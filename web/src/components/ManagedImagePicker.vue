<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue';
import { api, managedImageUrl, type ImagePurpose, type ManagedImage, type ImageQuota } from '../api';

const props = defineProps<{
  /** 上传控件的可读标签。 */
  label: string;
  /** 资产用途，必须与服务端绑定场景一致。 */
  purpose: ImagePurpose;
  /** 当前已绑定资产的预览地址；没有绑定时缺省。 */
  currentUrl?: string;
  /** 外部业务操作繁忙时禁用控件；不替代服务端校验。 */
  disabled?: boolean;
}>();
const emit = defineEmits<{
  uploaded: [image: ManagedImage];
  cleared: [];
  busy: [value: boolean];
}>();

const uploading = ref(false);
const error = ref('');
const libraryOpen = ref(false);
const libraryLoading = ref(false);
const libraryError = ref('');
const images = ref<ManagedImage[]>([]);
const nextCursor = ref<string | null>(null);
const libraryLoaded = ref(false);
const quota = ref<ImageQuota | null>(null);
let active = true;
let uploadController: AbortController | null = null;
let libraryController: AbortController | null = null;
let quotaRevision = 0;

onBeforeUnmount(() => {
  active = false;
  uploadController?.abort();
  libraryController?.abort();
  emit('busy', false);
});

async function loadLibrary() {
  if (libraryLoading.value) return;
  libraryLoading.value = true;
  libraryError.value = '';
  libraryController = new AbortController();
  const revision = quotaRevision;
  try {
    const [result, budget] = await Promise.all([
      !libraryLoaded.value || nextCursor.value
        ? api.myImages(props.purpose, nextCursor.value || undefined, libraryController.signal)
        : Promise.resolve(null),
      api.imageQuota(libraryController.signal),
    ]);
    if (!active) return;
    if (result) {
      const known = new Set(images.value.map((image) => image.id));
      images.value.push(...result.items.filter((image) => !known.has(image.id)));
      nextCursor.value = result.nextCursor;
      libraryLoaded.value = true;
    }
    if (revision === quotaRevision) quota.value = budget;
  } catch (cause) {
    if (active) libraryError.value = cause instanceof Error ? cause.message : '图片库加载失败，请重试。';
  } finally {
    libraryController = null;
    if (active) libraryLoading.value = false;
  }
}

function toggleLibrary() {
  libraryOpen.value = !libraryOpen.value;
  if (libraryOpen.value) void loadLibrary();
}

function selectImage(image: ManagedImage) {
  if (props.disabled || uploading.value) return;
  emit('uploaded', image);
  libraryOpen.value = false;
  error.value = '';
}

async function chooseFile(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = '';
  if (!file || uploading.value || props.disabled) return;
  error.value = '';
  if (!['image/jpeg', 'image/png'].includes(file.type)) {
    error.value = '仅支持 JPEG 或 PNG 图片。';
    return;
  }
  if (file.size === 0 || file.size > 5 * 1024 * 1024) {
    error.value = '图片不能为空，且不能超过 5 MiB。';
    return;
  }
  uploading.value = true;
  emit('busy', true);
  uploadController = new AbortController();
  try {
    const uploaded = await api.uploadImage(file, props.purpose, uploadController.signal);
    if (active) {
      emit('uploaded', uploaded);
      libraryOpen.value = false;
      if (!images.value.some((image) => image.id === uploaded.id)) images.value.unshift(uploaded);
      quotaRevision++;
      quota.value = null;
      try {
        const budget = await api.imageQuota(uploadController.signal);
        if (active) quota.value = budget;
      } catch {
        /* The upload succeeded; quota can be refreshed when the library opens. */
      }
    }
  } catch (cause) {
    if (active) error.value = cause instanceof Error ? cause.message : '图片上传失败，请重试。';
  } finally {
    uploadController = null;
    uploading.value = false;
    if (active) emit('busy', false);
  }
}
</script>

<template>
  <div class="managed-image-picker">
    <label
      >{{ label
      }}<input
        type="file"
        accept="image/jpeg,image/png,.jpg,.jpeg,.png"
        :disabled="disabled || uploading"
        @change="chooseFile"
    /></label>
    <p class="hint">JPEG / PNG，最大 5 MiB；长边不超过 4096 像素。</p>
    <button
      type="button"
      class="secondary"
      :disabled="disabled || uploading"
      :aria-expanded="libraryOpen"
      @click="toggleLibrary"
      >{{ libraryOpen ? '收起图片库' : '选择已上传图片' }}</button
    >
    <div v-if="libraryOpen" class="image-library" :aria-label="`${label}图片库`" :aria-busy="libraryLoading">
      <p v-if="quota" class="hint"
        >图片库已用 {{ quota.usedImages }} / {{ quota.maxImages }} 张，
        {{ (quota.usedBytes / 1024 / 1024).toFixed(1) }} / {{ (quota.maxBytes / 1024 / 1024).toFixed(0) }} MiB。</p
      >
      <div class="image-library-grid">
        <button
          v-for="image in images"
          :key="image.id"
          type="button"
          :disabled="disabled || uploading"
          :aria-label="`选择 ${image.width} × ${image.height} 图片`"
          @click="selectImage(image)"
        >
          <img :src="managedImageUrl(image.id)" alt="" loading="lazy" />
          <span>{{ image.width }} × {{ image.height }}</span>
        </button>
      </div>
      <p v-if="libraryLoading" class="hint" role="status">正在加载图片…</p>
      <p v-if="libraryLoaded && !images.length" class="hint">还没有此类图片，请先上传。</p>
      <p v-if="libraryError" class="form-error" role="alert">{{ libraryError }}</p>
      <button
        v-if="libraryError || nextCursor"
        type="button"
        class="secondary"
        :disabled="libraryLoading"
        @click="loadLibrary"
        >{{ libraryError ? '重试' : '加载更多' }}</button
      >
    </div>
    <p v-if="uploading" class="hint" role="status">正在上传并校验图片…</p>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <div v-if="currentUrl" class="managed-image-preview">
      <img :src="currentUrl" :alt="`${label}预览`" />
      <button type="button" class="secondary" :disabled="disabled || uploading" @click="emit('cleared')"
        >移除图片</button
      >
    </div>
  </div>
</template>
