<script setup lang="ts">
import { computed } from 'vue'
import type { EngineActionChoice, NarrativeResponse } from '../api/types'

const props = defineProps<{ turn: NarrativeResponse | null; actionChoices: EngineActionChoice[]; acting: boolean }>()
const emit = defineEmits<{ choose: [label: string]; execute: [choice: EngineActionChoice] }>()

const maxOptions = 4
/** 优先展示引擎提供的可直接提交行动，并保留原顺序。 */
const visibleActions = computed(() => props.actionChoices.slice(0, maxOptions))
/** 按原顺序用 GM 建议填补行动列表的剩余名额。 */
const visibleSuggestions = computed(() => (props.turn?.options ?? []).slice(0, maxOptions - visibleActions.value.length))
</script>

<template>
  <section class="narrative-turn" aria-live="polite">
    <article v-if="turn?.narration" class="narrative-section narration-copy">
      <strong v-if="turn.receiptOnly">系统</strong>
      <p>{{ turn.narration }}</p>
    </article>
    <section v-if="visibleActions.length || visibleSuggestions.length" class="narrative-section">
      <h3>候选行动</h3>
      <div class="narrative-options">
        <button v-for="choice in visibleActions" :key="`action:${choice.id}`" class="action-button engine-action"
          type="button" :disabled="acting" @click="emit('execute', choice)">{{ choice.label }}</button>
        <button v-for="(option, index) in visibleSuggestions" :key="`suggestion:${index}:${option}`" class="action-button"
          type="button" :disabled="acting" @click="emit('choose', option)">{{ option }}</button>
      </div>
    </section>
  </section>
</template>
