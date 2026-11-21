<script setup lang="ts">
import type { NarrativeResponse, NarrativeOption, NarrativeProposalCandidate } from '../api/types'

const props = defineProps<{ turn: NarrativeResponse; acting: boolean }>()
const emit = defineEmits<{ choose: [option: NarrativeOption]; applyProposal: [proposal: NarrativeProposalCandidate, index: number] }>()
/** 读取与提案索引对应的服务端转换预览。 */
function preview(index: number) { return props.turn.proposalPreviews[index] }
/** 将判别联合值格式化为便于玩家核对的文本。 */
function displayValue(value: { type: string; value: string | number | boolean }): string { return `${value.type}: ${String(value.value)}` }
</script>

<template>
  <section class="narrative-turn" aria-live="polite">
    <article v-if="turn.narration" class="narrative-section narration-copy"><p>{{ turn.narration }}</p></article>
    <section v-if="turn.options.length" class="narrative-section"><h3>可选行动</h3><div class="narrative-options"><button v-for="option in turn.options" :key="option.id" class="action-button" type="button" :disabled="acting || !option.intent" :title="option.intent ? '' : '该选项缺少可执行意图'" @click="emit('choose', option)">{{ option.label }}<small v-if="!option.intent">暂不可执行</small></button></div></section>
    <section v-if="turn.proposals.length" class="narrative-section"><h3>规则提案</h3><article v-for="(proposal, index) in turn.proposals" :key="`${proposal.type}-${index}`" class="structured-item"><div><strong>{{ proposal.type }}</strong><p v-for="(value, key) in proposal.fields" :key="key">{{ key }}：{{ value }}</p><small v-if="preview(index)">{{ preview(index)?.convertible ? '可应用' : '不可应用' }}<template v-if="preview(index)?.reason"> · {{ preview(index)?.reason }}</template></small></div><button class="secondary-button compact" type="button" :disabled="acting || !preview(index)?.convertible" @click="emit('applyProposal', proposal, index)">应用</button></article></section>
    <section v-if="turn.initializationQuestions.length" class="narrative-section"><h3>GM 提问</h3><article v-for="question in turn.initializationQuestions" :key="question.fieldId" class="structured-item"><div><strong>{{ question.fieldId }}</strong><p>{{ question.prompt }}</p><small v-if="question.options.length">选项：{{ question.options.join(' / ') }}</small></div></article></section>
    <section v-if="turn.parseCandidates.length" class="narrative-section"><h3>解析候选</h3><article v-for="(candidate, index) in turn.parseCandidates" :key="`${candidate.fieldId}-${index}`" class="structured-item"><div><strong>{{ candidate.fieldId }}</strong><p>{{ displayValue(candidate.value) }}</p><small>置信度 {{ Math.round(candidate.confidence * 100) }}%</small></div></article></section>
    <section v-if="turn.mediaRequests.length" class="narrative-section"><h3>媒体请求</h3><article v-for="(media, index) in turn.mediaRequests" :key="`${media.assetId}-${index}`" class="structured-item"><div><strong>{{ media.kind }}<template v-if="media.assetId"> · {{ media.assetId }}</template></strong><p>{{ media.prompt }}</p></div></article></section>
  </section>
</template>
