<script setup lang="ts">
import { Bookmark, LockKeyhole, Users } from 'lucide-vue-next';
import type { UserIdentity } from '../api';
defineProps<{
  /** 本人认证投影，其他用户不会作为账户页数据。 */
  user: UserIdentity;
}>();
const emit = defineEmits<{
  /** 打开本人关注列表。 */
  following: [];
  /** 打开本人内容收藏。 */
  favorites: [];
}>();
const encryptedTransport = location.protocol === 'https:';
</script>
<template>
  <div class="workspace-page-heading"
    ><div
      ><p class="workspace-eyebrow">ACCOUNT & CONNECTIONS</p><h1>账户与连接</h1
      ><p>查看你的资料、内容关系和当前连接能力。</p></div
    ></div
  >
  <div class="account-page-grid"
    ><section class="workspace-card"
      ><div class="workspace-card-heading"><h2>账户资料</h2><span class="status-tag positive">已登录</span></div
      ><dl class="account-facts"
        ><div
          ><dt>显示名称</dt><dd>{{ user.displayName }}</dd></div
        ><div
          ><dt>公开用户名</dt><dd>@{{ user.handle }}</dd></div
        ><div
          ><dt>本人邮箱</dt><dd>{{ user.email }}</dd></div
        ><div
          ><dt>账户 ID</dt><dd>{{ user.id }}</dd></div
        ></dl
      ><div class="page-actions"
        ><button class="secondary" @click="emit('following')"><Users :size="16" />我的关注</button
        ><button class="secondary" @click="emit('favorites')"><Bookmark :size="16" />我的收藏</button></div
      ></section
    >
    <section class="workspace-card"
      ><div class="workspace-card-heading"><h2>会话与能力</h2><LockKeyhole :size="19" /></div
      ><div class="capability-row"
        ><div
          ><strong>当前传输</strong
          ><small>{{
            encryptedTransport ? '通过 HTTPS 访问' : '当前为 HTTP 阶段验收，正式运营须启用 HTTPS/WSS。'
          }}</small></div
        ><span class="status-tag" :class="encryptedTransport ? 'positive' : 'warning'">{{
          encryptedTransport ? '加密' : '待配置 TLS'
        }}</span></div
      ><div class="capability-row"
        ><div><strong>Discord 连接</strong><small>尚未完成正式应用凭据和 OAuth 回调验收。</small></div
        ><span class="status-tag">尚未开放</span></div
      ><div class="capability-row"
        ><div><strong>视频推流与回放</strong><small>排期可创建；实际推流供应商和回放未接入。</small></div
        ><span class="status-tag">尚未开放</span></div
      ><p class="workspace-note"
        >页面不保存会话令牌到浏览器本地存储。路由入口不是授权凭据，服务端仍检查本人身份与资源归属。</p
      ></section
    ></div
  >
</template>
