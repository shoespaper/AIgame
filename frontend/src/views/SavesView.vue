<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { deleteGame, listGames, loadGameCatalog } from '../api/client'
import type { SaveMetadata } from '../api/types'
import { useGameStore } from '../stores/game'

const router = useRouter()
const store = useGameStore()
const saves = ref<SaveMetadata[]>([])
const worldNames = ref(new Map<string, string>())
const dateFormat = new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short', hour12: false })
const listing = ref(true)
const listError = ref('')
const loadingId = ref('')
const loadError = ref<{ gameId: string; message: string } | null>(null)
const notice = ref('')
const target = ref<SaveMetadata | null>(null)
const deleting = ref(false)
const deleteError = ref('')
const busy = computed(() => !!loadingId.value || !!target.value || store.loading || store.saving || store.acting)

const heading = ref<HTMLElement | null>(null)
const list = ref<HTMLUListElement | null>(null)
const dialog = ref<HTMLDialogElement | null>(null)
const cancelButton = ref<HTMLButtonElement | null>(null)
const confirmButton = ref<HTMLButtonElement | null>(null)
let deleteTrigger: HTMLElement | null = null
let deletedIndex: number | null = null
let active = true

/** 返回列表用的世界名称，目录中缺少该世界时展示其标识。
 * @param save 一份存档的摘要
 */
function worldName(save: SaveMetadata): string {
  return worldNames.value.get(save.worldId) || save.worldId
}

/** 按浏览器的本地时区格式化存档时间。
 * @param updatedAt 服务端返回的 ISO 时间，空字符串表示未知
 */
function savedTime(updatedAt: string): string {
  const time = Date.parse(updatedAt)
  return Number.isFinite(time) ? `保存于 ${dateFormat.format(time)}` : '保存时间未知'
}

/** 读取服务端存档列表，并区分加载失败与列表为空。 */
async function refreshSaves() {
  listing.value = true
  listError.value = ''
  try {
    saves.value = await listGames()
  } catch (cause) {
    listError.value = cause instanceof Error ? cause.message : '请稍后重试。'
  } finally {
    listing.value = false
  }
}

/** 载入所选存档，服务端读取成功后才进入游玩页。
 * @param gameId 待加载的游戏存档标识
 * @param event 点击事件，用于失败后恢复按钮焦点
 */
async function loadSave(gameId: string, event: MouseEvent) {
  if (busy.value) return
  const trigger = event.currentTarget
  loadingId.value = gameId
  loadError.value = null
  notice.value = ''
  try {
    const game = await store.load(gameId)
    if (active && game) await router.push({ name: 'saved-game', params: { gameId } })
  } catch (cause) {
    loadError.value = { gameId, message: cause instanceof Error ? cause.message : '请稍后重试。' }
  } finally {
    loadingId.value = ''
    await nextTick()
    if (active && trigger instanceof HTMLElement) trigger.focus()
  }
}

/** 显示包含目标存档信息的删除确认框。
 * @param save 待确认删除的存档摘要
 * @param event 点击事件，用于关闭弹窗后恢复焦点
 */
async function askDelete(save: SaveMetadata, event: MouseEvent) {
  if (busy.value || !dialog.value) return
  deleteTrigger = event.currentTarget instanceof HTMLElement ? event.currentTarget : null
  target.value = save
  deleteError.value = ''
  await nextTick()
  dialog.value?.showModal()
  cancelButton.value?.focus()
}

/** 关闭删除确认框，用于取消或确认成功后的收尾。 */
function closeDialog() {
  dialog.value?.close()
}

/** 在删除请求进行时拦截 Escape 关闭，等待服务端结果。
 * @param event 原生弹窗取消事件
 */
function handleCancel(event: Event) {
  if (deleting.value) event.preventDefault()
}

/** 清除弹窗状态，将焦点还给删除按钮或移到相邻的存档。 */
async function handleClose() {
  const index = deletedIndex
  const trigger = deleteTrigger
  target.value = null
  deletedIndex = null
  deleteTrigger = null
  await nextTick()
  if (index === null) {
    trigger?.focus()
    return
  }
  const neighborIndex = Math.min(index, saves.value.length - 1)
  const neighbor = list.value?.querySelectorAll<HTMLButtonElement>('[data-save-load]')[neighborIndex]
  const nextFocus = neighbor ?? heading.value
  nextFocus?.focus()
}

