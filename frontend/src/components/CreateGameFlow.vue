<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import MarkdownIt from 'markdown-it'
import type { GameCatalog } from '../api/types'
import { loadGameCatalog } from '../api/client'
import { useGameStore } from '../stores/game'
import { useWorldbuildStore } from '../stores/worldbuild'

const router = useRouter()
const store = useGameStore()
const worldbuild = useWorldbuildStore()
const catalog = ref<GameCatalog | null>(null)
const step = ref(1)
const customMode = ref(false)
const worldChat = ref<HTMLElement | null>(null)
const characterChat = ref<HTMLElement | null>(null)
const worldAnswer = ref('')
const worldTab = ref<'draft' | 'chat'>('chat')
const busy = ref(false)
const error = ref('')
const answer = ref('')
const selection = reactive({ worldId: '', engineId: null as string | null })
const steps = ['世界', '规则', '角色', '确认']
const selectedWorld = computed(() => catalog.value?.worlds.find((world) => world.id === selection.worldId) || null)
const selectedEngine = computed(() => catalog.value?.engines.find((engine) => engine.id === selection.engineId) || null)
const markdown = new MarkdownIt({ html: false }).disable('image')
const worldDraftHtml = computed(() => markdown.render(worldbuild.turn?.draft ?? ''))

/** 新消息和等待状态变化后显示对话底部。 */
watch(() => [worldbuild.history.length, worldbuild.busy, worldTab.value], async () => {
  await nextTick()
  const chat = worldChat.value
  if (chat) chat.scrollTop = chat.scrollHeight
})
/** 显示角色访谈的最新消息，包括从确认页返回时。 */
watch(() => [step.value, store.initializationHistory.length, store.initializationBusy], async () => {
  await nextTick()
  const chat = characterChat.value
  if (chat) chat.scrollTop = chat.scrollHeight
})
const collectedEntries = computed(() => Object.entries(store.initializationTurn?.collected || {}))
/** 初始化字段 ID 到中文标签的映射，用于确认页展示。 */
const FIELD_LABELS: Record<string, string> = {
  characterName: '角色名字', age: '年龄', gender: '性别', archetype: '职业', difficulty: '难度',
  name: '世界名', description: '世界描述', location: '地点', region: '区域',
  time: '时间', mainQuest: '主线方向', originScene: '开场场景',
}
/** 枚举字段值到中文标签的映射。 */
const VALUE_LABELS: Record<string, string> = {
  swordsman: '剑士', mage: '法师', ranger: '游侠', alchemist: '炼金术师',
  adventure: '冒险', hard: '艰难', nightmare: '噩梦', narrative: '叙事',
}
/** 将字段 ID 转成中文标签，未知字段原样返回。
 * @param key 字段标识
 */
function fieldLabel(key: string): string { return FIELD_LABELS[key] || key }
/** 将字段值转成便于玩家核对的中文标签。
 * @param key 字段标识
 * @param value 字段原始值
 */
function valueLabel(key: string, value: unknown): string {
  const raw = String(value)
  return (key === 'archetype' || key === 'difficulty') ? (VALUE_LABELS[raw] || raw) : raw
}

/** 加载可选世界书和规则引擎目录。 */
async function loadCatalog() {
  try {
    catalog.value = await loadGameCatalog()
    selection.worldId = catalog.value.worlds[0]?.id || ''
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '无法读取世界目录。'
  }
}

/** 打开自定义世界对话，每次进入都全新开始。 */
async function startCustomWorld() {
  customMode.value = true
  await restartWorldbuild()
}

/** 退出自定义世界构建，清空会话状态以便下次全新开始。 */
function exitCustomWorld() {
  customMode.value = false
  worldbuild.reset()
}

/** 开启全新的世界构建对话。 */
async function restartWorldbuild() {
  error.value = ''; worldAnswer.value = ''
  worldTab.value = 'chat'
  try { await worldbuild.start() }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '无法开始世界构建对话。' }
}

/** 发送描述或修改意见，失败时保留输入和上一版草案。 */
async function submitWorldAnswer() {
  const input = worldAnswer.value.trim()
  if (!input || worldbuild.busy || busy.value) return
  error.value = ''
  const previousVersion = worldbuild.turn?.version
  try {
    await worldbuild.answer(input)
    worldAnswer.value = ''
    if (worldbuild.turn?.version !== previousVersion) worldTab.value = 'draft'
  } catch (cause) {
    const message = cause instanceof Error ? cause.message : '世界草案处理失败，请重试。'
    error.value = worldbuild.turn?.done ? `${message} 上次通过验收的草案仍可继续。` : message
  }
}

