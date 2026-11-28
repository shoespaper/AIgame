<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import MarkdownIt from 'markdown-it'
import type { CheckedInitialization, CreationProgress, GameCatalog, GameSelection, InitializationField, InitializationFormValues, InitializationSession } from '../api/types'
import { ApiError, createInitializationSession, loadGameCatalog, validateInitialization } from '../api/client'
import InitializationFormField from './InitializationFormField.vue'
import NarrationRetryNotice from './NarrationRetryNotice.vue'
import { useGameStore } from '../stores/game'
import { useWorldbuildStore } from '../stores/worldbuild'

const router = useRouter()
const store = useGameStore()
const worldbuild = useWorldbuildStore()
const catalog = ref<GameCatalog | null>(null)
const step = ref(1)
const customMode = ref(false)
const worldChat = ref<HTMLElement | null>(null)
const worldAnswer = ref('')
const pendingWorldAnswer = ref('')
const worldAnswerField = ref<HTMLTextAreaElement | null>(null)
const worldTab = ref<'draft' | 'chat'>('chat')
const busy = ref(false)
const error = ref('')
const creationStatus = ref('')
const templateRetry = ref(false)
const selection = reactive<GameSelection>({ worldId: '', engineId: null })
const form = ref<InitializationSession | null>(null)
const formSelection = ref<GameSelection | null>(null)
const loadingForm = ref(false)
const profileInput = reactive<Record<string, string>>(Object.create(null))
const engineInput = ref<Record<string, string>>(Object.create(null))
const checked = ref<CheckedInitialization | null>(null)
let formRequest = 0
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
const reviewed = computed(() => checked.value?.setup)
const reviewedEngine = computed(() => form.value?.engineFields.flatMap(field => {
  const value = reviewed.value?.engineConfig[field.id]?.value
  if (value === undefined) return []
  const label = typeof value === 'boolean' ? (value ? '是' : '否') : field.optionLabels[String(value)] ?? String(value)
  return [{ id: field.id, label: field.label, value: label }]
}) ?? [])

/** 更新生成进度，并对每次失败排队显示约一秒的提示。
 * @param progress 服务端发送的阶段及尝试信息
 */
function showCreationProgress(progress: CreationProgress) {
  const unit = progress.phase === 'draft' || progress.phase === 'judge' ? '轮' : '次'
  const count = progress.attempt == null ? '' : ` · 第 ${progress.attempt}/${progress.limit} ${unit}`
  if (progress.failed) store.showRetryHint(progress.message + count)
  else creationStatus.value = progress.message + count
}

/** 将字段错误码转换为页面提示。
 * @param field 服务端声明的字段
 * @param code 校验错误码，字段正常时为空
 */
function fieldError(field: InitializationField, code?: string) {
  switch (code) {
    case undefined: return undefined
    case 'field_required': return field.options.length || field.type === 'BOOLEAN' ? '请选择' + field.label + '。' : '请填写' + field.label + '。'
    case 'field_type_invalid': return field.type === 'INTEGER' ? '请输入整数。' : '请输入有效的' + field.label + '。'
    case 'field_out_of_range': {
      if (field.minimum !== null && field.maximum !== null) return '请输入 ' + field.minimum + ' 至 ' + field.maximum + ' 之间的值。'
      if (field.minimum !== null) return '请输入不小于 ' + field.minimum + ' 的值。'
      return '请输入不大于 ' + field.maximum + ' 的值。'
    }
    case 'field_option_invalid': return '请从当前可用选项中选择。'
    default: return '当前值不符合配置要求，请检查后重试。'
  }
}

/** 按字段定义序列化当前输入，跳过未填写的值。
 * @param fields 服务端声明的有序字段
 * @param input 玩家输入的控件原始值
 */
function formValues(fields: InitializationField[], input: Record<string, string>): InitializationFormValues {
  const values: InitializationFormValues = Object.create(null)
  for (const field of fields) {
    const text = input[field.id]?.trim()
    if (!text) continue
    if (field.type === 'BOOLEAN') {
      values[field.id] = text === 'true'
    } else if (field.type === 'INTEGER' || field.type === 'DECIMAL') {
      const number = Number(text)
      const valid = field.type === 'INTEGER' ? Number.isSafeInteger(number) : Number.isFinite(number)
      values[field.id] = valid ? number : text
    } else {
      values[field.id] = text
    }
  }
  return values
}

/** 保留已填内容，并在字段修改时清除此字段上一次的错误。
 * @param group 通用角色资料或引擎配置分组
 * @param id 修改的字段标识
 * @param value 当前控件的原始值
 */
