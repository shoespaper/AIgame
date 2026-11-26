<script setup lang="ts">
import { useRouter } from 'vue-router'
const router = useRouter()
</script>
<template><section class="home-screen" aria-labelledby="home-title"><div class="home-symbol" aria-hidden="true">✦</div><p class="eyebrow">AI GAME / STORY ENGINE</p><h1 id="home-title">AIgame</h1><p class="home-subtitle">从一个世界开始，和 GM 一起决定接下来会发生什么。</p><div class="home-actions"><button class="home-action primary-button" type="button" @click="router.push('/game')"><strong>开始游戏</strong><span>选择世界与规则，建立新故事</span></button><button class="home-action secondary-button" type="button" @click="router.push({ name: 'game', query: { phase: 'saves' } })"><strong>游戏存档</strong><span>继续冒险或删除存档</span></button><button class="home-action quiet-button" type="button" @click="router.push('/settings')"><strong>设置</strong><span>配置外部模型 API key</span></button></div></section></template>
