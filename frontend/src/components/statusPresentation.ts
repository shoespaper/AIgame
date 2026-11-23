import type { JsonValue, WorldState } from '../api/types'

/**
 * 规则状态在服务端以通用 JSON（engine-neutral）呈现；本模块集中负责「安全读取 + 中文标签映射」，
 * 避免在组件里散落裸英文字段名。所有取值都做防御性判断，缺失字段时回退到空值或占位文案。
 */

type JsonMap = { [key: string]: JsonValue }

/** 六维属性条目，供角色属性网格渲染。 */
export interface StatEntry { key: string; label: string; value: number }

/** 背包物品视图。 */
export interface ItemView { name: string; typeLabel: string; damage: string; quantity: number }

/** 技能/法术能力视图。 */
export interface AbilityView { name: string; kindLabel: string; effectHint: string }

/** 角色面板视图模型。 */
export interface CharacterView {
  name: string
  gender: string
  age: number
  archetypeLabel: string
  level: number
  xp: number
  xpToNext: number
  stats: StatEntry[]
  hpCurrent: number
  hpMax: number
  spCurrent: number
  spMax: number
  resources: { copper: number; rations: number; potions: number }
}

/** 单个 NPC 视图。 */
export interface NpcView {
  id: string
  name: string
  typeLabel: string
  location: string
  relationship: number
  mood: number
  statusLabel: string
  statusKey: string
  personality: string
  canTrain: boolean
  canCombat: boolean
  sellsItems: boolean
  givesQuests: boolean
}

/** 按身份聚合后的 NPC 分组。 */
export interface NpcGroup { typeKey: string; typeLabel: string; count: number; npcs: NpcView[] }

/** 任务视图。 */
export interface QuestView {
  id: string
  title: string
  statusKey: string
  statusLabel: string
  region: string
  description: string
  daysRemaining: number
  deadlineDays: number
}

/** 规则状态面板视图模型。 */
export interface RulesView {
  difficultyLabel: string
  threatLabel: string
  threatCounter: number
  threatInterval: number
  talentLabel: string
  effects: string[]
  rulesetLabel: string
}

const ARCHETYPE_LABELS: Record<string, string> = { swordsman: '剑士', mage: '法师', ranger: '游侠', alchemist: '炼金术士' }
const DIFFICULTY_LABELS: Record<string, string> = { story: '叙事', adventure: '冒险', hard: '艰难', nightmare: '噩梦' }
const THREAT_LABELS: Record<string, string> = { Dormant: '沉寂', Low: '低', Moderate: '中等', High: '高', Severe: '严重', Crisis: '危机' }
const NPC_TYPE_LABELS: Record<string, string> = { family: '家人', companion: '同伴', merchant: '商人', instructor: '导师', questgiver: '委托人', antagonist: '敌对', authority: '官员', refugee: '难民', traveler: '旅人', pilgrim: '朝圣者', other: '其他' }
const NPC_STATUS_LABELS: Record<string, string> = { ACTIVE: '活跃', DEAD: '死亡', MISSING: '失踪' }
const QUEST_STATUS_LABELS: Record<string, string> = { AVAILABLE: '可接取', ACTIVE: '进行中', COMPLETED: '已完成', FAILED: '已失败' }
const ITEM_TYPE_LABELS: Record<string, string> = { weapon: '武器', armor: '护甲', accessory: '饰品', consumable: '消耗品', material: '材料' }

/** 从标签映射中取中文文案，未知时回退为原始键。 */
/** @param map 键到中文文案的映射 @param key 原始枚举键 */
function label(map: Record<string, string>, key: string): string {
  return key && map[key] ? map[key] : key
}

/** 将 JSON 值安全转换为对象映射，非对象或数组时返回 null。 */
function asMap(value: JsonValue | null | undefined): JsonMap | null {
  return value && typeof value === 'object' && !Array.isArray(value) ? (value as JsonMap) : null
}

/** 将 JSON 值安全转换为数组，非数组时返回空数组。 */
function asList(value: JsonValue | null | undefined): JsonValue[] {
  return Array.isArray(value) ? value : []
}

/** 将 JSON 值安全转换为字符串。 */
/** @param value 原始值 @param fallback 缺失时的回退值 */
function asStr(value: JsonValue | null | undefined, fallback = ''): string {
  return typeof value === 'string' ? value : fallback
}

/** 将 JSON 值安全转换为数字。 */
/** @param value 原始值 @param fallback 缺失时的回退值 */
function asNum(value: JsonValue | null | undefined, fallback = 0): number {
  return typeof value === 'number' ? value : fallback
}

/** 将 JSON 值安全转换为布尔。 */
function asBool(value: JsonValue | null | undefined): boolean {
  return value === true
}

/** 从规则状态解析角色面板视图模型；没有角色名时返回 null。 */
/** @param state 规则状态根对象 */
export function parseCharacter(state: JsonMap | null): CharacterView | null {
  if (!state) return null
  const name = asStr(state.characterName).trim()
  if (!name) return null
  const stats: StatEntry[] = [
    { key: 'strength', label: '力量', value: asNum(state.strength) },
    { key: 'dexterity', label: '敏捷', value: asNum(state.dexterity) },
    { key: 'constitution', label: '体质', value: asNum(state.constitution) },
    { key: 'intelligence', label: '智力', value: asNum(state.intelligence) },
    { key: 'wisdom', label: '感知', value: asNum(state.wisdom) },
    { key: 'charisma', label: '魅力', value: asNum(state.charisma) },
  ]
  const resources = asMap(state.resources)
  return {
    name,
    gender: asStr(state.gender, '未知'),
    age: asNum(state.age),
    archetypeLabel: label(ARCHETYPE_LABELS, asStr(state.archetype)),
    level: asNum(state.level, 1),
    xp: asNum(state.xp),
    xpToNext: asNum(state.xpToNext),
    stats,
    hpCurrent: asNum(state.hpCurrent),
    hpMax: asNum(state.hpMax),
    spCurrent: asNum(state.spCurrent),
    spMax: asNum(state.spMax),
    resources: { copper: asNum(resources?.copper), rations: asNum(resources?.rations), potions: asNum(resources?.potions) },
  }
}

