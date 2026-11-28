<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { EngineActionChoice, JsonValue } from '../api/types'
import { useGameStore } from '../stores/game'
import NarrativeTurn from '../components/NarrativeTurn.vue'
import NarrationRetryNotice from '../components/NarrationRetryNotice.vue'

const route = useRoute(); const router = useRouter(); const game = useGameStore(); const loadError = ref('')
const playerInput = ref('')
const inputField = ref<HTMLInputElement | null>(null)
const state = computed(() => game.game?.rulesetState); const world = computed(() => game.game?.world)
/** 从路由读取当前游戏标识。 */
function idFromRoute(): string { return String(route.params.gameId || '') }
/** 按路由中的游戏标识加载服务器权威状态。 */
async function load() { const id = idFromRoute(); if (!id) { await router.replace('/'); return }; loadError.value = ''; if (game.game?.gameId === id) return; try { await game.load(id) } catch (cause) { loadError.value = cause instanceof Error ? cause.message : '无法加载游戏。' } }
/** 将通用 JSON 引擎状态格式化为只读文本。 */
/** @param value 当前引擎返回的 JSON 状态 */
function formatState(value: JsonValue): string { return JSON.stringify(value, null, 2) }
/** 保存当前游戏的服务端快照。 */
async function save() { try { await game.save() } catch { /* store exposes the user-facing error */ } }
/** 发送当前输入，失败时恢复本次发送的原文。 */
async function speak() {
  const text = playerInput.value
  const id = idFromRoute()
  if (!text.trim() || game.acting || game.pendingTurn) return
  playerInput.value = ''
  try {
    if (await game.speak(text)) return
  } catch { /* store 会暴露用户可见错误 */ }
  await restoreInput(id, text)
}
/** 使用原回合标识查询中断的回复。 */
async function resumeTurn() {
  const pending = game.pendingTurn
  if (!pending || await game.resumeTurn()) return
  if (!pending.choiceId) await restoreInput(pending.gameId, pending.playerInput)
}
/** 回合确认失败后恢复玩家原文。
 * @param id 回合所属游戏
 * @param text 玩家原始输入
 */
async function restoreInput(id: string, text: string) {
  if (idFromRoute() !== id || game.pendingTurn || playerInput.value !== '') return
  playerInput.value = text
  await nextTick()
  inputField.value?.focus()
}
/** 将候选行动填入输入框，供玩家编辑和发送。 */
/** @param label 候选行动的文本 */
function choose(label: string) { playerInput.value = label }
/** 执行引擎已确认的选项，并保留输入框里的草稿。
 * @param choice 当前游戏状态提供的执行选项
 */
async function execute(choice: EngineActionChoice) {
  try { await game.speak(choice.label, choice.id) } catch { /* store 会暴露用户可见错误 */ }
}
watch(() => route.params.gameId, () => { void load() }); onMounted(() => { void load() })
</script>

<template>
  <section v-if="game.loading && !game.game" class="loading-state"><span class="loader" />正在读取游戏状态…</section>
  <section v-else-if="loadError" class="error-state page-content"><p class="eyebrow">LOAD FAILED</p><h1>这局远征暂时无法读取</h1><p class="form-error" role="alert">{{ loadError }}</p><div><button class="primary-button" type="button" @click="load">重试</button><button class="quiet-button" type="button" @click="router.push('/')">返回游戏桌</button></div></section>
  <section v-else-if="game.game && world" class="play-page">
    <div class="play-header"><div><p class="eyebrow">{{ (game.game.rulesetId ? game.game.rulesetId.toUpperCase() : 'NARRATIVE') }} / REVISION {{ game.game.revision }}</p><h1>{{ world.setting || '未命名世界' }}</h1><p>{{ world.settingDescription || '世界正在等待你的第一个选择。' }}</p></div><div class="play-actions"><button class="quiet-button" type="button" :disabled="game.loading" @click="load">重新读取</button><button class="secondary-button" type="button" :disabled="game.saving" @click="save">{{ game.saving ? '保存中…' : '保存游戏' }}</button></div></div>
    <div v-if="game.error || game.notice" class="message-bar" :class="{ error: game.error }" role="status"><span>{{ game.error || game.notice }}</span><button type="button" aria-label="关闭提示" @click="game.clearMessages()">×</button></div>
    <div class="play-layout">
      <main class="story-column">
        <div class="story-heading"><div><span class="panel-kicker">CURRENT SESSION</span><h2>本次操作</h2></div><span class="revision-badge">第 {{ world.day }} 天 · {{ world.time || '未知时刻' }}</span></div>
        <div class="timeline" aria-live="polite"><article v-for="item in game.events" :key="item.id" class="timeline-item" :class="item.kind"><div class="timeline-marker" aria-hidden="true" /><div class="timeline-copy"><div class="timeline-meta"><strong>{{ item.title }}</strong><time>{{ item.time }}</time></div><p>{{ item.detail }}</p></div></article><div v-if="!game.events.length" class="empty-timeline">还没有行动记录。选择右侧的行动，开始这场远征。</div></div>
        <div class="action-composer"><div class="composer-heading"><div><span class="panel-kicker">GM CONVERSATION</span><h2>你要怎么做？</h2></div><span v-if="game.acting" class="processing-label"><span class="loader small" />处理中</span></div>
          <NarrativeTurn v-if="game.narration || game.game.actionChoices.length" :turn="game.narration"
            :action-choices="game.game.actionChoices" :acting="game.acting || !!game.pendingTurn" @choose="choose" @execute="execute" />
          <div v-if="game.pendingTurn" class="conversation-message player"><span>你</span><p>{{ game.pendingTurn.playerInput }}</p></div>
          <NarrationRetryNotice :hint="game.retryHint" :message="game.turnError"
            :can-resume="!!game.pendingTurn && !game.acting" @resume="resumeTurn" />
          <div class="free-input"><input ref="inputField" v-model="playerInput" placeholder="自由输入行动或问题" :disabled="!!game.pendingTurn" @keyup.enter="speak" /><button class="primary-button" type="button" :disabled="game.acting || !!game.pendingTurn || !playerInput.trim()" @click="speak">发送</button></div>
        </div>
      </main>
      <aside class="status-column">
        <section v-if="state" class="status-panel character-panel"><div class="panel-heading compact-heading"><span class="panel-kicker">ENGINE STATE</span><h2>{{ game.game.rulesetId || '纯叙事' }}</h2><p>由所选引擎返回的只读状态。</p></div><pre class="engine-state">{{ formatState(state) }}</pre></section>
        <section class="status-panel world-panel"><div class="panel-heading compact-heading"><span class="panel-kicker">WORLD STATE</span><h2>当前世界</h2></div><dl class="world-facts"><div><dt>位置</dt><dd>{{ world.location || '未指定' }}</dd></div><div><dt>区域</dt><dd>{{ world.currentRegion || '未指定' }}</dd></div></dl><div class="quest-callout"><span>主线方向</span><strong>{{ world.mainQuest || '尚未确定' }}</strong></div><div v-if="world.originScene" class="origin-scene"><span>开场场景</span><p>{{ world.originScene }}</p></div></section>
      </aside>
    </div>
  </section>
</template>
