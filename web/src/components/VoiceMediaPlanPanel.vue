<script setup lang="ts">
import { useVoiceMediaPlan } from '../composables/voice-media-plan';
const props = defineProps<{
  /** 当前受控房间。 */ roomId: string;
  /** 当前可信用户。 */ userId: string;
  /** 当前登录轮次。 */ sessionRevision: number;
  /** 父面板当前授权是否核验成功。 */ allowed: boolean;
}>();
const { plan, loading, error, load, paused } = useVoiceMediaPlan(props);
</script>
<template>
  <section aria-label="媒体授权计划" :aria-busy="loading">
    <h3>媒体授权计划与退场</h3>
    <p class="hint">这里显示后台授权目标和退场进度；实际连接、收听和发声状态请查看语音连接面板。</p>
    <button type="button" class="secondary" :disabled="loading || !allowed || !!paused" @click="load()"
      >查询媒体计划</button
    >
    <p v-if="paused" role="status">{{ paused }}</p>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <p v-if="plan && !plan.tracked" role="status">媒体计划候选尚未启用，不代表退场任务已完成。</p>
    <template v-else-if="plan?.tracked">
      <p class="hint"
        >本人轮次 {{ plan.generation || '尚未分配' }} · {{ plan.active ? '希望接入' : '希望离场' }} · 希望发声
        {{ plan.publishDesired ? '允许' : '不允许' }}</p
      >
      <p role="status">房间待退场 {{ plan.pendingRetirements }} 项 · 耗尽预算 {{ plan.deadRetirements }} 项</p>
      <p v-if="plan.deadRetirements" class="form-error" role="alert"
        >存在耗尽预算的任务，不能当作已经清退；需运维核对并恢复，不自动重置次数。</p
      >
    </template>
  </section>
</template>
