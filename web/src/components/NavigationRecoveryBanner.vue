<script setup lang="ts">
import { navigationLoadFailed } from '../services/navigation-recovery';
/** 仅用户明确确认后刷新；不尝试复制旧JOIN、表单或麦克风意图。 */
function reload() {
  if (window.confirm('刷新会结束当前通话，未保存的修改可能丢失。确认刷新页面吗？')) window.location.reload();
}
</script>
<template>
  <aside v-if="navigationLoadFailed" class="navigation-recovery" role="alert" aria-label="页面加载恢复">
    <div
      ><strong>页面暂时无法打开</strong
      ><p>可能刚发布了更新或网络中断。请刷新获取最新页面；不会自动刷新或重复入房。</p></div
    >
    <button type="button" @click="reload">刷新页面恢复</button>
    <button type="button" @click="navigationLoadFailed = false">暂不刷新</button>
  </aside>
</template>
<style scoped>
.navigation-recovery {
  position: fixed;
  z-index: 120;
  left: 12px;
  right: 12px;
  top: 12px;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  padding: 16px;
  border: 1px solid #a78440;
  border-radius: 12px;
  background: #262116;
  color: #f3e6c9;
  box-shadow: 0 8px 30px #0006;
}
.navigation-recovery div {
  flex: 1;
  min-width: 200px;
}
.navigation-recovery p {
  margin: 8px 0 0;
  line-height: 1.6;
}
.navigation-recovery button {
  padding: 10px 14px;
  border: 1px solid #8c784b;
  border-radius: 8px;
  background: #39301d;
  color: inherit;
  cursor: pointer;
}
</style>
