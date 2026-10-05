<script setup lang="ts">
import { ref } from 'vue';
import { Image, ShieldCheck } from 'lucide-vue-next';
import ManagedImagePicker from '../components/ManagedImagePicker.vue';
import { managedImageUrl, type ImagePurpose, type ManagedImage } from '../api';
import { useAuthStore } from '../stores/auth';
import { useAssetReferenceInspection } from '../composables/asset-reference-inspection';

const purpose = ref<ImagePurpose>('POST_COVER');
const selected = ref<ManagedImage | null>(null);
const busy = ref(false);
const auth = useAuthStore();
const { snapshot, loading, error, referenced, inspect } = useAssetReferenceInspection(selected, auth);

function changePurpose() {
  selected.value = null;
}
function checkedTime(value: string) {
  return new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false });
}
</script>

<template>
  <div class="workspace-page-heading">
    <div>
      <p class="workspace-eyebrow">MEDIA LIBRARY</p>
      <h1>素材中心</h1>
      <p>本人图片、用途分离与真实配额，在一个地方管理。</p>
    </div>
    <span class="status-tag"><ShieldCheck :size="14" />私有资产</span>
  </div>
  <div class="asset-workspace-grid">
    <section class="workspace-card">
      <div class="workspace-card-heading"><h2>上传与复用</h2><Image :size="20" /></div>
      <label class="workspace-field"
        >图片用途
        <select v-model="purpose" :disabled="busy" @change="changePurpose">
          <option value="POST_COVER">文章封面</option>
          <option value="AVATAR">UP 主头像</option>
          <option value="BANNER">UP 主主页封面</option>
        </select>
      </label>
      <ManagedImagePicker
        :key="purpose"
        label="本人图片"
        :purpose="purpose"
        :current-url="selected ? managedImageUrl(selected.id) : undefined"
        @uploaded="(image) => (selected = image)"
        @cleared="selected = null"
        @busy="(value) => (busy = value)"
      />
    </section>
    <aside class="workspace-card asset-guidance">
      <p class="workspace-eyebrow">资产使用说明</p>
      <h2>上传不等于发布</h2>
      <p>这里只上传或选择素材，不会自动绑定到文章或主页。请在内容编辑器或 UP 主中心选择图片并保存。</p>
      <ul>
        <li>JPEG / PNG，不超过 5 MiB。</li>
        <li>服务端校验真实格式和图片尺寸。</li>
        <li>未发布资料、草稿图片仅所有者可读。</li>
        <li>移除预览不代表物理删除，也不立即释放配额。</li>
      </ul>
      <section
        v-if="selected"
        class="selected-asset asset-reference-inspection"
        aria-label="所选图片引用核验"
        :aria-busy="loading"
      >
        <strong>已选择图片</strong>
        <small>ID {{ selected.id }}</small>
        <span>{{ selected.width }} × {{ selected.height }}</span>
        <button type="button" class="secondary" :disabled="loading || busy || !auth.user" @click="inspect">
          {{ loading ? '正在核验引用…' : error ? '重试引用核验' : snapshot ? '刷新引用核验' : '查看图片引用' }}
        </button>
        <p v-if="loading" class="hint" role="status">正在读取主页和文章的真实引用，包含草稿及归档…</p>
        <p v-if="error" class="form-error" role="alert">{{ error }} 未确认引用状态，不作未使用判断。</p>
        <div v-if="snapshot" class="asset-reference-result" role="status">
          <strong>{{ referenced ? '图片仍被引用' : '本次查询未发现引用' }}</strong>
          <p>UP 主头像或主页封面：{{ snapshot.profileReferenced ? '有引用' : '未发现引用' }}</p>
          <p>文章封面：{{ snapshot.postReferenced ? '有引用' : '未发现引用' }}</p>
          <small>核验时间：{{ checkedTime(snapshot.checkedAt) }}（北京时间）</small>
          <p class="hint">这是两域查询快照，不是安全删除许可，也不会让草稿图片公开。自动删除尚未启用。</p>
        </div>
      </section>
      <p v-else class="hint">上传或选择一张本人图片后，可核验包含草稿的真实引用。</p>
    </aside>
  </div>
</template>

<style scoped>
.asset-reference-inspection {
  overflow-wrap: anywhere;
}
.asset-reference-result {
  padding-top: 12px;
  border-top: 1px solid var(--border-color, #303541);
}
.asset-reference-result p {
  margin: 8px 0;
}
.asset-reference-inspection button {
  align-self: flex-start;
}
</style>
