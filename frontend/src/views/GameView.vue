<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useGameStore } from '../stores/game'
import NarrativeTurn from '../components/NarrativeTurn.vue'
import CreateGameFlow from '../components/CreateGameFlow.vue'
import StatusSidebar from '../components/StatusSidebar.vue'
import SavesView from './SavesView.vue'

const route = useRoute(); const router = useRouter(); const store = useGameStore()
const showSaves = computed(() => route.query.phase === 'saves')
const gameId = computed(() => !showSaves.value && typeof route.params.gameId === 'string' ? route.params.gameId : '')
const answer = ref(''); const error = ref('')
const currentWorld = computed(() => store.game?.world)

/** 点选候选行动：当作玩家输入发送。 */
/** @param label 候选行动的文本 */
function choose(label: string) { void store.speak(label) }

/** 发送自由输入并清空输入框。 */
async function send() {
  const text = answer.value.trim()
  if (!text || store.acting) return
  answer.value = ''
  try { await store.speak(text) } catch { /* store 会暴露用户可见错误 */ }
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
          <div v-if="store.acting" class="conversation-message gm">
            <span>GM</span>
            <p class="processing-label">正在回应…</p>
          </div>
          <p v-if="!store.conversation.length && !store.acting" class="empty-timeline">还没有对话。</p>
        </div>
        <div v-if="store.narration" class="current-options">
          <NarrativeTurn :turn="{ ...store.narration, narration: '' }" :acting="store.acting" @choose="choose" />
        </div>
        <div class="free-input">
          <input v-model="answer" placeholder="告诉 GM 你要做什么" @keyup.enter="send" />
          <button class="primary-button" type="button" :disabled="store.acting || !answer.trim()" @click="send">发送</button>
          <button class="secondary-button" type="button" :disabled="store.saving" @click="store.save">保存游戏</button>
          <button class="quiet-button" type="button" @click="router.push('/')">退出</button>
        </div>
      </main>
    </div>
  </section>
  <p v-else class="loading-state" role="status">正在加载存档…</p>
</template>
