<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { ActionType } from '../api/client'
import { useGameStore } from '../stores/game'

const route = useRoute(); const router = useRouter(); const game = useGameStore(); const loadError = ref('')
const actionLabels: Record<ActionType, { title: string; detail: string }> = { rest: { title: '休息', detail: '恢复少量生命与精神' }, consume_ration: { title: '消耗口粮', detail: '消耗 1 份口粮' }, use_potion: { title: '使用药水', detail: '恢复生命值' }, advance_day: { title: '推进一天', detail: '消耗口粮并推进世界时间' } }
const archetypeNames: Record<string, string> = { swordsman: '剑士', mage: '法师', ranger: '游侠', alchemist: '炼金术士' }
const difficultyNames: Record<string, string> = { story: '剧情', adventure: '冒险', hard: '艰难' }
const regionNames: Record<string, string> = { forest: '密林', plains: '开阔草原', mountain: '山脉地带', dungeon: '地窟', swamp: '沼泽', ruins: '废墟', cavern: '洞窟群', town: '城镇', corrupted_land: '侵蚀之地', volcanic: '火山带' }
const actionTypes: ActionType[] = ['rest', 'consume_ration', 'use_potion', 'advance_day']
const state = computed(() => game.game?.rulesetState); const world = computed(() => game.game?.world)
function idFromRoute(): string { return String(route.params.gameId || '') }
async function load() { const id = idFromRoute(); if (!id) { await router.replace('/'); return }; loadError.value = ''; if (game.game?.gameId === id) return; try { await game.load(id) } catch (cause) { loadError.value = cause instanceof Error ? cause.message : '无法加载游戏。' } }
async function action(type: ActionType) { try { await game.act(type) } catch { /* store exposes the user-facing error */ } }
async function save() { try { await game.save() } catch { /* store exposes the user-facing error */ } }
function actionDisabled(type: ActionType): boolean { if (!state.value || game.acting) return true; if (state.value.hpCurrent <= 0) return true; if (type === 'consume_ration' || type === 'advance_day') return state.value.resources.rations <= 0; if (type === 'use_potion') return state.value.resources.potions <= 0 || state.value.hpCurrent >= state.value.hpMax; return false }
watch(() => route.params.gameId, () => { void load() }); onMounted(() => { void load() })
</script>

