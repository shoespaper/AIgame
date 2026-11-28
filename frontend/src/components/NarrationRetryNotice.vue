<script setup lang="ts">
defineProps<{ hint: string; message: string; canResume: boolean }>()
const emit = defineEmits<{ resume: [] }>()
</script>

<template>
  <div class="narration-feedback">
    <div class="retry-hint-region" role="status" aria-atomic="true">
      <span v-if="hint" :key="hint" class="retry-hint">{{ hint }}</span>
    </div>
    <div v-if="message || canResume" class="turn-message">
      <span role="status">{{ message || '等待确认本轮结果。' }}</span>
      <button v-if="canResume" class="quiet-button" type="button" @click="emit('resume')">查询回合结果</button>
    </div>
  </div>
</template>

<style scoped>
.narration-feedback { position: relative; }
.retry-hint-region { position: absolute; inset-inline: 12px; bottom: 9px; display: flex; justify-content: center; z-index: 2; pointer-events: none; }
.retry-hint {
  max-width: 100%; padding: 6px 12px; border: 1px solid var(--line); border-radius: var(--radius);
  background: var(--surface); color: var(--text); font-size: 13px; overflow-wrap: anywhere;
  animation: retry-float 1s ease both;
}
.turn-message { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 12px; padding: 6px 24px; color: var(--muted); font-size: 13px; }
@keyframes retry-float {
  from { opacity: 0; transform: translateY(5px); }
  15%, 85% { opacity: 1; transform: translateY(0); }
  to { opacity: 0; transform: translateY(-2px); }
}
@media (prefers-reduced-motion: reduce) { .retry-hint { animation: none; } }
</style>
