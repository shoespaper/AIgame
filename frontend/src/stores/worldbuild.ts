import { defineStore } from 'pinia'
import { answerWorldbuild, createWorldbuildSession } from '../api/client'
import type { WorldbuildTurn } from '../api/types'
import type { InitMessage } from './game'

/** 保存独立于角色初始化的世界构建对话和当前结构化配置。 */
export const useWorldbuildStore = defineStore('worldbuild', {
  state: () => ({ sessionId: '', turn: null as WorldbuildTurn | null, busy: false, history: [] as InitMessage[] }),
  actions: {
    /** 建立世界构建会话，失败时保留已有对话。
     * @param templateId 可空的优先复用模板标识
     */
    async start(templateId?: string) {
      this.busy = true
      try {
        const session = await createWorldbuildSession(templateId)
        this.sessionId = session.id
        this.turn = session.turn
        this.history = [{ id: 1, role: 'gm', text: session.turn.narration, question: session.turn.question }]
      } finally { this.busy = false }
    },
    /** 提交玩家回答，成功后追加两条对话记录。
     * @param answer 玩家自然语言回答
     */
    async answer(answer: string) {
      if (!this.sessionId || this.busy || this.turn?.done) return
      this.busy = true
      try {
        const result = await answerWorldbuild(this.sessionId, answer)
        this.turn = result.turn
        this.history.push({ id: this.history.length + 1, role: 'player', text: answer })
        this.history.push({ id: this.history.length + 1, role: 'gm', text: result.turn.narration, question: result.turn.question })
      } finally { this.busy = false }
    },
    /** 清理世界构建状态以开始新的配置。 */
    reset() { this.sessionId = ''; this.turn = null; this.history = [] },
  },
})
