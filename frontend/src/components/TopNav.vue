<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { downloadLogs } from '../api/client'

const route = useRoute()
const router = useRouter()
const settingsOpen = ref(false)
const exporting = ref(false)
const exportError = ref('')
const settingsGroup = ref<HTMLElement | null>(null)
const settingsToggle = ref<HTMLButtonElement | null>(null)

/** 顶部主导航项，带 phase 的项通过查询参数区分同一路径下的不同视图。 */
interface NavItem { label: string; path: string; phase?: string }

const items: NavItem[] = [
  { label: '总览', path: '/' },
  { label: '创建新冒险', path: '/game', phase: 'selection' },
  { label: '世界书', path: '/worldbooks' },
  { label: '存档', path: '/game', phase: 'saves' },
]

/** 判断导航项是否对应当前页面。
 * @param item 导航项
 */
function isActive(item: NavItem): boolean {
  if (item.phase !== undefined) {
    const phase = route.query.phase ?? 'selection'
    return route.path === item.path && phase === item.phase
  }
  return route.path === item.path
}

/** 跳转到导航项。
 * @param item 导航项
 */
function go(item: NavItem) {
  if (item.phase !== undefined) router.push({ path: item.path, query: item.phase === 'saves' ? { phase: 'saves' } : {} })
  else router.push(item.path)
}

/** 点击设置区域外时收起子项。
 * @param event 页面上的点击事件
 */
function closeOnOutsideClick(event: MouseEvent) {
  if (event.target instanceof Node && !settingsGroup.value?.contains(event.target)) settingsOpen.value = false
}

/** 按 Escape 收起设置子项，并把键盘焦点移回触发按钮。
 * @param event 页面上的键盘事件
 */
function closeOnEscape(event: KeyboardEvent) {
  if (!settingsOpen.value || event.key !== 'Escape') return
  settingsOpen.value = false
  settingsToggle.value?.focus()
}

/** 收起菜单并进入现有模型配置页面。 */
function openModelSettings() {
  settingsOpen.value = false
  void router.push('/settings')
}

/** 在当前页面发起日志下载，并在失败时允许重试。 */
async function exportLogs() {
  if (exporting.value) return
  exporting.value = true
  exportError.value = ''
  try {
    await downloadLogs()
  } catch (cause) {
    exportError.value = cause instanceof Error ? cause.message : '导出日志失败，请稍后重试。'
  } finally {
    exporting.value = false
  }
}

watch(() => route.fullPath, () => {
  settingsOpen.value = false
  exportError.value = ''
})
onMounted(() => {
  document.addEventListener('click', closeOnOutsideClick)
  document.addEventListener('keydown', closeOnEscape)
})
onBeforeUnmount(() => {
  document.removeEventListener('click', closeOnOutsideClick)
  document.removeEventListener('keydown', closeOnEscape)
})
</script>

<template>
  <header class="onboarding-nav top-nav">
    <button class="onboarding-brand" type="button" aria-label="返回首页" @click="router.push('/')">AIgame</button>
    <nav aria-label="主导航">
      <template v-for="item in items" :key="item.label">
        <span v-if="isActive(item)" class="active">{{ item.label }}</span>
        <button v-else type="button" @click="go(item)">{{ item.label }}</button>
      </template>
      <div ref="settingsGroup" class="nav-settings">
        <button ref="settingsToggle" type="button" :class="{ active: route.path === '/settings' }"
                :aria-expanded="settingsOpen" aria-controls="settings-options" @click="settingsOpen = !settingsOpen">
          设置
        </button>
        <div v-show="settingsOpen" id="settings-options" class="nav-settings-options">
          <button type="button" @click="openModelSettings">模型配置</button>
          <button type="button" :disabled="exporting" @click="exportLogs">
            {{ exporting ? '正在导出…' : '导出日志' }}
          </button>
        </div>
      </div>
    </nav>
  </header>
  <p v-if="exportError" class="form-error nav-export-error" role="alert">{{ exportError }}</p>
</template>
