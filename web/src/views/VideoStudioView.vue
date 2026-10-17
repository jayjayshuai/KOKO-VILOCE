<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { onBeforeRouteLeave } from 'vue-router';
import { Film, Upload, LockKeyhole, RefreshCw } from 'lucide-vue-next';
import { useAuthStore } from '../stores/auth';
import { createVideoDraftWorkspace } from '../composables/video-drafts';
import { videoPreviewUrl, type VideoDraft } from '../services/video-drafts';

const auth = useAuthStore(),
  workspace = createVideoDraftWorkspace(auth),
  state = workspace.state;
const file = ref<File | null>(null),
  title = ref(''),
  description = ref(''),
  selected = ref<string | null>(null),
  playerError = ref(''),
  fileInput = ref<HTMLInputElement | null>(null),
  previewRegion = ref<HTMLElement | null>(null);
const locked = computed(() => state.busy || !!state.pending),
  preview = computed(() => state.items.find((row) => row.id === selected.value && row.state === 'READY'));
const labels: Record<VideoDraft['state'], string> = {
  UPLOADING: '等待原上传确认',
  QUEUED: '排队等待处理',
  PROCESSING: '正在检查与转码',
  READY: '私有预览可用',
  FAILED: '处理失败',
};
const failures: Record<string, string> = {
  INVALID_VIDEO: '视频无效、损坏、超出时长/尺寸或无法完整解码。',
  PROCESSING_FAILED: '转码失败或超过处理时限。',
  STORAGE_UNAVAILABLE: '存储暂时不可用。',
  INTERRUPTED: '上传或处理被中断。',
};
let timer: ReturnType<typeof setTimeout> | undefined,
  disposed = false,
  generation = 0;
