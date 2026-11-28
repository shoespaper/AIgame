<script setup lang="ts">
import { computed } from 'vue'
import type { JsonValue, WorldState } from '../api/types'
import {
  parseCharacter,
  parseInventory,
  parseAbilities,
  parseNpcGroups,
  parseQuests,
  parseRules,
  type NpcView,
  type QuestView,
} from './statusPresentation'

const props = defineProps<{
  rulesetState: Record<string, JsonValue> | null
  world: WorldState
  rulesetId: string | null
}>()

/** 规则状态根对象（直接复用，类型为通用 JSON）。 */
const state = computed(() => props.rulesetState)

const character = computed(() => parseCharacter(state.value))
const inventory = computed(() => parseInventory(state.value))
const abilities = computed(() => parseAbilities(state.value))
const npcGroups = computed(() => parseNpcGroups(state.value))
const quests = computed(() => parseQuests(state.value))
const rules = computed(() => parseRules(state.value, props.rulesetId))

/** 合并位置与区域为「地点」展示文案。 */
const locationLabel = computed(() => {
  const parts = [props.world.location, props.world.currentRegion].filter((value) => value && value.trim())
  return parts.length ? parts.join(' · ') : '未指定'
})

/** 时间展示文案：第 N 天 · 时段。 */
const timeLabel = computed(() => `第 ${props.world.day ?? 1} 天 · ${props.world.time || '未知'}`)

/**
 * 计算状态条填充百分比（0~100）。
 * @param current 当前值
 * @param max 上限值
 */
function pct(current: number, max: number): number {
  if (max <= 0) return 0
  return Math.max(0, Math.min(100, Math.round((current / max) * 100)))
}

/** 任务的时间文案：有期限显示剩余天数，否则为无期限。 */
/** @param quest 任务视图 */
function questTime(quest: QuestView): string {
  if (quest.deadlineDays <= 0) return '无期限'
  return `剩余 ${quest.daysRemaining} 天`
}

/** NPC 能力描述：汇总可训练/可战斗/可交易/发任务。 */
/** @param npc NPC 视图 */
function npcCapabilities(npc: NpcView): string {
  const tags: string[] = []
  if (npc.canTrain) tags.push('可训练')
  if (npc.canCombat) tags.push('可战斗')
  if (npc.sellsItems) tags.push('可交易')
  if (npc.givesQuests) tags.push('发任务')
  return tags.length ? tags.join(' · ') : '无'
}

/** NPC 状态对应的样式键，用于状态徽标配色。 */
/** @param statusKey 原始状态枚举 */
function npcStatusClass(statusKey: string): string {
  if (statusKey === 'DEAD') return 'bad'
  if (statusKey === 'MISSING') return 'warn'
  return 'on'
}

/** 任务状态对应的样式键，用于状态徽标配色。 */
/** @param statusKey 原始状态枚举 */
function questStatusClass(statusKey: string): string {
  if (statusKey === 'FAILED') return 'bad'
  if (statusKey === 'COMPLETED') return 'done'
  if (statusKey === 'ACTIVE') return 'on'
  return 'warn'
}
</script>