/** 从规则状态解析背包物品列表。 */
/** @param state 规则状态根对象 */
export function parseInventory(state: JsonMap | null): ItemView[] {
  if (!state) return []
  return asList(state.inventory).map((entry) => {
    const item = asMap(entry)
    return {
      name: asStr(item?.name),
      typeLabel: label(ITEM_TYPE_LABELS, asStr(item?.type)),
      damage: asStr(item?.damage),
      quantity: asNum(item?.quantity, 1),
    }
  }).filter((item) => item.name)
}

/** 从规则状态解析技能与法术（合并展示，法术加标签）。 */
/** @param state 规则状态根对象 */
export function parseAbilities(state: JsonMap | null): AbilityView[] {
  if (!state) return []
  const read = (value: JsonValue, kindLabel: string): AbilityView[] => asList(value).map((entry) => {
    const ability = asMap(entry)
    return { name: asStr(ability?.name), kindLabel, effectHint: asStr(ability?.effectHint) || asStr(ability?.description) }
  }).filter((ability) => ability.name)
  return [...read(state.skills, '技能'), ...read(state.spells, '法术')]
}

/** 从规则状态解析 NPC 并按身份分组。 */
/** @param state 规则状态根对象 */
export function parseNpcGroups(state: JsonMap | null): NpcGroup[] {
  if (!state) return []
  const systems = asMap(state.systems)
  const npcs = asList(systems?.npcs).map((entry): NpcView | null => {
    const npc = asMap(entry)
    if (!npc) return null
    const name = asStr(npc.name).trim()
    if (!name) return null
    const statusKey = asStr(npc.status, 'ACTIVE')
    return {
      id: asStr(npc.npcId) || name,
      name,
      typeLabel: label(NPC_TYPE_LABELS, asStr(npc.type, 'other')),
      location: asStr(npc.location),
      relationship: asNum(npc.relationship),
      mood: asNum(npc.mood),
      statusKey,
      statusLabel: label(NPC_STATUS_LABELS, statusKey),
      personality: asStr(npc.personality),
      canTrain: asBool(npc.canTrain),
      canCombat: asBool(npc.canCombat),
      sellsItems: asBool(npc.sellsItems),
      givesQuests: asBool(npc.givesQuests),
    }
  }).filter((npc): npc is NpcView => npc !== null)
  const groups = new Map<string, NpcGroup>()
  for (const npc of npcs) {
    const typeKey = npc.typeLabel
    const group = groups.get(typeKey) ?? { typeKey, typeLabel: typeKey, count: 0, npcs: [] }
    group.npcs.push(npc)
    groups.set(typeKey, group)
  }
  return [...groups.values()].map((group) => ({ ...group, count: group.npcs.length }))
}

/** 从规则状态解析任务列表。 */
/** @param state 规则状态根对象 */
export function parseQuests(state: JsonMap | null): QuestView[] {
  if (!state) return []
  const systems = asMap(state.systems)
  return asList(systems?.questLog).map((entry) => {
    const quest = asMap(entry)
    const statusKey = asStr(quest?.status, 'AVAILABLE')
    return {
      id: asStr(quest?.questId),
      title: asStr(quest?.title) || asStr(quest?.questId),
      statusKey,
      statusLabel: label(QUEST_STATUS_LABELS, statusKey),
      region: asStr(quest?.region),
      description: asStr(quest?.description),
      daysRemaining: asNum(quest?.daysRemaining),
      deadlineDays: asNum(quest?.deadlineDays),
    }
  }).filter((quest) => quest.id)
}

/** 从规则状态与世界状态解析规则状态面板。 */
/** @param state 规则状态根对象 @param world 世界状态 @param rulesetId 规则集标识 */
export function parseRules(state: JsonMap | null, world: WorldState, rulesetId: string | null): RulesView {
  const talent = asMap(state?.uniqueTalent)
  const talentName = asStr(talent?.name)
  const talentAwakened = asBool(talent?.awakened)
  const effects = asList(state?.statusEffects).map((entry) => {
    const effect = asMap(entry)
    const name = asStr(effect?.name)
    if (!name) return null
    const duration = asNum(effect?.duration)
    const penalty = asStr(effect?.penalty)
    const parts = [name, duration > 0 ? `${duration} 回合` : '', penalty].filter(Boolean)
    return parts.join(' · ')
  }).filter((text): text is string => text !== null)
  return {
    difficultyLabel: label(DIFFICULTY_LABELS, asStr(state?.difficulty)),
    threatLabel: label(THREAT_LABELS, world.threatLevel),
    threatCounter: world.threatCounter ?? 0,
    threatInterval: world.threatInterval ?? 0,
    talentLabel: talentName ? `${talentName} · ${talentAwakened ? '已觉醒' : '未觉醒'}` : '无',
    effects,
    rulesetLabel: rulesetId ? rulesetId.toUpperCase() : '纯叙事',
  }
}
