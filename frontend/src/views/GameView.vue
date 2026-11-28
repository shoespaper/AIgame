<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { EngineActionChoice } from '../api/types'
import { useGameStore } from '../stores/game'
import NarrativeTurn from '../components/NarrativeTurn.vue'
import NarrationRetryNotice from '../components/NarrationRetryNotice.vue'
import CreateGameFlow from '../components/CreateGameFlow.vue'
import StatusSidebar from '../components/StatusSidebar.vue'
import SavesView from './SavesView.vue'

const route = useRoute(); const router = useRouter(); const store = useGameStore()
const showSaves = computed(() => route.query.phase === 'saves')
const gameId = computed(() => !showSaves.value && typeof route.params.gameId === 'string' ? route.params.gameId : '')
const answer = ref(''); const error = ref('')
const answerField = ref<HTMLInputElement | null>(null)
const currentWorld = computed(() => store.game?.world)

/** 将候选行动填入输入框，供玩家编辑和发送。 */
/** @param label 候选行动的文本 */
function choose(label: string) { answer.value = label }

/** 执行引擎已确认的选项，并保留输入框里的草稿。
 * @param choice 当前游戏状态提供的执行选项
 */
async function execute(choice: EngineActionChoice) {
  try { await store.speak(choice.label, choice.id) } catch { /* store 会暴露用户可见错误 */ }
}

/** 发送当前输入，失败时恢复本次发送的原文。 */
async function send() {
  const text = answer.value
  const id = gameId.value
  if (!text.trim() || store.acting || store.pendingTurn) return
  answer.value = ''
  try {
    if (await store.speak(text)) return
  } catch { /* store 会暴露用户可见错误 */ }
  await restoreAnswer(id, text)
}

/** 使用原回合标识查询中断的回复。 */
async function resumeTurn() {
  const pending = store.pendingTurn
  if (!pending || await store.resumeTextTurn()) return
  await restoreAnswer(pending.gameId, pending.playerInput)
}

/** 回合确认失败后恢复原文并将焦点放回输入框。
 * @param id 回合所属游戏
 * @param text 玩家原始输入
 */
async function restoreAnswer(id: string, text: string) {
  if (gameId.value !== id || store.pendingTurn || answer.value !== '') return
  answer.value = text
  await nextTick()
  answerField.value?.focus()
}

watch(gameId, async (id, _, onCleanup) => {
  error.value = ''
  answer.value = ''
  if (!id || (!store.loading && store.game?.gameId === id)) return
  let active = true
  onCleanup(() => { active = false })
  try { await store.load(id) }
  catch (cause) {
    if (active) error.value = cause instanceof Error ? cause.message : '无法加载存档。'
  }
}, { immediate: true })
</script>

<template>
  <SavesView v-if="showSaves" />
  <CreateGameFlow v-else-if="!gameId" />

  <section v-else-if="error" class="page-content game-flow">
    <h1>无法加载存档</h1>
    <p class="form-error" role="alert">{{ error }}</p>
    <button class="secondary-button" type="button" @click="router.push({ name: 'game', query: { phase: 'saves' } })">
      返回存档列表
    </button>
  </section>

  <section v-else-if="store.game?.gameId === gameId" class="play-page">
    <div v-if="store.error || store.notice" class="message-bar" :class="{ error: store.error }" role="status">{{ store.error || store.notice }}</div>
    <div class="play-layout">
      <StatusSidebar :ruleset-state="store.game.rulesetState" :world="store.game.world" :ruleset-id="store.game.rulesetId" />
      <main class="story-column">
        <div class="story-heading">
          <h2>GM 对话</h2>
          <span class="revision-badge">第 {{ currentWorld?.day }} 天 · {{ currentWorld?.time || '未知时刻' }}</span>
        </div>
        <div class="conversation-log" aria-live="polite">
          <article v-for="(message, index) in store.conversation" :key="`${index}-${message.role}`" class="conversation-message" :class="message.role">
            <span>{{ message.role === 'gm' ? 'GM' : '你' }}</span>
            <p>{{ message.text }}</p>
          </article>
          <article v-if="store.pendingTurn" class="conversation-message player">
            <span>你</span>
            <p>{{ store.pendingTurn.playerInput }}</p>
          </article>
          <div v-if="store.acting" class="conversation-message gm">
            <span>GM</span>
            <p class="processing-label">正在回应…</p>
          </div>
          <p v-if="!store.conversation.length && !store.acting" class="empty-timeline">还没有对话。</p>
        </div>
        <div v-if="store.narration || store.game.actionChoices.length" class="current-options">
          <NarrativeTurn :turn="store.narration && { ...store.narration, narration: '' }"
            :action-choices="store.game.actionChoices" :acting="store.acting || !!store.pendingTurn" @choose="choose" @execute="execute" />
        </div>
        <NarrationRetryNotice :hint="store.retryHint" :message="store.turnError"
          :can-resume="!!store.pendingTurn && !store.acting" @resume="resumeTurn" />
        <div class="free-input">
          <input ref="answerField" v-model="answer" placeholder="告诉 GM 你要做什么" :disabled="!!store.pendingTurn" @keyup.enter="send" />
          <button class="primary-button" type="button" :disabled="store.acting || !!store.pendingTurn || !answer.trim()" @click="send">发送</button>
          <button class="secondary-button" type="button" :disabled="store.saving" @click="store.save">保存游戏</button>
          <button class="quiet-button" type="button" @click="router.push('/')">退出</button>
        </div>
      </main>
    </div>
  </section>
  <p v-else class="loading-state" role="status">正在加载存档…</p>
</template>
