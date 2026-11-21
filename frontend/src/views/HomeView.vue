<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { listGames } from '../api/client'
const router = useRouter(); const error = ref(''); const busy = ref(false)
async function openSaves() { busy.value = true; error.value = ''; try { const saves = await listGames(); if (saves.length === 1) await router.push(`/game/${encodeURIComponent(saves[0].gameId)}`); else await router.push({ name: 'game', query: { phase: 'saves' } }) } catch (cause) { error.value = cause instanceof Error ? cause.message : '无法读取存档列表。' } finally { busy.value = false } }
</script>
<template><section class="home-screen" aria-labelledby="home-title"><div class="home-symbol" aria-hidden="true">✦</div><p class="eyebrow">AI GAME / STORY ENGINE</p><h1 id="home-title">AIgame</h1><p class="home-subtitle">从一个世界开始，和 GM 一起决定接下来会发生什么。</p><div class="home-actions"><button class="home-action primary-button" type="button" @click="router.push('/game')"><strong>开始游戏</strong><span>选择世界与规则，建立新故事</span></button><button class="home-action secondary-button" type="button" :disabled="busy" @click="openSaves"><strong>{{ busy ? '读取中…' : '加载存档' }}</strong><span>继续一局已经开始的冒险</span></button><button class="home-action quiet-button" type="button" @click="router.push('/settings')"><strong>设置</strong><span>配置外部模型 API key</span></button></div><p v-if="error" class="form-error" role="alert">{{ error }}</p></section></template>
