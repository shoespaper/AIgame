<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { deleteWorldbook, getWorldbook, loadGameCatalog, updateWorldbook } from '../api/client'
import type { GameCatalog, WorldbookDetail } from '../api/types'

const TYPE_LABELS: Record<string, string> = {
  isekai: '异世界', cyberpunk: '赛博朋克', fantasy: '奇幻', cosmic_horror: '克苏鲁恐怖', noir: '黑色电影', wasteland: '末日废土',
}
const TONE_LABELS: Record<string, string> = { light: '轻松', balanced: '平衡', hardcore: '硬核' }

const catalog = ref<GameCatalog | null>(null)
const busy = ref(false)
const error = ref('')
const notice = ref('')
const editing = ref<WorldbookDetail | null>(null)
const form = reactive({ name: '', type: 'isekai', tone: 'balanced', templateId: '', descriptionHint: '', worldTropesText: '', originContextsText: '', factionsText: '' })

/** 加载世界书与模板目录。 */
async function load() {
  try { catalog.value = await loadGameCatalog() } catch (cause) { error.value = cause instanceof Error ? cause.message : '无法读取世界书目录。' }
}

/** 读取单个世界书详情并填充编辑表单。
 * @param worldId 世界书标识
 */
async function startEdit(worldId: string) {
  busy.value = true; error.value = ''; notice.value = ''
  try {
    const detail = await getWorldbook(worldId)
    editing.value = detail
    form.name = detail.name
    form.type = detail.type
    form.tone = detail.tone
    form.templateId = detail.templateId
    form.descriptionHint = detail.descriptionHint
    form.worldTropesText = detail.worldTropes.join('\n')
    form.originContextsText = detail.originContexts.join('\n')
    form.factionsText = detail.factions.map((faction) => `${faction.type}：${faction.desc_hint}`).join('\n')
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '无法读取世界书详情。' } finally { busy.value = false }
}

/** 将多行文本解析为势力列表。
 * @param text 每行「名称：描述」的原始文本
 */
function parseFactions(text: string): { type: string; desc_hint: string }[] {
  return text.split('\n').map((line) => line.trim()).filter(Boolean).map((line) => {
    const index = line.includes('：') ? line.indexOf('：') : line.indexOf(':')
    if (index < 0) return { type: line, desc_hint: '' }
    return { type: line.slice(0, index).trim(), desc_hint: line.slice(index + 1).trim() }
  })
}

/** 覆盖保存当前编辑的世界书。 */
async function saveEdit() {
  if (!editing.value || busy.value) return
  busy.value = true; error.value = ''; notice.value = ''
  try {
    await updateWorldbook(editing.value.id, {
      id: editing.value.id, name: form.name, type: form.type, templateId: form.templateId,
      tone: form.tone as WorldbookDetail['tone'], descriptionHint: form.descriptionHint,
      factions: parseFactions(form.factionsText),
      originContexts: form.originContextsText.split('\n').map((line) => line.trim()).filter(Boolean),
      worldTropes: form.worldTropesText.split('\n').map((line) => line.trim()).filter(Boolean),
    })
    editing.value = null
    notice.value = '世界书已更新。'
    await load()
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '保存失败。' } finally { busy.value = false }
}

/** 删除世界书前二次确认。
 * @param worldId 世界书标识
 */
async function remove(worldId: string) {
  if (!window.confirm('确定删除这个世界书吗？此操作不可撤销。')) return
  busy.value = true; error.value = ''; notice.value = ''
  try { await deleteWorldbook(worldId); notice.value = '世界书已删除。'; await load() } catch (cause) { error.value = cause instanceof Error ? cause.message : '删除失败。' } finally { busy.value = false }
}

onMounted(() => { void load() })
</script>

<template>
  <section class="page-content game-flow worldbooks-page">
    <div class="section-heading">
      <div><p class="eyebrow">WORLDBOOKS</p><h1>世界书</h1><p class="lede">管理已保存的世界书：编辑叙事设定，或删除不再需要的世界。内置世界书只读。</p></div>
    </div>

    <p v-if="notice" class="success-message" role="status">{{ notice }}</p>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>

    <form v-if="editing" class="form-panel world-edit-panel" @submit.prevent="saveEdit">
      <div class="panel-heading"><p class="panel-kicker">EDIT</p><h2>编辑「{{ editing.name }}」</h2></div>
      <div class="form-grid">
        <label class="field"><span>世界名称</span><input v-model="form.name" maxlength="200" /></label>
        <label class="field"><span>世界类型</span>
          <select v-model="form.type"><option v-for="(label, value) in TYPE_LABELS" :key="value" :value="value">{{ label }}</option></select>
        </label>
        <label class="field"><span>故事基调</span>
          <select v-model="form.tone"><option v-for="(label, value) in TONE_LABELS" :key="value" :value="value">{{ label }}</option></select>
        </label>
        <label class="field"><span>引用模板</span>
          <select v-model="form.templateId"><option value="">无模板</option><option v-for="template in catalog?.templates || []" :key="template.id" :value="template.id">{{ template.name }} · {{ TYPE_LABELS[template.type] || template.type }}</option></select>
        </label>
      </div>
      <label class="field wide"><span>世界背景</span><textarea v-model="form.descriptionHint" rows="3" placeholder="用几句话描述这个世界。" /></label>
      <label class="field wide"><span>世界规则（每行一条）</span><textarea v-model="form.worldTropesText" rows="3" /></label>
      <label class="field wide"><span>出身起点（每行一条）</span><textarea v-model="form.originContextsText" rows="3" /></label>
      <label class="field wide"><span>主要势力（每行「名称：描述」）</span><textarea v-model="form.factionsText" rows="4" /></label>
      <div class="form-actions">
        <button class="quiet-button" type="button" :disabled="busy" @click="editing = null">取消</button>
        <button class="primary-button" type="submit" :disabled="busy">{{ busy ? '保存中…' : '保存修改' }}</button>
      </div>
    </form>

    <div class="save-list">
      <div v-for="world in catalog?.worlds || []" :key="world.id" class="save-row world-row">
        <div class="world-row-main">
          <strong>{{ world.name }}</strong>
          <span>{{ TYPE_LABELS[world.type] || world.type }} · {{ world.templateId || '无模板' }}</span>
          <small>{{ world.description || '暂无描述' }}</small>
        </div>
        <div class="world-row-actions">
          <span v-if="!world.editable" class="world-builtin">内置 · 只读</span>
          <button v-if="world.editable" class="secondary-button" type="button" :disabled="busy" @click="startEdit(world.id)">编辑</button>
          <button v-if="world.editable" class="quiet-button" type="button" :disabled="busy" @click="remove(world.id)">删除</button>
        </div>
      </div>
      <p v-if="catalog && !catalog.worlds.length" class="empty-timeline">暂无世界书。</p>
    </div>
  </section>
</template>
