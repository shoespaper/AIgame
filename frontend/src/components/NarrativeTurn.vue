<script setup lang="ts">
import type { EngineActionChoice, NarrativeResponse } from '../api/types'

defineProps<{ turn: NarrativeResponse | null; actionChoices: EngineActionChoice[]; acting: boolean }>()
const emit = defineEmits<{ choose: [label: string]; execute: [choice: EngineActionChoice] }>()
</script>

<template>
  <section class="narrative-turn" aria-live="polite">
    <article v-if="turn?.narration" class="narrative-section narration-copy"><p>{{ turn.narration }}</p></article>
    <section v-if="actionChoices.length" class="narrative-section">
      <h3>可执行行动</h3>
      <div class="narrative-options">
        <button v-for="choice in actionChoices" :key="choice.id" class="action-button" type="button" :disabled="acting" @click="emit('execute', choice)">{{ choice.label }}</button>
      </div>
    </section>
    <section v-if="turn?.options.length" class="narrative-section">
      <h3>候选行动</h3>
      <div class="narrative-options">
        <button v-for="option in turn.options" :key="option" class="action-button" type="button" :disabled="acting" @click="emit('choose', option)">{{ option }}</button>
      </div>
    </section>
  </section>
</template>