/** 删除目标存档，成功后更新列表和本地缓存，失败时保留弹窗供重试。 */
async function confirmDelete() {
  const save = target.value
  if (!save || deleting.value) return
  deleting.value = true
  deleteError.value = ''
  notice.value = ''
  try {
    await deleteGame(save.gameId)
    store.forgetSave(save.gameId)
    deletedIndex = saves.value.findIndex(({ gameId }) => gameId === save.gameId)
    saves.value = saves.value.filter(({ gameId }) => gameId !== save.gameId)
    if (loadError.value?.gameId === save.gameId) loadError.value = null
    notice.value = `已删除「${worldName(save)}」存档 ${save.gameId}。`
    closeDialog()
  } catch (cause) {
    deleteError.value = cause instanceof Error ? cause.message : '请稍后重试。'
  } finally {
    deleting.value = false
    if (deleteError.value) {
      await nextTick()
      confirmButton.value?.focus()
    }
  }
}

onMounted(() => {
  void refreshSaves()
  void loadGameCatalog().then((catalog) => {
    worldNames.value = new Map<string, string>(catalog.worlds.map(({ id, name }) => [id, name]))
  }).catch(() => { /* 目录不可用时仍可用世界标识加载或删除存档。 */ })
})
onBeforeUnmount(() => { active = false })
</script>

<template>
  <section class="page-content game-flow archive" aria-labelledby="archive-title">
    <div class="section-heading">
      <div>
        <h1 id="archive-title" ref="heading" tabindex="-1">游戏存档</h1>
        <p class="lede">继续上次的冒险，或删除不再需要的存档。</p>
      </div>
      <button class="quiet-button" type="button" @click="router.push('/')">返回首页</button>
    </div>

    <p v-if="listing" class="archive-status" role="status">正在读取存档列表…</p>
    <div v-else-if="listError" class="archive-empty">
      <p class="form-error" role="alert">无法读取存档列表。{{ listError }}</p>
      <button class="secondary-button" type="button" @click="refreshSaves">重新读取</button>
    </div>
    <template v-else>
      <div class="archive-list-heading">
        <h2 id="archive-list-title">全部存档 <small>· {{ saves.length }} 份</small></h2>
        <small v-if="saves.length">最近保存优先</small>
      </div>
      <ul v-if="saves.length" ref="list" class="save-list archive-list" aria-labelledby="archive-list-title">
        <li v-for="save in saves" :key="save.gameId" class="save-row archive-row">
          <div>
            <div class="archive-name">
              <h3>{{ worldName(save) }}</h3>
              <span class="engine-label">{{ save.engineId || '纯叙事' }}</span>
            </div>
            <div class="archive-progress">
              <span>第 {{ save.day }} 天</span>
              <span>版本 {{ save.revision }}</span>
              <span>{{ savedTime(save.updatedAt) }}</span>
            </div>
            <div class="archive-id">{{ save.gameId }}</div>
          </div>
          <div class="archive-actions">
            <button class="secondary-button archive-load" type="button" data-save-load :disabled="busy"
                    :aria-label="`加载 ${worldName(save)} ${save.gameId}`" @click="loadSave(save.gameId, $event)">
              {{ loadingId === save.gameId ? '加载中…' : '加载' }}
            </button>
            <button class="quiet-button archive-delete" type="button" :disabled="busy"
                    :aria-label="`删除 ${worldName(save)} ${save.gameId}`" @click="askDelete(save, $event)">
              删除
            </button>
          </div>
          <p v-if="loadError?.gameId === save.gameId" class="form-error archive-row-error" role="alert">
            无法加载存档。{{ loadError.message }}
          </p>
        </li>
      </ul>
      <div v-else class="archive-empty">
        <strong>还没有游戏存档</strong>
        <p>保存一次冒险后，可在这里继续游玩。</p>
      </div>
      <p class="success-message archive-notice" role="status">{{ notice }}</p>
    </template>

    <dialog ref="dialog" class="archive-dialog" aria-labelledby="delete-save-title"
            aria-describedby="delete-save-description delete-save-target" @cancel="handleCancel" @close="handleClose">
      <h2 id="delete-save-title">删除这份存档？</h2>
      <p id="delete-save-description">此操作会永久删除这份存档及其游戏进度，无法恢复。</p>
      <div v-if="target" id="delete-save-target" class="archive-target">
        <strong>{{ worldName(target) }} · 第 {{ target.day }} 天 · 版本 {{ target.revision }}</strong>
        <div class="archive-id">{{ target.gameId }}</div>
      </div>
      <p v-if="deleteError" class="form-error" role="alert">无法删除存档。{{ deleteError }}</p>
      <div class="archive-actions">
        <button ref="cancelButton" class="secondary-button" type="button" autofocus :disabled="deleting" @click="closeDialog">取消</button>
        <button ref="confirmButton" class="secondary-button archive-confirm" type="button" :disabled="deleting" @click="confirmDelete">
          {{ deleting ? '删除中…' : '确认删除' }}
        </button>
      </div>
    </dialog>
  </section>
