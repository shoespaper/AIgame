<script setup lang="ts">
import type { NarrativeResponse } from '../api/types'

defineProps<{ turn: NarrativeResponse; acting: boolean }>()
const emit = defineEmits<{ choose: [label: string] }>()
</script>

<template>
  <section class="narrative-turn" aria-live="polite">
    <article v-if="turn.narration" class="narrative-section narration-copy"><p>{{ turn.narration }}</p></article>
    <section v-if="turn.options.length" class="narrative-section">
      <h3>候选行动</h3>
      <div class="narrative-options">
        <button v-for="option in turn.options" :key="option" class="action-button" type="button" :disabled="acting" @click="emit('choose', option)">{{ option }}</button>
      </div>
    </section>
  </section>
</template>
