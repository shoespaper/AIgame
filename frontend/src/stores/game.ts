import { defineStore } from 'pinia'
import { applyAction, initializeGame, loadGame, saveGame, type ActionType } from '../api/client'
import type { GameInstanceResponse, InitializePayload } from '../api/types'

export interface TimelineEvent {
  id: number
  kind: 'system' | 'action' | 'result' | 'error'
  title: string
  detail: string
  time: string
}

const CURRENT_GAME_KEY = 'aigame.currentGameId'
const actionLabels: Record<ActionType, string> = {
  rest: '休息', consume_ration: '消耗口粮', use_potion: '使用药水', advance_day: '推进一天',
}
function nowLabel(): string { return new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit' }).format(new Date()) }
function event(kind: TimelineEvent['kind'], title: string, detail: string): TimelineEvent {
  return { id: Date.now() + Math.floor(Math.random() * 1000), kind, title, detail, time: nowLabel() }
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
  }),
  getters: { currentGameId: (state) => state.game?.gameId || localStorage.getItem(CURRENT_GAME_KEY) || '' },
  actions: {
    remember(gameId: string) { localStorage.setItem(CURRENT_GAME_KEY, gameId) },
    clearMessages() { this.error = ''; this.notice = '' },
    async initialize(gameId: string, payload: InitializePayload) {
      this.loading = true; this.clearMessages()
      try {
        this.game = await initializeGame(gameId, payload)
        this.remember(gameId)
        this.events = [event('system', '新的远征已建立', '世界状态和角色初始状态已由服务器确认。')]
        return this.game
      } catch (cause) { this.error = cause instanceof Error ? cause.message : '创建游戏失败'; throw cause
      } finally { this.loading = false }
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
      } catch (cause) { this.error = cause instanceof Error ? cause.message : '保存失败'; throw cause
      } finally { this.saving = false }
    },
    async act(type: ActionType) {
      if (!this.game) return
      this.acting = true; this.clearMessages()
      const before = this.game
      try {
        this.events.push(event('action', actionLabels[type], '行动已提交，等待 AP 规则集结算。'))
        this.game = await applyAction(before.gameId, before.revision, type)
        const state = this.game.rulesetState
        const detail = state
          ? `生命 ${state.hpCurrent}/${state.hpMax}，精神 ${state.spCurrent}/${state.spMax}，口粮 ${state.resources.rations}。`
          : `世界时间已更新至第 ${this.game.world.day} 天。`
        this.events.push(event('result', '规则结算完成', detail))
        return this.game
      } catch (cause) {
        this.events.push(event('error', '行动未完成', cause instanceof Error ? cause.message : '服务器拒绝了这次行动。'))
        this.error = cause instanceof Error ? cause.message : '行动失败'; throw cause
      } finally { this.acting = false }
    },
    reset() { this.game = null; this.events = []; this.clearMessages() },
  },
})
