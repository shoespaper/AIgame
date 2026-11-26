<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { listGames } from '../api/client'
import { useGameStore } from '../stores/game'
import NarrativeTurn from '../components/NarrativeTurn.vue'
import CreateGameFlow from '../components/CreateGameFlow.vue'
import StatusSidebar from '../components/StatusSidebar.vue'

type Phase = 'selection' | 'initialization' | 'play' | 'saves'
const route = useRoute(); const router = useRouter(); const store = useGameStore()
const saves = ref<Awaited<ReturnType<typeof listGames>>>([])
const phase = computed<Phase>(() => route.query.phase === 'saves' ? 'saves' : route.params.gameId ? 'play' : 'selection')
const answer = ref(''); const error = ref(''); const busy = ref(false)
const currentWorld = computed(() => store.game?.world)

/** 从存档列表载入指定存档并跳转到游玩页。 */
/** @param gameId 存档的游戏标识 */
async function loadSaved(gameId: string) {
  busy.value = true
  try { await store.load(gameId); await router.replace(`/game/${encodeURIComponent(gameId)}`) }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '无法加载存档。' }
  finally { busy.value = false }
}

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

onMounted(async () => {
  try {
    if (phase.value === 'play' && route.params.gameId) {
      const gameId = String(route.params.gameId)
      if (store.game?.gameId !== gameId) await loadSaved(gameId)
    } else if (phase.value === 'saves') saves.value = await listGames()
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '页面数据加载失败。' }
})
</script>

<template>
  <CreateGameFlow v-if="phase === 'selection' || phase === 'initialization'" />

  <section v-else-if="phase === 'saves'" class="page-content game-flow">
    <div class="section-heading">
      <div>
        <p class="eyebrow">SAVES</p>
        <h1>加载存档</h1>
        <p class="lede">选择一局继续游玩。</p>
      </div>
      <button class="quiet-button" type="button" @click="router.push('/')">返回首页</button>
    </div>
    <div class="save-list">
      <button v-for="save in saves" :key="save.gameId" class="save-row" type="button" @click="loadSaved(save.gameId)">
        <strong>{{ save.gameId }}</strong>
        <span>{{ save.worldId }} · {{ save.engineId || '纯叙事' }}</span>
        <small>第 {{ save.day }} 天 · revision {{ save.revision }} · {{ save.updatedAt }}</small>
      </button>
      <p v-if="!saves.length" class="empty-timeline">暂无存档。</p>
    </div>
  </section>

  <section v-else-if="store.game" class="play-page">
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
</template>
