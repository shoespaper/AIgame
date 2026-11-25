<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { RouterView, useRoute } from 'vue-router'
import TopNav from './components/TopNav.vue'
import { health, lastFailedRequestId } from './api/client'

const route = useRoute()
const serverState = ref<'checking' | 'online' | 'offline'>('checking')
const copyStatus = ref('')
const requestIdField = ref<HTMLInputElement | null>(null)

/** 检查后端健康状态并更新连接提示。 */
async function checkHealth() {
  serverState.value = 'checking'
  try { serverState.value = (await health()).status === 'UP' ? 'online' : 'offline' } catch { serverState.value = 'offline' }
}

/** 复制最近 API 错误的请求 ID，剪贴板不可用时选中文本供手动复制。 */
async function copyRequestId() {
  const requestId = lastFailedRequestId.value
  if (!requestId) return
  try {
    await navigator.clipboard.writeText(requestId)
    if (lastFailedRequestId.value === requestId) copyStatus.value = '已复制'
  } catch {
    if (lastFailedRequestId.value !== requestId) return
    requestIdField.value?.focus()
    requestIdField.value?.select()
    copyStatus.value = '请手动复制'
  }
}

watch(lastFailedRequestId, () => { copyStatus.value = '' })
watch(() => route.fullPath, () => { lastFailedRequestId.value = null })
onMounted(() => { void checkHealth() })
</script>

<template>
  <div class="app-shell">
    <main class="page-shell">
      <TopNav />
      <div v-if="lastFailedRequestId" class="request-diagnostic">
        <label for="failed-request-id">请求 ID</label>
        <input id="failed-request-id" ref="requestIdField" :value="lastFailedRequestId" readonly />
        <button type="button" class="quiet-button" @click="copyRequestId">复制</button>
        <button type="button" class="quiet-button" @click="lastFailedRequestId = null">关闭</button>
        <span role="status">{{ copyStatus }}</span>
      </div>
      <RouterView />
    </main>
    <div class="connection-pill" :class="serverState">
      <i aria-hidden="true" />{{ serverState === 'online' ? '服务在线' : serverState === 'offline' ? '服务离线' : '连接检查中' }}
    </div>
  </div>
</template>