</template>

<style scoped>
.archive h1 { margin-bottom: 10px; }
.archive .section-heading { margin-bottom: 30px; }
.archive-status, .archive-list-heading small, .archive-id { color: var(--muted); }
.archive-list-heading { display: flex; justify-content: space-between; align-items: baseline; gap: 12px; margin-bottom: 12px; }
.archive-list-heading h2 { margin: 0; font-size: 15px; }
.archive-list-heading small { font-size: 12px; font-weight: 400; }
.archive-list { padding: 0; margin: 0; list-style: none; }
.archive-row { grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: 14px 24px; padding: 18px 20px; }
.archive-row:hover, .archive-row:focus-within { border-color: #53615b; }
.archive-name, .archive-progress, .archive-actions { display: flex; align-items: center; flex-wrap: wrap; }
.archive-name { gap: 8px 10px; }
.archive-name h3 { margin: 0; font-size: 17px; overflow-wrap: anywhere; }
.archive-name .engine-label { color: var(--teal); border: 1px solid #36534f; border-radius: 4px; padding: 2px 7px; font-size: 11px; }
.archive-progress { gap: 4px 0; margin-top: 8px; }
.archive-progress > :not(:last-child)::after { content: '·'; margin: 0 9px; }
.archive-id { margin-top: 5px; font: 11px/1.5 ui-monospace, monospace; overflow-wrap: anywhere; }
.archive-actions { justify-content: flex-end; gap: 8px; }
.archive-actions button { min-height: 42px; }
.archive-load { color: var(--accent-strong); background: #29271f; border-color: #796943; }
.archive-load:hover:not(:disabled) { background: #3a3222; border-color: var(--accent); }
.archive-delete { color: #f1a698; }
.archive-delete:hover:not(:disabled) { color: #ffbbad; background: #2b2020; border-color: #6c4741; }
.archive-row-error { grid-column: 1 / -1; margin: 0; }
.archive-empty { border: 1px dashed var(--line); border-radius: var(--radius); padding: 30px 18px; text-align: center; }
.archive-empty p { margin: 8px 0 0; font-size: 13px; }
.archive-empty p:not(.form-error) { color: var(--muted); }
.archive-empty button { margin-top: 14px; }
.archive-notice:empty { margin: 0; }
.archive-dialog { width: min(430px, calc(100% - 32px)); padding: 24px; background: var(--surface); color: var(--text); border: 1px solid var(--line); border-radius: var(--radius); box-shadow: var(--shadow); }
.archive-dialog::backdrop { background: #05090ccc; }
.archive-dialog h2 { margin: 0; font-size: 22px; }
.archive-dialog p { margin: 10px 0 0; line-height: 1.65; }
.archive-dialog p:not(.form-error) { color: var(--muted); font-size: 14px; }
.archive-dialog .archive-actions { margin-top: 22px; }
.archive-target { margin-top: 16px; padding: 12px; border-radius: 6px; background: var(--surface-soft); font-size: 14px; overflow-wrap: anywhere; }
.archive-confirm { color: #281512; background: #e99c8c; border-color: #e99c8c; }
.archive-confirm:hover:not(:disabled) { color: #281512; background: #ffbbad; border-color: #ffbbad; }
.archive button:focus-visible { outline: 2px solid var(--accent-strong); outline-offset: 3px; }

@media (max-width: 560px) {
  .archive-row { grid-template-columns: 1fr; gap: 10px; padding: 14px; }
  .archive-row .archive-actions { padding-top: 9px; border-top: 1px solid var(--line); }
}
</style>
