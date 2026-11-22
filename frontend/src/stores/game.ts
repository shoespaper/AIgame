import { defineStore } from 'pinia'
import { loadGame, saveGame, narrate, ApiError, createInitializationSession, answerInitialization, finalizeInitialization } from '../api/client'
import type { ConfirmedEvent, GameInstanceResponse, GameSelection, NarrativeResponse, InitializationTurn, InitializationQuestion, InitializationParseCandidate } from '../api/types'

export interface TimelineEvent {
  id: number
  kind: 'system' | 'action' | 'result' | 'error'
  title: string
  detail: string
  time: string
}

/** 初始化对话流中的一条消息：GM 话术或玩家回答，GM 消息可附带当前问题与解析候选。 */
export interface InitMessage {
  id: number
  role: 'gm' | 'player'
  text: string
  question?: InitializationQuestion | null
  parseCandidates?: InitializationParseCandidate[]
}

const CURRENT_GAME_KEY = 'aigame.currentGameId'
function nowLabel(): string { return new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit' }).format(new Date()) }
function event(kind: TimelineEvent['kind'], title: string, detail: string): TimelineEvent {
  return { id: Date.now() + Math.floor(Math.random() * 1000), kind, title, detail, time: nowLabel() }
}
/** 将服务端确认事件转换为时间线展示项。 */
/** @param item 引擎或世界处理后确认的事件 */
function confirmedEvent(item: ConfirmedEvent): TimelineEvent {
  return event('result', `${item.domain} · ${item.type}`, `${item.subject}${item.target ? ` → ${item.target}` : ''}${item.amount === null ? '' : ` · ${item.amount}`}：${item.outcome}`)
}

export const useGameStore = defineStore('game', {
  state: () => ({
    game: null as GameInstanceResponse | null,
    events: [] as TimelineEvent[],
    loading: false,
    saving: false,
    acting: false,
    error: '',
    notice: '',
    narration: null as NarrativeResponse | null,
    initializationSessionId: '',
    initializationTurn: null as InitializationTurn | null,
    initializationBusy: false,
    initializationHistory: [] as InitMessage[],
  }),
  getters: { currentGameId: (state) => state.game?.gameId || localStorage.getItem(CURRENT_GAME_KEY) || '' },
  actions: {
    remember(gameId: string) { localStorage.setItem(CURRENT_GAME_KEY, gameId) },
    clearMessages() { this.error = ''; this.notice = '' },
    /** 创建 AI 初始化会话并保存首个 GM 回合。
     * @param selection 玩家选择的世界书和可选引擎
     */
    async startInitialization(selection: GameSelection) {
      this.initializationBusy = true; this.clearMessages()
      try {
        const session = await createInitializationSession(selection)
        this.initializationSessionId = session.id
        this.initializationTurn = session.turn
        this.initializationHistory = []
        this.pushInitMessage('gm', session.turn.narration, session.turn.question, session.turn.parseCandidates)
        return session
      } catch (cause) { this.error = cause instanceof Error ? cause.message : '无法开始初始化问答'; throw cause
      } finally { this.initializationBusy = false }
    },
    /** 在初始化对话流中追加一条 GM 或玩家消息。
     * @param role 消息角色
     * @param text 消息正文
     * @param question 当前 GM 提问（仅 GM 消息）
     * @param parseCandidates 已接受的解析候选（仅 GM 消息）
     */
    pushInitMessage(role: 'gm' | 'player', text: string, question?: InitializationQuestion | null, parseCandidates?: InitializationParseCandidate[]) {
      this.initializationHistory.push({ id: Date.now() + Math.floor(Math.random() * 1000), role, text, question, parseCandidates })
    },
    /** 提交初始化自然语言回答并更新 GM 回合。
     * @param answer 玩家当前回答
     */
    async answerInitialization(answer: string) {
      if (!this.initializationSessionId) return null
      this.initializationBusy = true; this.clearMessages()
      this.pushInitMessage('player', answer)
      try {
        const result = await answerInitialization(this.initializationSessionId, answer)
        this.initializationTurn = result.turn
        this.pushInitMessage('gm', result.turn.narration, result.turn.question, result.turn.parseCandidates)
        return result
      } catch (cause) { this.initializationHistory.pop(); this.error = cause instanceof Error ? cause.message : '初始化回答未通过校验'; throw cause
      } finally { this.initializationBusy = false }
    },
    /** 在 GM 回合标记完成后创建正式游戏并记录当前游戏。
     * @returns 新建的游戏实例
     */
    async finalizeInitialization() {
      if (!this.initializationSessionId) return null
      this.initializationBusy = true; this.clearMessages()
      try {
        const result = await finalizeInitialization(this.initializationSessionId)
        this.game = result.game
        this.remember(this.game.gameId)
        this.events = [event('system', '新的远征已建立', '世界状态和角色初始状态已由服务器确认。')]
        if (result.opening) {
          this.narration = result.opening
          result.opening.confirmedEvents.forEach((item) => this.events.push(confirmedEvent(item)))
        }
        this.initializationSessionId = ''; this.initializationTurn = null; this.initializationHistory = []
        return this.game
      } catch (cause) { this.error = cause instanceof Error ? cause.message : '创建游戏失败'; throw cause
      } finally { this.initializationBusy = false }
    },
    async load(gameId: string) {
      this.loading = true; this.clearMessages()
      try {
        this.game = await loadGame(gameId)
        this.remember(gameId)
        this.events = [event('system', '已恢复存档', `已读取第 ${this.game.revision} 次状态修订。`)]
        return this.game
      } catch (cause) { this.error = cause instanceof Error ? cause.message : '读取存档失败'; throw cause
      } finally { this.loading = false }
    },
    async save() {
      if (!this.game) return
      this.saving = true; this.clearMessages()
      try {
        await saveGame(this.game.gameId, this.game.revision)
        this.notice = '存档已确认。'
        this.events.push(event('system', '存档完成', `修订号 ${this.game.revision} 已写入服务器。`))
      } catch (cause) { if (cause instanceof ApiError && cause.message.toLowerCase().includes('revision mismatch')) { await this.load(this.game.gameId); this.notice = '存档已更新，已刷新最新状态。'; return }; this.error = cause instanceof Error ? cause.message : '保存失败'; throw cause
      } finally { this.saving = false }
    },
    /** 将自由输入交给 GM 叙事接口；服务端可能返回纯叙事或附带一次引擎结算。 */
    /** @param input 玩家自由输入 */
    async speak(input: string) {
      if (!this.game) return
      this.acting = true; this.clearMessages()
      const gameId = this.game.gameId; const revision = this.game.revision
      try {
        const result = await narrate(gameId, revision, input)
        this.game = result.game
        this.narration = result
        result.confirmedEvents.forEach((item) => this.events.push(confirmedEvent(item)))
        return result
      }
      catch (cause) { if (cause instanceof ApiError && cause.message.toLowerCase().includes('revision mismatch')) { await this.load(gameId); this.notice = '存档已更新，已刷新最新状态。'; return }; this.error = cause instanceof Error ? cause.message : '叙事请求失败'; throw cause }
      finally { this.acting = false }
    },
    reset() { this.game = null; this.events = []; this.narration = null; this.initializationSessionId = ''; this.initializationTurn = null; this.initializationHistory = []; this.clearMessages() },
  },
})
