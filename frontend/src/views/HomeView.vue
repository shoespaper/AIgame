<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useGameStore } from '../stores/game'

const router = useRouter(); const game = useGameStore(); const gameId = ref(game.currentGameId); const error = ref(''); const busy = ref(false)
const resumeId = computed(() => game.game?.gameId || game.currentGameId)
async function resume() {
  const id = gameId.value.trim()
  if (!id) { error.value = '请输入游戏 ID。'; return }
  busy.value = true; error.value = ''
  try { await game.load(id); await router.push(`/play/${encodeURIComponent(id)}`) }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '无法读取这个存档。' }
  finally { busy.value = false }
}
</script>

<template>
  <section class="home-page page-content">
    <div class="home-intro"><p class="eyebrow">ISEKAI GREY · WORLD 1.0</p><h1>灰境远征</h1><p class="lede">两个王国已经陷落。边境仍在集结冒险者，寻找下一道防线。</p><div class="intro-actions"><button class="primary-button" type="button" @click="router.push('/create')">创建角色 <span>→</span></button></div></div>
    <section class="home-grid" aria-label="游戏入口">
      <article class="entry-panel create-panel"><div class="panel-kicker">NEW EXPEDITION</div><h2>从边境启程</h2><p>为旅人选择职业与出身，决定故事开始时的地点和处境。</p><button class="secondary-button" type="button" @click="router.push('/create')">开始创建 <span>↗</span></button></article>
      <article class="entry-panel load-panel"><div class="panel-kicker">CONTINUE</div><h2>继续远征</h2><label class="field-label" for="resume-id">存档 ID</label><div class="inline-form"><input id="resume-id" v-model="gameId" placeholder="输入存档 ID" autocomplete="off" @keyup.enter="resume" /><button class="secondary-button compact" type="button" :disabled="busy" @click="resume">{{ busy ? '读取中' : '读取' }}</button></div><p v-if="resumeId" class="saved-hint">最近使用：<strong>{{ resumeId }}</strong></p><p v-if="error" class="form-error" role="alert">{{ error }}</p></article>
    </section>
  </section>
</template>