/** 完成世界构建并进入规则选择，进入前将世界书实例落盘。 */
async function finishCustomWorld() {
  if (!worldbuild.turn?.done || busy.value || worldbuild.busy) return
  busy.value = true; error.value = ''
  try {
    const result = await worldbuild.save()
    catalog.value = await loadGameCatalog()
    selection.worldId = result.worldbookId
    customMode.value = false
    step.value = 2
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '无法进入规则选择。' }
  finally { busy.value = false }
}

/** 进入角色初始化步骤并启动 GM 会话。 */
async function startCharacterSetup() {
  if (!selection.worldId) return
  error.value = ''
  busy.value = true
  try {
    await store.startInitialization(selection)
    answer.value = ''
    step.value = 3
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '无法开始开局确认。'
  } finally {
    busy.value = false
  }
}

/** 提交玩家对 GM 初始化问题的回答。
 * @param value 玩家输入的回答文本
 */
async function submitAnswer(value = answer.value) {
  const normalized = value.trim()
  if (!normalized || store.initializationBusy) return
  error.value = ''
  try {
    await store.answerInitialization(normalized)
    answer.value = ''
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '答案未通过服务器校验。'
  }
}

/** 在本轮回答处理完且必填资料齐备后预览开局资料。 */
function reviewSetup() {
  if (store.initializationBusy || !store.initializationTurn?.done) return
  error.value = ''
  step.value = 4
}

/** 创建正式游戏并打开首轮游玩页面。 */
async function createGame() {
  if (!store.initializationTurn?.done || busy.value || store.initializationBusy) return
  error.value = ''
  busy.value = true
  try {
    const created = await store.finalizeInitialization()
    if (created) await router.replace(`/game/${encodeURIComponent(created.gameId)}`)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '创建游戏失败。'
  } finally {
    busy.value = false
  }
}

/** 从确认页返回时保留访谈，返回规则选择时清空访谈。 */
function goBack() {
  if (busy.value || store.initializationBusy) return
  if (customMode.value) {
    exitCustomWorld()
    return
  }
  if (step.value === 4) {
    step.value = 3
    return
  }
  if (step.value === 2) { step.value = 1; return }
  if (step.value === 3) {
    step.value = 2
    store.reset()
  }
}

onMounted(() => { void loadCatalog() })
</script>

