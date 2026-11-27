import { defineStore } from 'pinia'
import { answerWorldbuild, createWorldbuildSession, saveWorldbuild } from '../api/client'
import type { WorldbuildTurn } from '../api/types'

/** 世界构建对话中的玩家回答或 GM 回合。 */
export interface WorldbuildMessage {
  id: number
  role: 'gm' | 'player'
  text: string
}

/** 保存独立于角色初始化的世界构建对话和当前已验收的草案。 */
export const useWorldbuildStore = defineStore('worldbuild', {
  state: () => ({ sessionId: '', turn: null as WorldbuildTurn | null, busy: false, history: [] as WorldbuildMessage[] }),
  actions: {
    /** 建立世界构建会话，失败时保留已有对话。 */
    async start() {
      this.busy = true
      try {
        const session = await createWorldbuildSession()
        this.sessionId = session.id
        this.turn = session.turn
        this.history = []
      } finally { this.busy = false }
    },
    /** 提交玩家回答，成功后追加两条对话记录。
     * @param answer 玩家自然语言回答
     */
    async answer(answer: string) {
      if (!this.sessionId || this.busy) return
      this.busy = true
      try {
        const result = await answerWorldbuild(this.sessionId, answer)
        this.turn = result.turn
        this.history.push({ id: this.history.length + 1, role: 'player', text: answer })
        this.history.push({ id: this.history.length + 1, role: 'gm', text: result.turn.reply })
      } finally { this.busy = false }
    },
    /** 保存当前已验收版本，返回新世界书标识。 */
    async save() {
      if (!this.sessionId || !this.turn?.done) throw new Error('世界草案尚未通过验收。')
      return saveWorldbuild(this.sessionId, this.turn.version)
    },
    /** 清理世界构建状态以开始新的配置。 */
    reset() { this.sessionId = ''; this.turn = null; this.history = [] },
  },
})