<template>
  <section v-if="game.loading && !game.game" class="loading-state"><span class="loader" />正在读取游戏状态…</section>
  <section v-else-if="loadError" class="error-state page-content"><p class="eyebrow">LOAD FAILED</p><h1>这局远征暂时无法读取</h1><p class="form-error" role="alert">{{ loadError }}</p><div><button class="primary-button" type="button" @click="load">重试</button><button class="quiet-button" type="button" @click="router.push('/')">返回游戏桌</button></div></section>
  <section v-else-if="game.game && world" class="play-page">
    <div class="play-header"><div><p class="eyebrow">{{ game.game.rulesetId.toUpperCase() }} / REVISION {{ game.game.revision }}</p><h1>{{ world.setting || '未命名世界' }}</h1><p>{{ world.settingDescription || '世界正在等待你的第一个选择。' }}</p></div><div class="play-actions"><button class="quiet-button" type="button" :disabled="game.loading" @click="load">重新读取</button><button class="secondary-button" type="button" :disabled="game.saving" @click="save">{{ game.saving ? '保存中…' : '保存游戏' }}</button></div></div>
    <div v-if="game.error || game.notice" class="message-bar" :class="{ error: game.error }" role="status"><span>{{ game.error || game.notice }}</span><button type="button" aria-label="关闭提示" @click="game.clearMessages()">×</button></div>
    <div class="play-layout">
      <main class="story-column">
        <div class="story-heading"><div><span class="panel-kicker">CURRENT SESSION</span><h2>本次操作</h2></div><span class="revision-badge">第 {{ world.day }} 天 · {{ world.time || '未知时刻' }}</span></div>
        <div class="timeline" aria-live="polite"><article v-for="item in game.events" :key="item.id" class="timeline-item" :class="item.kind"><div class="timeline-marker" aria-hidden="true" /><div class="timeline-copy"><div class="timeline-meta"><strong>{{ item.title }}</strong><time>{{ item.time }}</time></div><p>{{ item.detail }}</p></div></article><div v-if="!game.events.length" class="empty-timeline">还没有行动记录。选择右侧的行动，开始这场远征。</div></div>
        <div class="action-composer"><div class="composer-heading"><div><span class="panel-kicker">AP ACTIONS</span><h2>你要怎么做？</h2></div><span v-if="game.acting" class="processing-label"><span class="loader small" />结算中</span></div><div class="action-grid"><button v-for="type in actionTypes" :key="type" class="action-button" type="button" :disabled="actionDisabled(type)" @click="action(type)"><strong>{{ actionLabels[type].title }}</strong><span>{{ actionLabels[type].detail }}</span></button></div><p class="composer-note">每项行动都会影响角色状态或世界日期。</p></div>
      </main>
      <aside class="status-column">
        <section v-if="state" class="status-panel character-panel"><div class="panel-heading compact-heading"><span class="panel-kicker">CHARACTER</span><h2>{{ state.characterName }}</h2><p>{{ archetypeNames[state.archetype] || state.archetype }} · {{ difficultyNames[state.difficulty] || state.difficulty }}</p></div><div class="vital-bars"><div><div class="bar-label"><span>生命</span><strong>{{ state.hpCurrent }} / {{ state.hpMax }}</strong></div><div class="bar"><i class="hp" :style="{ width: `${Math.max(0, Math.min(100, state.hpCurrent / state.hpMax * 100))}%` }" /></div></div><div><div class="bar-label"><span>精神</span><strong>{{ state.spCurrent }} / {{ state.spMax }}</strong></div><div class="bar"><i class="sp" :style="{ width: `${Math.max(0, Math.min(100, state.spCurrent / state.spMax * 100))}%` }" /></div></div></div><div class="resource-grid"><div><span>铜币</span><strong>{{ state.resources.copper }}</strong></div><div><span>口粮</span><strong>{{ state.resources.rations }}</strong></div><div><span>药水</span><strong>{{ state.resources.potions }}</strong></div></div><details class="details-block"><summary>属性与装备</summary><div class="stat-grid"><div><span>力量</span><strong>{{ state.strength }}</strong></div><div><span>敏捷</span><strong>{{ state.dexterity }}</strong></div><div><span>体质</span><strong>{{ state.constitution }}</strong></div><div><span>智力</span><strong>{{ state.intelligence }}</strong></div><div><span>感知</span><strong>{{ state.wisdom }}</strong></div><div><span>魅力</span><strong>{{ state.charisma }}</strong></div></div><dl class="equipment-list"><div><dt>武器</dt><dd>{{ state.equipment.weapon }}</dd></div><div><dt>护甲</dt><dd>{{ state.equipment.armor }}</dd></div></dl></details></section>
        <section class="status-panel world-panel"><div class="panel-heading compact-heading"><span class="panel-kicker">WORLD STATE</span><h2>当前世界</h2></div><dl class="world-facts"><div><dt>位置</dt><dd>{{ world.location || '未指定' }}</dd></div><div><dt>区域</dt><dd>{{ regionNames[world.currentRegion || ''] || world.currentRegion || '未指定' }}</dd></div><div><dt>季节</dt><dd>{{ world.season || '未指定' }}</dd></div><div><dt>天气</dt><dd>{{ world.weather || '未指定' }}</dd></div><div><dt>威胁</dt><dd><span class="threat-dot" />{{ world.threatLevel === 'Dormant' ? '沉寂' : world.threatLevel }}</dd></div></dl><div class="quest-callout"><span>主线方向</span><strong>{{ world.mainQuest || '尚未确定' }}</strong></div><div v-if="world.originScene" class="origin-scene"><span>开场场景</span><p>{{ world.originScene }}</p></div></section>
      </aside>
    </div>
  </section>
</template>
