<script setup lang="ts">
defineProps<{
  /** 仅已确认的保存/发布步骤。 */ success: string;
  /** 本流程错误，不吞掉其他表单/上传错误。 */ error: string;
  /** 结果未知时需先核实，不允许盲重发写入。 */ needsRecheck: boolean;
  /** 正在执行保存/发布或查询。 */ busy: boolean;
}>();
const emit = defineEmits<{ /** 用户明确要求只读核实。 */ recheck: [] }>();
</script>
<template>
  <p v-if="busy" class="hint" role="status">正在提交或核实，请等待服务端确认…</p>
  <p v-if="success" class="workspace-success" role="status">{{ success }}</p>
  <p v-if="error" class="form-error" role="alert">{{ error }}</p>
  <template v-if="needsRecheck">
    <p class="hint">重新读取会覆盖当前表单，不会重复保存或发布。待核实信息只在当前页面内存中保留。</p>
    <button type="button" class="secondary" :disabled="busy" @click="emit('recheck')">{{
      busy ? '正在核实…' : '重新读取提交结果'
    }}</button>
  </template>
</template>