let previewOpener: HTMLElement | null = null;
async function poll(revision: number) {
  if (disposed || revision !== generation || !auth.user) return;
  await workspace.load(true);
  if (!disposed && revision === generation && state.capability?.enabled)
    timer = setTimeout(() => void poll(revision), document.visibilityState === 'hidden' ? 12000 : 5000);
}
watch(
  () => [auth.user?.id, auth.sessionRevision],
  async () => {
    const revision = ++generation;
    clearTimeout(timer);
    workspace.reset();
    file.value = null;
    title.value = '';
    description.value = '';
    selected.value = null;
    playerError.value = '';
    previewOpener = null;
    if (fileInput.value) fileInput.value.value = '';
    if (auth.user) {
      await workspace.load();
      if (!disposed && revision === generation && state.capability?.enabled)
        timer = setTimeout(() => void poll(revision), 5000);
    }
  },
  { immediate: true, flush: 'sync' },
);
async function submit(retry: boolean) {
  const revision = generation;
  if (retry) await workspace.retry();
  else await workspace.upload(file.value, title.value, description.value);
  if (!disposed && revision === generation && !state.pending && !state.error) {
    file.value = null;
    title.value = '';
    description.value = '';
    if (fileInput.value) fileInput.value.value = '';
  }
}
const upload = () => submit(false);
const retry = () => submit(true);
function choose(event: Event) {
  file.value = (event.target as HTMLInputElement).files?.[0] ?? null;
}
async function select(row: VideoDraft, event: Event) {
  const revision = generation;
  previewOpener = event.currentTarget as HTMLElement;
  selected.value = row.id;
  playerError.value = '';
  await nextTick();
  if (disposed || revision !== generation || !previewRegion.value) return;
  previewRegion.value.focus({ preventScroll: true });
  previewRegion.value.scrollIntoView({ block: 'start' });
}
function closePreview() {
  selected.value = null;
  if (previewOpener?.isConnected) {
    previewOpener.focus({ preventScroll: true });
    previewOpener.scrollIntoView({ block: 'nearest' });
  }
  previewOpener = null;
}
function confirmLeave() {
  return (
    (!file.value && !state.pending) ||
    window.confirm('离开会丢失本页文件选择和原请求重试信息，不会撤回服务端已受理的上传。是否离开？')
  );
}
function beforeUnload(event: BeforeUnloadEvent) {
  if (file.value || state.pending) {
    event.preventDefault();
    event.returnValue = '';
  }
}
window.addEventListener('beforeunload', beforeUnload);
onBeforeRouteLeave(() => confirmLeave());
onBeforeUnmount(() => {
  disposed = true;
  generation++;
  clearTimeout(timer);
  workspace.dispose();
  window.removeEventListener('beforeunload', beforeUnload);
});
const mib = (value: number) => (value / 1048576).toFixed(1) + ' MiB';
</script>
<template>
  <div class="workspace-page-heading"
    ><div
      ><p class="workspace-eyebrow">VIDEO STUDIO</p><h1>视频工作台</h1
      ><p>上传私有草稿，查看真实处理状态，完成后本人预览。</p></div
    ><span class="status-tag"><LockKeyhole :size="14" />仅本人可见</span></div
  >
  <p v-if="!auth.user" class="workspace-card">请先登录，再管理本人视频。</p>
  <template v-else>
    <p v-if="state.loading && !state.capability" role="status">正在核对视频能力…</p>
    <p v-if="state.error" class="form-error" role="alert">{{ state.error }}</p>
    <button
      v-if="!state.capability || state.error"
      class="secondary"
      :disabled="state.loading || state.busy"
      @click="workspace.load()"
      ><RefreshCw :size="16" />重新核对</button
    >
    <section v-if="state.capability && !state.capability.enabled" class="workspace-card"
      ><h2>视频草稿尚未开放</h2><p>处理程序和容量验收完成后才会开放；现有文章、图片和语音不受影响。</p></section
    >
    <div v-if="state.capability?.enabled" class="video-studio-grid">
      <section class="workspace-card"
        ><div class="workspace-card-heading"
          ><h2><Upload :size="20" />上传视频草稿</h2><span>{{ state.items.length }} / 5 条</span></div
        >
        <p class="hint">MP4 · 最多32 MiB / 180秒 · 最大1080p（支持竖屏）。上传后服务端完整解码并转为兼容播放格式。</p>
        <form @submit.prevent="upload"
          ><fieldset :disabled="locked" class="video-fields"
            ><label class="workspace-field"
              >选择MP4视频<input ref="fileInput" type="file" accept="video/mp4,.mp4" @change="choose" /></label
            ><small v-if="file">{{ file.name }} · {{ mib(file.size) }}</small
            ><label class="workspace-field"
              >标题<input v-model="title" maxlength="100" required placeholder="给这段视频起个名字" /></label
            ><label class="workspace-field"
              >说明<textarea
                v-model="description"
                maxlength="1000"
                rows="3"
                placeholder="可选，最多1000字"
              /></label></fieldset
          ><button class="primary" :disabled="locked || !file || !title.trim()">{{
            state.busy ? '正在上传并登记…' : '上传私有草稿'
          }}</button></form
        >
        <div v-if="state.pending" class="video-pending" role="status"
          ><p>保留原文件及资料，避免重复创建草稿。</p
          ><button type="button" class="secondary" :disabled="state.busy" @click="retry">{{
            state.busy ? '正在核实原上传…' : '重试原上传'
          }}</button></div
        >
        <p class="hint"
          >上传不等于公开发布。每条预留96
          MiB源文件与产物预算；失败、未知上传也占额度，未实现核验清理前不释放。当前阶段不提供删除与公开发布。</p
        >
      </section>
      <section class="workspace-card"
        ><div class="workspace-card-heading"
          ><h2><Film :size="20" />本人视频</h2
          ><button class="secondary" :disabled="state.loading || state.busy" @click="workspace.load()"
            >刷新状态</button
          ></div
        >
        <p v-if="state.syncNote" class="hint" role="status">{{ state.syncNote }}</p
        ><p v-if="!state.items.length && !state.loading && !state.error" class="hint"
          >还没有视频草稿，先上传一段MP4。</p
        >
        <ul class="video-draft-list"
          ><li v-for="row in state.items" :key="row.id"
            ><div
              ><strong>{{ row.title }}</strong
              ><span class="status-tag">{{ labels[row.state] }}</span></div
            ><small
              >{{ mib(row.sourceBytes)
              }}<template v-if="row.durationMillis">
                · {{ (row.durationMillis / 1000).toFixed(1) }}秒 · {{ row.width }}×{{ row.height }}</template
              ></small
            ><p v-if="row.state === 'FAILED'" class="form-error"
              >{{
                row.failureCode && Object.hasOwn(failures, row.failureCode)
                  ? failures[row.failureCode]
                  : '处理未完成，请联系运营核对。'
              }}
              不会自动重复转码。</p
            ><button v-if="row.state === 'READY'" class="secondary" @click="select(row, $event)">预览视频</button></li
          ></ul
        >
      </section>
      <section
        v-if="preview"
        ref="previewRegion"
        class="workspace-card video-preview"
        tabindex="-1"
        aria-label="视频预览"
        ><div class="workspace-card-heading"
          ><h2>{{ preview.title }}</h2
          ><button class="secondary" @click="closePreview">关闭预览</button></div
        ><video
          :key="`${auth.user?.id}:${auth.sessionRevision}:${preview.id}`"
          :src="videoPreviewUrl(preview.id)"
          controls
          playsinline
          preload="metadata"
          @error="playerError = '视频加载失败，请刷新状态核对登录与处理结果。'"
          @loadedmetadata="playerError = ''"
        /><p v-if="playerError" class="form-error" role="alert">{{ playerError }}</p
        ><p class="hint">仅本人可预览，不会自动播放，也没有公开分发链接。</p></section
      >
    </div>
  </template>
</template>
<style scoped>
.video-studio-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1.1fr);
  gap: 20px;
}
.video-studio-grid h2 {
  display: flex;
  align-items: center;
  gap: 8px;
}
.video-studio-grid .workspace-card {
  min-width: 0;
}
.video-fields {
  border: 0;
  padding: 0;
  margin: 16px 0;
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 16px;
  min-width: 0;
}
.video-fields .workspace-field {
  min-width: 0;
}
.video-fields input[type='file'] {
  max-width: 100%;
  min-width: 0;
  width: 100%;
}
.video-pending {
  margin: 16px 0;
  padding: 12px;
  border: 1px solid #63548e;
  border-radius: 10px;
}
.video-draft-list {
  list-style: none;
  padding: 0;
  display: grid;
  gap: 12px;
}
.video-draft-list li {
  padding: 16px;
  border: 1px solid #333b4b;
  border-radius: 12px;
  display: grid;
  gap: 10px;
  overflow-wrap: anywhere;
}
.video-draft-list li > div {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  justify-content: space-between;
}
.video-draft-list small,
.video-fields small {
  color: #aab1c4;
  overflow-wrap: anywhere;
}
.video-preview {
  grid-column: 1/-1;
  overflow-wrap: anywhere;
  scroll-margin-top: 100px;
}
.video-preview video {
  display: block;
  width: 100%;
  max-height: 65svh;
  background: #090b10;
  border-radius: 10px;
}
.video-studio-grid button {
  min-height: 44px;
}
.video-studio-grid .hint {
  line-height: 1.7;
}
@media (max-width: 900px) {
  .video-studio-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