<template>
  <aside class="status-sidebar">
    <details class="side-panel" open>
      <summary class="side-title">世界信息</summary>
      <div class="side-body">
        <div class="kv-row"><span class="kv-key">地点</span><span class="kv-val">{{ locationLabel }}</span></div>
        <div class="kv-row"><span class="kv-key">时间</span><span class="kv-val">{{ timeLabel }}</span></div>
      </div>
    </details>

    <details class="side-panel" open>
      <summary class="side-title">任务 <em v-if="quests.length" class="side-count">{{ quests.length }}</em></summary>
      <div class="side-body">
        <template v-if="quests.length">
          <details v-for="quest in quests" :key="quest.id" class="side-sub">
            <summary class="side-row">{{ quest.title }} <span class="tag" :class="questStatusClass(quest.statusKey)">{{ quest.statusLabel }}</span></summary>
            <div class="sub-body">
              <div class="kv-row"><span class="kv-key">时间</span><span class="kv-val">{{ questTime(quest) }}</span></div>
              <div class="kv-row"><span class="kv-key">地点</span><span class="kv-val">{{ quest.region || '未指定' }}</span></div>
              <div class="kv-row"><span class="kv-key">事件</span><span class="kv-val">{{ quest.description || '—' }}</span></div>
            </div>
          </details>
        </template>
        <p v-else class="side-empty">暂无任务。</p>
      </div>
    </details>

    <details class="side-panel" open>
      <summary class="side-title">角色</summary>
      <div class="side-body">
        <template v-if="character">
          <div class="kv-row"><span class="kv-key">姓名</span><span class="kv-val">{{ character.name }}</span></div>
          <div class="kv-row"><span class="kv-key">性别 / 年龄</span><span class="kv-val">{{ character.gender }} · {{ character.age }} 岁</span></div>
          <div class="kv-row"><span class="kv-key">职业 / 等级</span><span class="kv-val">{{ character.archetypeLabel }} · {{ character.level }} 级</span></div>
          <div class="attr-grid">
            <div v-for="stat in character.stats" :key="stat.key"><b>{{ stat.label }}</b><strong>{{ stat.value }}</strong></div>
          </div>
          <div class="vital-pair">
            <div>
              <div class="kv-row"><span class="kv-key">生命</span><span class="kv-val">{{ character.hpCurrent }} / {{ character.hpMax }}</span></div>
              <div class="vital-bar"><i class="hp" :style="{ width: pct(character.hpCurrent, character.hpMax) + '%' }" /></div>
            </div>
            <div>
              <div class="kv-row"><span class="kv-key">精神</span><span class="kv-val">{{ character.spCurrent }} / {{ character.spMax }}</span></div>
              <div class="vital-bar"><i class="sp" :style="{ width: pct(character.spCurrent, character.spMax) + '%' }" /></div>
            </div>
          </div>
          <template v-if="rulesetId === 'ap' && character.xpToNext > 0">
            <div class="kv-row"><span class="kv-key">经验</span><span class="kv-val">{{ character.xp }} / {{ character.xpToNext }}</span></div>
            <div class="vital-bar" aria-hidden="true"><i class="xp" :style="{ width: pct(character.xp, character.xpToNext) + '%' }" /></div>
          </template>
          <details class="side-sub">
            <summary class="side-row">技能 <em v-if="abilities.length" class="side-count">{{ abilities.length }}</em></summary>
            <div class="sub-body">
              <template v-if="abilities.length">
                <div v-for="ability in abilities" :key="ability.name" class="sub-item"><strong>{{ ability.name }}</strong><span>{{ ability.effectHint || '—' }}</span></div>
              </template>
              <p v-else class="side-empty">尚未习得技能。</p>
            </div>
          </details>
          <details class="side-sub">
            <summary class="side-row">背包 <em v-if="inventory.length" class="side-count">{{ inventory.length }}</em></summary>
            <div class="sub-body">
              <div class="sub-item money"><strong>存款 · 铜币</strong><span>×{{ character.resources.copper }}</span></div>
              <div class="sub-item"><strong>口粮</strong><span>×{{ character.resources.rations }}</span></div>
              <div class="sub-item"><strong>药水</strong><span>×{{ character.resources.potions }}</span></div>
              <template v-if="inventory.length">
                <div v-for="item in inventory" :key="item.name" class="sub-item"><strong>{{ item.name }} · {{ item.typeLabel }}{{ item.damage ? ' · ' + item.damage : '' }}</strong><span>×{{ item.quantity }}</span></div>
              </template>
              <p v-else class="side-empty">暂无其他物品。</p>
            </div>
          </details>
        </template>
        <p v-else class="side-empty">尚无角色状态。</p>
      </div>
    </details>

    <details class="side-panel" open>
      <summary class="side-title">NPC</summary>
      <div class="side-body">
        <template v-if="npcGroups.length">
          <details v-for="group in npcGroups" :key="group.typeKey" class="side-sub">
            <summary class="side-row">{{ group.typeLabel }} <em class="side-count">{{ group.count }}</em></summary>
            <div class="sub-body">
              <details v-for="npc in group.npcs" :key="npc.id" class="side-sub">
                <summary class="side-row">{{ npc.name }} <span class="tag" :class="npcStatusClass(npc.statusKey)">{{ npc.statusLabel }}</span></summary>
                <div class="sub-body">
                  <div class="kv-row"><span class="kv-key">位置</span><span class="kv-val">{{ npc.location || '未知' }}</span></div>
                  <div class="kv-row"><span class="kv-key">好感</span><span class="kv-val">{{ npc.relationship > 0 ? '+' + npc.relationship : npc.relationship }}</span></div>
                  <div class="kv-row"><span class="kv-key">物品</span><span class="kv-val">{{ npc.inventory.map(item => `${item.name} ×${item.quantity}`).join('、') || '无' }}</span></div>
                  <div class="kv-row"><span class="kv-key">性格</span><span class="kv-val">{{ npc.personality || '—' }}</span></div>
                  <div class="kv-row"><span class="kv-key">能力</span><span class="kv-val">{{ npcCapabilities(npc) }}</span></div>
                </div>
              </details>
            </div>
          </details>
        </template>
        <p v-else class="side-empty">暂无 NPC。</p>
      </div>
    </details>

    <details class="side-panel" open>
      <summary class="side-title">规则状态</summary>
      <div class="side-body">
        <div class="kv-row"><span class="kv-key">难度</span><span class="kv-val">{{ rules.difficultyLabel }}</span></div>
        <div v-if="rulesetId === 'ap'" class="kv-row"><span class="kv-key">威胁等级</span><span class="kv-val">{{ rules.threatLabel }}</span></div>
        <div v-if="rulesetId === 'ap'" class="kv-row"><span class="kv-key">威胁计数</span><span class="kv-val">{{ rules.threatCounter }} / {{ rules.threatInterval }}</span></div>
        <div class="kv-row"><span class="kv-key">天赋</span><span class="kv-val">{{ rules.talentLabel }}</span></div>
        <div class="kv-row"><span class="kv-key">状态效果</span><span class="kv-val">{{ rules.effects.length ? rules.effects.join('；') : '无' }}</span></div>
        <div class="kv-row"><span class="kv-key">规则集</span><span class="kv-val">{{ rules.rulesetLabel }}</span></div>
      </div>
    </details>
  </aside>
</template>
