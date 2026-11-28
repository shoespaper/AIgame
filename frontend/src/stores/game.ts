import { defineStore } from 'pinia'
import type { CreationProgress } from '../api/types'
import { loadGame, saveGame, narrate, streamNarration, ApiError, finalizeInitialization } from '../api/client'
import type { ConfirmedEvent, GameInstanceResponse, NarrativeResponse, ConversationMessage, TextNarrationTurn } from '../api/types'

export interface TimelineEvent {
  id: number
  kind: 'system' | 'action' | 'result' | 'error'
  title: string
  detail: string
  time: string
}

const CURRENT_GAME_KEY = 'aigame.currentGameId'
function conversationKey(gameId: string): string { return `aigame.conversation.${gameId}` }
function readConversation(gameId: string): ConversationMessage[] {
  try {
    const value: unknown = JSON.parse(localStorage.getItem(conversationKey(gameId)) || '[]')
    return Array.isArray(value) ? value.filter((item): item is ConversationMessage => item && (item.role === 'player' || item.role === 'gm') && typeof item.text === 'string') : []
  } catch { return [] }
}
function nowLabel(): string { return new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit' }).format(new Date()) }

/** 创建可在同一回合重连时复用的随机标识。 */
function turnId(): string {
  if (crypto.randomUUID) return crypto.randomUUID()
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  const hex = Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}
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
    loadRequestId: 0,
    saving: false,
    acting: false,
    error: '',
    notice: '',
    narration: null as NarrativeResponse | null,
    conversation: [] as ConversationMessage[],
    pendingTurn: null as TextNarrationTurn | null,
    turnError: '',
    retryHint: '',
    retryQueue: [] as string[],
    retryTimer: null as number | null,
  }),
  getters: { currentGameId: (state) => state.game?.gameId || localStorage.getItem(CURRENT_GAME_KEY) || '' },
  actions: {
    remember(gameId: string) { localStorage.setItem(CURRENT_GAME_KEY, gameId) },
    clearMessages() { this.error = ''; this.notice = '' },
    /** 清理上一条消息的浮现提示和游玩回合错误。 */
    clearTurnMessages() {
      if (this.retryTimer !== null) window.clearTimeout(this.retryTimer)
      this.retryTimer = null
      this.retryHint = ''
      this.retryQueue = []
      this.turnError = ''
    },
    /** 按失败顺序逐条显示提示，服务端重试无需等待动画。
     * @param message 单次失败的简短文案，可省略以继续显示队列中的下一条
     */
    showRetryHint(message?: string) {
      if (message) this.retryQueue.push(message)
      if (this.retryTimer !== null) return
      this.retryHint = this.retryQueue.shift() ?? ''
      if (!this.retryHint) return
      this.retryTimer = window.setTimeout(() => {
        this.retryTimer = null
        this.showRetryHint()
      }, 1000)
    },
    /** 在服务端确认删除后清理对应的游戏状态、对话缓存和最近游玩记录。
     * @param gameId 已删除的游戏存档标识
     */
    forgetSave(gameId: string) {
      if (this.game?.gameId === gameId) {
        this.game = null
        this.events = []
        this.narration = null
        this.conversation = []
        this.pendingTurn = null
        this.clearTurnMessages()
        this.clearMessages()
      }
      try {
        localStorage.removeItem(conversationKey(gameId))
        if (localStorage.getItem(CURRENT_GAME_KEY) === gameId) localStorage.removeItem(CURRENT_GAME_KEY)
      } catch { /* 浏览器限制本地存储时，服务端删除结果仍然有效。 */ }
    },
    /** 确认已校验的角色表单并保存游玩页需要的开场和提示。
     * @param id 初始化会话标识
     * @param version 确认页使用的表单版本
     * @param onProgress 接收开局确认中的模板和开场进度
     */
    async finalizeInitialization(id: string, version: number, onProgress: (progress: CreationProgress) => void) {
      this.clearMessages()
      try {
        const result = await finalizeInitialization(id, version, onProgress)
        this.game = result.game
        this.pendingTurn = null
        this.clearTurnMessages()
        this.remember(this.game.gameId)
        this.conversation = []
        this.events = [event('system', '新的远征已建立', '世界状态和角色初始状态已由服务器确认。')]
        if (result.opening) {
          this.narration = result.opening
          this.conversation.push({ role: 'gm', text: result.opening.narration })
          result.opening.confirmedEvents.forEach((item) => this.events.push(confirmedEvent(item)))
        }
        localStorage.setItem(conversationKey(this.game.gameId), JSON.stringify(this.conversation))
        return this.game
      } catch (cause) { this.error = cause instanceof Error ? cause.message : '创建游戏失败'; throw cause
      }
    },
    /** 加载指定权威存档和本地对话，清除上一次游玩留下的临时选项。
     * @param gameId 待加载的游戏存档标识
     */
    async load(gameId: string) {
      const requestId = ++this.loadRequestId
      this.loading = true; this.clearMessages()
      try {
        const game = await loadGame(gameId)
        if (requestId !== this.loadRequestId) return
        this.remember(gameId)
        this.game = game
        if (this.pendingTurn?.gameId !== gameId) this.pendingTurn = null
        this.clearTurnMessages()
        this.narration = null
        this.conversation = readConversation(gameId)
        this.events = [event('system', '已恢复存档', `已读取第 ${game.revision} 次状态修订。`)]
        return game
      } catch (cause) {
        if (requestId !== this.loadRequestId) return
        this.error = cause instanceof Error ? cause.message : '读取存档失败'
        throw cause
      } finally {
        if (requestId === this.loadRequestId) this.loading = false
      }
    },
    async save() {
      if (!this.game) return
      this.saving = true; this.clearMessages()
      try {
        await saveGame(this.game.gameId, this.game.revision)
        this.notice = '存档已确认。'
        this.events.push(event('system', '存档完成', `修订号 ${this.game.revision} 已写入服务器。`))
      } catch (cause) { if (cause instanceof ApiError && cause.code === 'REVISION_MISMATCH') { await this.load(this.game.gameId); this.notice = '存档已更新，已刷新最新状态。'; return }; this.error = cause instanceof Error ? cause.message : '保存失败'; throw cause
      } finally { this.saving = false }
    },
    /** 发送自由文本或直接执行已确认的引擎选项。 */
    /** @param input 玩家自由输入或直接选项的展示文字 */
    /** @param choiceId 若为直接执行按钮，传入当前状态提供的行动标识 */
    async speak(input: string, choiceId?: string) {
      if (!this.game || this.acting || this.pendingTurn) return
      if (!choiceId) {
        this.clearTurnMessages()
        this.pendingTurn = {
          id: turnId(), gameId: this.game.gameId, expectedRevision: this.game.revision,
          playerInput: input, history: this.conversation.slice(-40),
        }
        return this.resumeTextTurn()
      }
      this.acting = true; this.clearMessages()
      const gameId = this.game.gameId; const revision = this.game.revision
      const transcript = [...this.conversation]
      this.conversation.push({ role: 'player', text: input })
      try {
        const result = await narrate(gameId, revision, input, transcript, choiceId)
        this.game = result.game
        this.narration = result
        this.conversation.push({ role: 'gm', text: result.narration })
        localStorage.setItem(conversationKey(gameId), JSON.stringify(this.conversation))
        result.confirmedEvents.forEach((item) => this.events.push(confirmedEvent(item)))
        return result
      }
      catch (cause) { this.conversation = transcript; if (cause instanceof ApiError && cause.code === 'REVISION_MISMATCH') { await this.load(gameId); this.notice = '存档已更新，已刷新最新状态。'; return }; this.error = cause instanceof Error ? cause.message : '叙事请求失败'; throw cause }
      finally { this.acting = false }
    },
    /** 完成或恢复当前文本回合，重连沿用原标识，待确认时保留原文与已知回执。 */
    async resumeTextTurn() {
      const pending = this.pendingTurn
      if (!pending || this.acting || this.game?.gameId !== pending.gameId) return
      this.acting = true
      this.clearMessages()
      this.turnError = ''
      let receipt: NarrativeResponse | undefined
      const isCurrent = () => this.pendingTurn?.id === pending.id && this.game?.gameId === pending.gameId
      const receive = () => streamNarration(pending, (update) => {
        if (!isCurrent()) return
        if (update.type === 'attempt_failed') this.showRetryHint(`第 ${update.attempt} 次 · ${update.message}`)
        if (update.type === 'committed') {
          receipt = update.result
          this.game = update.result.game
          this.narration = update.result
        }
      })
      try {
        let result: NarrativeResponse
        try {
          result = await receive()
        } catch (cause) {
          if (receipt) result = receipt
          else if (isCurrent() && cause instanceof ApiError && (cause.status === 0 || cause.code === 'RESULT_UNCONFIRMED')) {
            try { result = await receive() }
            catch (reattachFailure) {
              if (!receipt) throw reattachFailure
              result = receipt
            }
          } else throw cause
        }
        if (!isCurrent()) return result
        this.game = result.game
        this.narration = result
        this.pendingTurn = null
        this.conversation.push({ role: 'player', text: pending.playerInput }, { role: 'gm', text: result.narration })
        try {
          localStorage.setItem(conversationKey(pending.gameId), JSON.stringify(this.conversation))
        } catch { /* 浏览器存储受限时，已确认的回合仍然可见。 */ }
        result.confirmedEvents.forEach(item => this.events.push(confirmedEvent(item)))
        return result
      } catch (cause) {
        if (!isCurrent()) return
        if (cause instanceof ApiError && (cause.status === 0 || cause.code === 'RESULT_UNCONFIRMED' || cause.status >= 500)) {
          this.turnError = '暂未确认本轮结果，请查询后继续。'
          return
        }
        this.pendingTurn = null
        if (cause instanceof ApiError && cause.code === 'REVISION_MISMATCH') {
          try { await this.load(pending.gameId) } catch { /* 读取失败可由现有存档提示处理。 */ }
        }
        this.turnError = cause instanceof Error ? cause.message : 'GM 暂时无法回复，原文已恢复。'
      } finally {
        this.acting = false
      }
    },
    /** 清除当前游戏及尚未结束的界面状态。 */
    reset() {
      this.game = null; this.events = []; this.narration = null; this.conversation = []
      this.pendingTurn = null
      this.clearMessages()
      this.clearTurnMessages()
    },
  },
})
