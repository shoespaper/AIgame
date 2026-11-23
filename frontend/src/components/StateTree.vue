<script setup lang="ts">
import { computed } from 'vue'
import type { JsonValue } from '../api/types'

const props = defineProps<{ value: JsonValue; label?: string }>()
/** 将通用 JSON 属性名转换成便于阅读的标签。 */
/** @param value 原始属性名 */
function labelFor(value: string): string { return value.replace(/([a-z0-9])([A-Z])/g, '$1 $2').replaceAll('_', ' ') }
const entries = computed(() => props.value && typeof props.value === 'object' && !Array.isArray(props.value)
  ? Object.entries(props.value) : null)
const items = computed(() => Array.isArray(props.value) ? props.value : null)
</script>

<template>
  <details v-if="entries" class="state-tree-group" :open="!label">
    <summary v-if="label">{{ labelFor(label) }}</summary>
    <dl><div v-for="([key, value]) in entries" :key="key" class="state-tree-entry">
      <dt>{{ labelFor(key) }}</dt><dd><StateTree v-if="value !== null && typeof value === 'object'" :value="value" :label="key" /><span v-else>{{ value === null ? '—' : String(value) }}</span></dd>
    </div></dl>
  </details>
  <ul v-else-if="items" class="state-tree-list"><li v-for="(item, index) in items" :key="index"><StateTree v-if="item !== null && typeof item === 'object'" :value="item" :label="String(index + 1)" /><span v-else>{{ item === null ? '—' : String(item) }}</span></li></ul>
  <span v-else>{{ String(value) }}</span>
</template>
