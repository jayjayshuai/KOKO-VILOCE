import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

// 静态部署契约，不替代实际Nginx加载、证书校验和公开网络验收。
function location(source, path) {
  const marker = 'location ^~ ' + path + ' {'
  const start = source.indexOf(marker)
  assert.ok(start >= 0, 'Expected exact protected prefix')
  assert.equal(source.indexOf(marker, start + marker.length), -1, 'Duplicate protected prefix')
  let depth = 1
  for (let index = start + marker.length; index < source.length; index++) {
    if (source[index] === '{') depth++
    if (source[index] === '}' && --depth === 0) return source.slice(start, index + 1)
  }
  throw new Error('Unclosed protected location')
}

test('明文媒体模板直接拒绝且不记录令牌，不重定向或转发', () => {
  const source = readFileSync(new URL('../../deploy/edge-locations.conf', import.meta.url), 'utf8')
  const media = location(source, '/koko-api/media/livekit/')
  assert.match(media, /return 426;/)
  assert.match(media, /access_log off;/)
  assert.match(media, /error_log \/dev\/null crit;/)
  assert.doesNotMatch(media, /proxy_pass|rewrite|https:\/\/|return 30[1278]/)
  assert.match(source, /location \/koko-api\/ \{/)
  assert.match(location(source, '/rtc'), /return 404;/)
})

test('TLS边缘保留受保护媒体升级代理，不重新开放原始SFU路由', () => {
  const source = readFileSync(new URL('../../deploy/nginx-tls.conf', import.meta.url), 'utf8')
  const media = location(source, '/koko-api/media/livekit/')
  assert.match(source, /listen 443 ssl;/)
  assert.match(source, /access_log off;/)
  assert.match(media, /proxy_http_version 1\.1;/)
  assert.match(media, /proxy_set_header Upgrade \$http_upgrade;/)
  assert.match(media, /proxy_pass http:\/\/koko-nexus-gateway:8080;/)
  assert.match(location(source, '/rtc'), /return 404;/)
})
