<script setup lang="ts">
import { computed } from 'vue'
import type { InitializationField } from '../api/types'

const props = defineProps<{
  group: 'profile' | 'engine'
  field: InitializationField
  value?: string
  error?: string
}>()
const emit = defineEmits<{ change: [value: string] }>()

const id = computed(() => `character-${props.group}-${props.field.id}`)
const background = computed(() => props.group === 'profile' && props.field.id === 'background')
const inputType = computed(() => props.field.type === 'INTEGER' || props.field.type === 'DECIMAL' ? 'number' : 'text')
const description = computed(() => props.error ? `${id.value}-error` : props.field.prompt ? `${id.value}-hint` : undefined)

/** 将表单控件中的原始值交给父级保留和校验。
 * @param event 文本、数字或选项控件产生的事件
 */
function read(event: Event) {
  const control = event.target as HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement
  emit('change', control.value)
}
</script>

<template>
  <div class="character-field" :class="{ 'character-name': group === 'profile' && field.id === 'characterName', 'character-background': background }">
    <label :for="id">
      {{ field.label }}{{ group === 'profile' && field.id === 'age' ? '（岁）' : '' }}
      <span v-if="field.required" class="character-required" aria-hidden="true">*</span>
      <span v-else class="character-hint">选填</span>
    </label>
    <textarea
      v-if="background" :id="id" :value="value ?? ''" rows="3"
      placeholder="例如 曾在边境替商队探路。"
      :aria-invalid="!!error" :aria-describedby="description" @input="read"
    ></textarea>
    <select
      v-else-if="field.options.length || field.type === 'BOOLEAN'"
      :id="id" :value="value ?? ''" :required="field.required"
      :aria-invalid="!!error" :aria-describedby="description" @change="read"
    >
      <option value="" :disabled="field.required">请选择{{ field.label }}</option>
      <template v-if="field.options.length">
        <option v-for="option in field.options" :key="option" :value="option">{{ field.optionLabels[option] ?? option }}</option>
      </template>
      <template v-else>
        <option value="true">是</option><option value="false">否</option>
      </template>
    </select>
    <input
      v-else :id="id" :type="inputType" :value="value ?? ''" :required="field.required"
      :min="field.minimum ?? undefined" :max="field.maximum ?? undefined"
      :step="field.type === 'DECIMAL' ? 'any' : undefined"
      :aria-invalid="!!error" :aria-describedby="description" @input="read"
    />
    <p v-if="error" :id="`${id}-error`" class="character-field-error">{{ error }}</p>
    <p v-else-if="field.prompt" :id="`${id}-hint`" class="character-hint">{{ field.prompt }}</p>
  </div>
</template>
