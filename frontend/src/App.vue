<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink, RouterView, useRoute } from 'vue-router'
import { health } from './api/client'
import { useGameStore } from './stores/game'

const game = useGameStore()
const route = useRoute()
const serverState = ref<'checking' | 'online' | 'offline'>('checking')
async function checkHealth() {
  serverState.value = 'checking'
  try { serverState.value = (await health()).status === 'UP' ? 'online' : 'offline' } catch { serverState.value = 'offline' }
}
onMounted(() => { void checkHealth() })
</script>

<template>
  <div class="app-shell">
    <header class="topbar">
      <RouterLink class="brand" to="/" aria-label="返回 AIgame 首页"><span class="brand-mark">A</span><span><strong>AIgame</strong><small>灰境远征</small></span></RouterLink>
      <nav class="topnav" aria-label="主导航"><RouterLink to="/" :class="{ active: route.name === 'home' }">游戏桌</RouterLink><RouterLink to="/create" :class="{ active: route.name === 'create' }">创建远征</RouterLink><RouterLink v-if="game.currentGameId" :to="`/play/${encodeURIComponent(game.currentGameId)}`" :class="{ active: route.name === 'play' }">继续游戏</RouterLink></nav>
      <div class="topbar-meta"><span class="server-status" :class="serverState"><i aria-hidden="true" />{{ serverState === 'online' ? '服务器在线' : serverState === 'offline' ? '服务器离线' : '检查连接' }}</span><button class="icon-button" type="button" title="重新检查服务器" aria-label="重新检查服务器" @click="checkHealth">↻</button></div>
    </header>
    <main class="page-shell"><RouterView /></main>
  </div>
</template>