function setField(group: 'profile' | 'engine', id: string, value: string) {
  const input = group === 'profile' ? profileInput : engineInput.value
  input[id] = value
  if (checked.value) {
    const errors = group === 'profile' ? checked.value.profileErrors : checked.value.engineErrors
    delete errors[id]
  }
  error.value = ''
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
  const input = worldAnswer.value
  if (!input.trim() || worldbuild.busy || busy.value) return
  error.value = ''
  store.clearTurnMessages()
  creationStatus.value = '正在整理世界草案…'
  pendingWorldAnswer.value = input
  worldAnswer.value = ''
  const previousVersion = worldbuild.turn?.version
  try {
    await worldbuild.answer(input, showCreationProgress)
    if (worldbuild.turn?.version !== previousVersion) worldTab.value = 'draft'
  } catch (cause) {
    worldAnswer.value = input
    const message = cause instanceof Error ? cause.message : '世界草案处理失败，请重试。'
    error.value = worldbuild.turn?.done ? `${message} 上次通过验收的草案仍可继续。` : message
    await nextTick()
    worldAnswerField.value?.focus()
  } finally {
    pendingWorldAnswer.value = ''
    creationStatus.value = ''
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

/** 进入角色页并加载所选引擎的字段，丢弃过期的加载结果。 */
async function startCharacterSetup() {
  if (!selection.worldId) return
  error.value = ''
  store.clearMessages()
  const selected = { ...selection }
  const previous = formSelection.value
  if (previous && previous.engineId !== selected.engineId) engineInput.value = Object.create(null)
  step.value = 3
  if (form.value && previous?.worldId === selected.worldId && previous.engineId === selected.engineId) return

  checked.value = null
  form.value = null
  formSelection.value = selected
  const request = ++formRequest
  loadingForm.value = true
  try {
    const loaded = await createInitializationSession(selected)
    if (request === formRequest) form.value = loaded
  } catch (cause) {
    if (request === formRequest) error.value = cause instanceof Error ? cause.message : '无法加载角色表单。'
  } finally {
    if (request === formRequest) loadingForm.value = false
  }
}

/** 送整份表单给服务端校验，通过后显示服务端确认的资料。 */
async function reviewSetup() {
  const session = form.value
  if (!session || busy.value || loadingForm.value) return
  error.value = ''
  busy.value = true
  try {
    checked.value = await validateInitialization(session.id,
      formValues(session.profileFields, profileInput), formValues(session.engineFields, engineInput.value))
    if (checked.value.version !== null && checked.value.setup) {
      templateRetry.value = false
      step.value = 4
    } else {
      error.value = '请检查角色资料和引擎配置。'
    }
  } catch (cause) {
    if (cause instanceof ApiError && cause.code === 'SESSION_NOT_FOUND') form.value = null
    error.value = cause instanceof Error ? cause.message : '无法校验角色表单。'
  } finally {
    busy.value = false
  }
  if (step.value === 3 && checked.value) {
    await nextTick()
    document.querySelector<HTMLElement>('#character-form [aria-invalid="true"]')?.focus()
  }
}

/** 确认已校验的表单版本，创建正式游戏并进入游玩页。 */
async function createGame() {
  const session = form.value
  const version = checked.value?.version
  if (!session || version == null || busy.value) return
  error.value = ''
  store.clearMessages()
  store.clearTurnMessages()
  creationStatus.value = '正在确认开局…'
  busy.value = true
  try {
    const created = await store.finalizeInitialization(session.id, version, showCreationProgress)
    await router.replace('/game/' + encodeURIComponent(created.gameId))
  } catch (cause) {
    templateRetry.value = cause instanceof ApiError && cause.code === 'TEMPLATE_ATTEMPTS_EXHAUSTED'
    if (cause instanceof ApiError && cause.code === 'SESSION_NOT_FOUND') {
      form.value = null
      checked.value = null
      step.value = 3
    }
    error.value = cause instanceof Error ? cause.message : '创建游戏失败。'
  } finally {
    busy.value = false
    creationStatus.value = ''
  }
}

/** 返回前序步骤，同时停止使用正在加载的旧表单。
 * @param target 要返回的步骤号，默认为上一步
 */
function goBack(target = step.value - 1) {
  if (busy.value || worldbuild.busy) return
  error.value = ''
  store.clearMessages()
  if (step.value === 3 && loadingForm.value) {
    formRequest++
    loadingForm.value = false
  }
  checked.value = null
  step.value = target
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
          <p>选择世界与规则，填写角色资料后开始冒险。</p>
        </div>
      </div>

      <div class="onboarding-steps" aria-label="创建进度">
        <button
          v-for="(name, index) in steps"
          :key="name"
          type="button"
          class="onboarding-step"
          :class="{ current: step === index + 1, complete: step > index + 1 }"
          :disabled="busy || worldbuild.busy || step <= index + 1 || index > 1"
          @click="goBack(index + 1)"
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
                    {{ busy ? '正在确认世界类型并保存…' : worldbuild.busy ? creationStatus || '正在整理世界草案…' : worldbuild.turn?.done ? '可进入下一步' : '等待生成' }}
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
                  <div v-if="pendingWorldAnswer" class="onboarding-message player">
                    <span class="message-role">你</span><p>{{ pendingWorldAnswer }}</p>
                  </div>
                  <div v-if="worldbuild.busy" class="onboarding-message gm thinking"><span class="loader small" />{{ creationStatus || '正在整理世界草案…' }}</div>
                </div>
                <NarrationRetryNotice :hint="store.retryHint" message="" :can-resume="false" />
                <form class="worldbuild-composer" @submit.prevent="submitWorldAnswer">
                  <textarea ref="worldAnswerField" v-model="worldAnswer" aria-label="给 GM 的消息" rows="2" :placeholder="worldbuild.turn?.done ? '告诉 GM 你想调整什么' : '描述你想玩的世界'" :disabled="!worldbuild.sessionId || worldbuild.busy || busy" maxlength="8000"></textarea>
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
          <p v-if="loadingForm" class="onboarding-empty" role="status">正在加载角色表单…</p>
          <button v-else-if="!form" type="button" class="onboarding-secondary" @click="startCharacterSetup">重新加载</button>
          <form v-else id="character-form" class="character-form" novalidate @submit.prevent="reviewSetup">
            <fieldset class="character-section" :disabled="busy" aria-labelledby="character-profile-title">
              <div class="character-section-heading">
                <h2 id="character-profile-title">角色基本资料</h2><span class="character-hint">* 为必填项</span>
              </div>
              <div class="character-profile-fields">
                <InitializationFormField
                  v-for="field in form.profileFields" :key="field.id" group="profile" :field="field"
                  :value="profileInput[field.id]" :error="fieldError(field, checked?.profileErrors[field.id])"
                  @change="setField('profile', field.id, $event)"
                />
              </div>
            </fieldset>
            <fieldset v-if="selectedEngine" class="character-section" :disabled="busy" aria-labelledby="character-engine-title">
              <div class="character-section-heading">
                <h2 id="character-engine-title">引擎配置</h2><span class="character-hint">{{ selectedEngine.displayName }}</span>
              </div>
              <div v-if="form.engineFields.length" class="character-engine-fields">
                <InitializationFormField
                  v-for="field in form.engineFields" :key="field.id" group="engine" :field="field"
                  :value="engineInput[field.id]" :error="fieldError(field, checked?.engineErrors[field.id])"
                  @change="setField('engine', field.id, $event)"
                />
              </div>
              <p v-else class="onboarding-empty">当前引擎无需额外配置。</p>
            </fieldset>
          </form>
        </template>

        <template v-else>
          <div class="review-intro"><span class="review-mark">✓</span><div><strong>检查开局资料</strong><p>确认后会创建游戏并进入第一轮。</p></div></div>
          <dl class="review-list">
            <div><dt>世界</dt><dd>{{ selectedWorld?.name || selection.worldId }}</dd></div>
            <div><dt>规则</dt><dd>{{ selectedEngine?.displayName || '纯叙事' }}</dd></div>
            <template v-if="reviewed">
              <div><dt>姓名</dt><dd>{{ reviewed.character.name }}</dd></div>
              <div><dt>年龄</dt><dd>{{ reviewed.character.age }} 岁</dd></div>
              <div><dt>性别</dt><dd>{{ reviewed.character.gender }}</dd></div>
              <div><dt>角色背景</dt><dd class="character-review-background">{{ reviewed.character.background || '未填写' }}</dd></div>
            </template>
            <div v-for="field in reviewedEngine" :key="field.id"><dt>{{ field.label }}</dt><dd>{{ field.value }}</dd></div>
          </dl>
        </template>
      </div>

      <p v-if="error || store.error" class="onboarding-error" role="alert">{{ error || store.error }}</p>
      <NarrationRetryNotice v-if="step === 4" :hint="store.retryHint" message="" :can-resume="false" />
      <footer class="onboarding-footer">
        <span role="status">{{ step < 3 ? `第 ${step} / 4 步` : step === 3 ? '角色与开局资料' : creationStatus || (templateRetry ? '等待模板生成' : '准备就绪') }}</span>
        <div class="footer-actions">
          <button type="button" class="onboarding-secondary" @click="router.push('/')">取消</button>
          <button v-if="step > 1" type="button" class="onboarding-secondary" :disabled="busy" @click="goBack()">{{ step === 4 ? '返回角色' : '上一步' }}</button>
          <template v-if="step === 1 && customMode">
            <button type="button" class="onboarding-primary" :disabled="busy || worldbuild.busy || !worldbuild.turn?.done" @click="finishCustomWorld">{{ busy ? '处理中…' : '下一步' }}</button>
          </template>
          <button v-else-if="step === 1" type="button" class="onboarding-primary" :disabled="!selection.worldId || !catalog" @click="step = 2">下一步</button>
          <button v-else-if="step === 2" type="button" class="onboarding-primary" :disabled="busy || !selection.worldId" @click="startCharacterSetup">{{ busy ? '准备中…' : '下一步' }}</button>
          <button v-else-if="step === 3" type="submit" form="character-form" class="onboarding-primary" :disabled="busy || !form || loadingForm">{{ busy ? '校验中…' : '下一步' }}</button>
          <button v-else type="button" class="onboarding-primary" :disabled="busy || checked?.version == null" @click="createGame">{{ busy ? '创建中…' : templateRetry ? '重试生成模板' : '创建并进入' }}</button>
        </div>
      </footer>
    </main>
  </section>
</template>
