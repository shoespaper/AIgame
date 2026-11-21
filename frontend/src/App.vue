<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterView } from 'vue-router'
import { health } from './api/client'
const serverState = ref<'checking' | 'online' | 'offline'>('checking')
async function checkHealth() {
  serverState.value = 'checking'
  try { serverState.value = (await health()).status === 'UP' ? 'online' : 'offline' } catch { serverState.value = 'offline' }
}
onMounted(() => { void checkHealth() })
</script>

<template><div class="app-shell"><main class="page-shell"><RouterView /></main><div class="connection-pill" :class="serverState"><i aria-hidden="true" />{{ serverState === 'online' ? '服务在线' : serverState === 'offline' ? '服务离线' : '连接检查中' }}</div></div></template>
