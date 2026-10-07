<script setup lang="ts">
import type { DiscoveryDomainState } from '../types/workspace';

defineProps<{
  /** 分区读取结果；只有 ready 才挂载本轮内容与操作。 */
  state: DiscoveryDomainState;
  /** 用于无障碍加载和失败提示的分区名称。 */
  label: string;
}>();
const emit = defineEmits<{ retry: [] }>();
</script>

<template>
  <div
    v-if="state.status === 'idle' || state.status === 'loading'"
    class="workspace-skeleton"
    role="status"
    aria-busy="true"
  >
    正在读取{{ label }}…
  </div>
  <div v-else-if="state.status === 'error'" class="workspace-error compact" role="alert">
    <h3>{{ label }}暂时不可用</h3>
    <p>{{ state.error }}</p>
    <button class="secondary compact-button" @click="emit('retry')">重试{{ label }}</button>
  </div>
  <slot v-else />
</template>
