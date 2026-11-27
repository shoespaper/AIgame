import { defineStore } from 'pinia'
import { loadGame, saveGame, narrate, ApiError, finalizeInitialization } from '../api/client'
import type { ConfirmedEvent, GameInstanceResponse, NarrativeResponse, ConversationMessage } from '../api/types'

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
  }),
  getters: { currentGameId: (state) => state.game?.gameId || localStorage.getItem(CURRENT_GAME_KEY) || '' },
  actions: {
    remember(gameId: string) { localStorage.setItem(CURRENT_GAME_KEY, gameId) },
    clearMessages() { this.error = ''; this.notice = '' },
    /** 在服务端确认删除后清理对应的游戏状态、对话缓存和最近游玩记录。
     * @param gameId 已删除的游戏存档标识
     */
    forgetSave(gameId: string) {
      if (this.game?.gameId === gameId) {
        this.game = null
        this.events = []
        this.narration = null
        this.conversation = []
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
     */
    async finalizeInitialization(id: string, version: number) {
      this.clearMessages()
      try {
        const result = await finalizeInitialization(id, version)
        this.game = result.game
        this.remember(this.game.gameId)
        this.conversation = []
        this.events = [event('system', '新的远征已建立', '世界状态和角色初始状态已由服务器确认。')]
        if (result.opening) {
          this.narration = result.opening
          this.conversation.push({ role: 'gm', text: result.opening.narration })
          result.opening.confirmedEvents.forEach((item) => this.events.push(confirmedEvent(item)))
        }
        localStorage.setItem(conversationKey(this.game.gameId), JSON.stringify(this.conversation))
        if (result.templateBuildFailed) this.notice = '模板构建失败，本局以无模板形式开始游戏。'
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
    /** 将自由输入交给 GM 叙事接口；服务端可能返回纯叙事或附带一次引擎结算。 */
    /** @param input 玩家自由输入 */
    async speak(input: string) {
      if (!this.game) return
      this.acting = true; this.clearMessages()
      const gameId = this.game.gameId; const revision = this.game.revision
      const transcript = [...this.conversation]
      this.conversation.push({ role: 'player', text: input })
      try {
        const result = await narrate(gameId, revision, input, transcript)
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
    reset() { this.game = null; this.events = []; this.narration = null; this.conversation = []; this.clearMessages() },
  },
})