<template>
  <section class="onboarding" :class="{ 'onboarding-worldbuild': step === 1 && customMode }" aria-labelledby="onboarding-title">
    <main class="onboarding-card">
      <div class="onboarding-heading">
        <div>
          <p class="onboarding-kicker">NEW ADVENTURE</p>
          <h1 id="onboarding-title">创建新冒险</h1>
          <p>选择世界与规则，再和 GM 一起确认角色资料。</p>
        </div>
      </div>

      <div class="onboarding-steps" aria-label="创建进度">
        <button
          v-for="(name, index) in steps"
          :key="name"
          type="button"
          class="onboarding-step"
          :class="{ current: step === index + 1, complete: step > index + 1 }"
          :disabled="busy || worldbuild.busy || store.initializationBusy || step <= index + 1 || index > 1"
          @click="step = index + 1"
        >
          <span>{{ index + 1 }}</span>{{ name }}
        </button>
      </div>

      <div class="onboarding-pane">
        <template v-if="step === 1">
          <template v-if="customMode">
            <div class="worldbuild-heading">
              <div><h2>和 GM 一起构建世界</h2><p>描述你想玩的世界，也可以继续和 GM 修改草案。</p></div>
              <div class="worldbuild-actions">
                <button type="button" class="onboarding-secondary" :disabled="worldbuild.busy || busy" @click="exitCustomWorld">返回世界书</button>
                <button type="button" class="onboarding-secondary" :disabled="worldbuild.busy || busy" @click="restartWorldbuild">重新构建</button>
              </div>
            </div>
            <div class="worldbuild-tabs" aria-label="切换面板">
              <button type="button" class="onboarding-secondary" :aria-pressed="worldTab === 'draft'" @click="worldTab = 'draft'">世界草案</button>
              <button type="button" class="onboarding-secondary" :aria-pressed="worldTab === 'chat'" @click="worldTab = 'chat'">GM 对话</button>
            </div>
            <div class="worldbuild-layout" :data-tab="worldTab">
              <section class="worldbuild-panel worldbuild-draft" aria-labelledby="world-draft-heading">
                <header class="worldbuild-panel-header">
                  <h3 id="world-draft-heading">世界草案</h3>
                  <span class="worldbuild-status" :class="{ ready: worldbuild.turn?.done && !worldbuild.busy && !busy }" role="status">
                    {{ busy ? '正在确认世界类型并保存…' : worldbuild.busy ? '正在整理世界草案…' : worldbuild.turn?.done ? '可进入下一步' : '等待生成' }}
                  </span>
                </header>
                <article v-if="worldDraftHtml" class="worldbuild-document" v-html="worldDraftHtml"></article>
                <p v-else class="worldbuild-empty">通过验收的草案会显示在这里。</p>
              </section>
              <section class="worldbuild-panel worldbuild-conversation" aria-labelledby="world-chat-heading">
                <header class="worldbuild-panel-header"><h3 id="world-chat-heading">GM 对话</h3></header>
                <div ref="worldChat" class="onboarding-chat" role="log" :aria-busy="worldbuild.busy">
                  <p v-if="!worldbuild.history.length" class="worldbuild-empty">试着说说题材或核心冲突，例如「剑与魔法，魔物正在入侵边境」。</p>
                  <div v-for="message in worldbuild.history" :key="message.id" class="onboarding-message" :class="message.role">
                    <span class="message-role">{{ message.role === 'gm' ? 'GM' : '你' }}</span><p>{{ message.text }}</p>
                  </div>
                  <div v-if="worldbuild.busy" class="onboarding-message gm thinking"><span class="loader small" />正在整理世界草案…</div>
                </div>
                <form class="onboarding-composer worldbuild-composer" @submit.prevent="submitWorldAnswer">
                  <textarea v-model="worldAnswer" aria-label="给 GM 的消息" rows="2" :placeholder="worldbuild.turn?.done ? '告诉 GM 你想调整什么' : '描述你想玩的世界'" :disabled="!worldbuild.sessionId || worldbuild.busy || busy" maxlength="8000"></textarea>
                  <button type="submit" class="onboarding-primary" :disabled="!worldbuild.sessionId || worldbuild.busy || busy || !worldAnswer.trim()">发送</button>
                </form>
              </section>
            </div>
          </template>
          <template v-else>
          <div class="onboarding-field">
            <div class="onboarding-label">选择世界书</div>
            <div class="world-choice-grid">
              <button
                v-for="world in catalog?.worlds || []"
                :key="world.id"
                type="button"
                class="world-choice"
                :class="{ selected: selection.worldId === world.id }"
                @click="selection.worldId = world.id"
              >
                <span class="choice-check" aria-hidden="true">{{ selection.worldId === world.id ? '✓' : '' }}</span>
                <strong>{{ world.name }}</strong>
                <span>{{ world.description }}</span>
              </button>
              <p v-if="catalog && !catalog.worlds.length" class="onboarding-empty">当前没有可用的世界书。</p>
            </div>
          </div>
          <div class="onboarding-field custom-world-entry"><button type="button" class="onboarding-secondary" @click="startCustomWorld">＋ 自定义世界</button></div>
          </template>
        </template>

        <template v-else-if="step === 2">
          <div class="onboarding-field">
            <div class="onboarding-label">选择规则引擎</div>
            <div class="engine-choice-grid">
              <button
                type="button"
                class="engine-choice"
                :class="{ selected: selection.engineId === null }"
                @click="selection.engineId = null"
              >
                <strong>纯叙事</strong>
                <span>由 GM 推进故事，不启用额外规则系统。</span>
              </button>
              <button
                v-for="engine in catalog?.engines || []"
                :key="engine.id"
                type="button"
                class="engine-choice"
                :class="{ selected: selection.engineId === engine.id }"
                @click="selection.engineId = engine.id"
              >
                <strong>{{ engine.displayName }}</strong>
                <span>使用该引擎提供的规则与角色状态。</span>
              </button>
            </div>
          </div>
          <div class="selection-summary">
            <span>已选世界</span><strong>{{ selectedWorld?.name || '尚未选择' }}</strong>
            <span>叙事语言</span><strong>简体中文</strong>
          </div>
        </template>

        <template v-else-if="step === 3">
          <div class="setup-context">
            <div><span>世界</span><strong>{{ selectedWorld?.name }}</strong></div>
            <div><span>规则</span><strong>{{ selectedEngine?.displayName || '纯叙事' }}</strong></div>
          </div>
          <div ref="characterChat" class="onboarding-chat" aria-live="polite" :aria-busy="store.initializationBusy">
            <div v-for="message in store.initializationHistory" :key="message.id" class="onboarding-message" :class="message.role">
              <span class="message-role">{{ message.role === 'gm' ? 'GM' : '你' }}</span>
              <p>{{ message.text }}</p>
            </div>
            <div v-if="store.initializationBusy" class="onboarding-message gm thinking"><span class="loader small" />GM 正在思考…</div>
          </div>
          <div v-if="store.initializationTurn?.options?.length" class="onboarding-options">
            <button
              v-for="option in store.initializationTurn.options"
              :key="option"
              type="button"
              class="onboarding-option"
              :disabled="store.initializationBusy"
              @click="submitAnswer(option)"
            >{{ option }}</button>
          </div>
          <form v-if="store.initializationTurn" class="onboarding-composer" @submit.prevent="submitAnswer()">
            <input v-model="answer" aria-label="给 GM 发消息" placeholder="提问、补充或修改开局资料" :disabled="store.initializationBusy" />
            <button type="submit" class="onboarding-primary" :disabled="store.initializationBusy || !answer.trim()">{{ store.initializationBusy ? '发送中…' : '发送' }}</button>
          </form>
          <p v-if="store.initializationTurn?.done" class="onboarding-ready">必填资料已齐备，可以继续和 GM 交流，也可以进入下一步。</p>
        </template>

        <template v-else>
          <div class="review-intro"><span class="review-mark">✓</span><div><strong>检查开局资料</strong><p>确认后会创建游戏并进入第一轮。</p></div></div>
          <dl class="review-list">
            <div><dt>世界</dt><dd>{{ selectedWorld?.name || selection.worldId }}</dd></div>
            <div><dt>规则</dt><dd>{{ selectedEngine?.displayName || '纯叙事' }}</dd></div>
            <div v-for="([key, value]) in collectedEntries" :key="key"><dt>{{ fieldLabel(key) }}</dt><dd>{{ valueLabel(key, value) }}</dd></div>
          </dl>
          <p v-if="!collectedEntries.length" class="onboarding-empty">没有额外的角色字段；世界与规则信息已准备好。</p>
        </template>
      </div>

      <p v-if="error || store.error" class="onboarding-error" role="alert">{{ error || store.error }}</p>
      <footer class="onboarding-footer">
        <span>{{ step < 3 ? `第 ${step} / 4 步` : step === 3 ? '角色与开局资料' : '准备就绪' }}</span>
        <div class="footer-actions">
          <button type="button" class="onboarding-secondary" @click="router.push('/')">取消</button>
          <button v-if="step > 1" type="button" class="onboarding-secondary" :disabled="busy || store.initializationBusy" @click="goBack">{{ step === 4 ? '返回角色' : '上一步' }}</button>
          <template v-if="step === 1 && customMode">
            <button type="button" class="onboarding-primary" :disabled="busy || worldbuild.busy || !worldbuild.turn?.done" @click="finishCustomWorld">{{ busy ? '处理中…' : '下一步' }}</button>
          </template>
          <button v-else-if="step === 1" type="button" class="onboarding-primary" :disabled="!selection.worldId || !catalog" @click="step = 2">下一步</button>
          <button v-else-if="step === 2" type="button" class="onboarding-primary" :disabled="busy || !selection.worldId" @click="startCharacterSetup">{{ busy ? '准备中…' : '下一步' }}</button>
          <button v-else-if="step === 3" type="button" class="onboarding-primary" :disabled="store.initializationBusy || !store.initializationTurn?.done" @click="reviewSetup">下一步</button>
          <button v-else type="button" class="onboarding-primary" :disabled="busy || store.initializationBusy || !store.initializationTurn?.done" @click="createGame">{{ busy ? '创建中…' : '创建并进入' }}</button>
        </div>
      </footer>
    </main>
  </section>
</template>
