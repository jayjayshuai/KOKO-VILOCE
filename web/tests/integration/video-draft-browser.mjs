import assert from 'node:assert/strict'
import { createServer, request as httpRequest } from 'node:http'
import { readFile, writeFile, mkdir } from 'node:fs/promises'
import { chromium } from 'playwright'
// 由VideoDraftMysqlCheck的隔离Tomcat驱动；环境中指定构建目录、合成MP4与新证据目录。
// 浏览器可由Playwright安装，或使用VIDEO_CHECK_BROWSER_EXECUTABLE指定已有受信任程序。
const {
  VIDEO_CHECK_PORT: port,
  VIDEO_CHECK_GATEWAY_KEY: key,
  VIDEO_CHECK_DIST: dist,
  VIDEO_CHECK_SOURCE: source,
  VIDEO_CHECK_EVIDENCE: evidence,
} = process.env
assert.ok(port && key && dist && source && evidence)
assert.ok(key.startsWith('synthetic-browser-gateway-'), 'Only the isolated test gateway credential is allowed')
await mkdir(evidence, { recursive: false })
let actor = '201',
  loseReply = true,
  origin,
  backendRequests = 0
const originals = [],
  ranges = [],
  sockets = new Set()
const report = {
  passed: false,
  actualBackendHttp: true,
  realMysqlAndFfmpeg: true,
  identityFixture: true,
  objectStoreFixture: 'local-files-not-MinIO',
  productionAccessed: false,
  pageErrors: 0,
  pageErrorMessages: [],
  groups: [],
}
const send = (res, status, value) => {
  res.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' })
  res.end(value === undefined ? '' : JSON.stringify(value))
}
const server = createServer(async (req, res) => {
  try {
    const path = new URL(req.url, 'http://127.0.0.1').pathname
    if (path.startsWith('/koko-api/assets/videos')) {
      if (req.headers.range) ranges.push(req.headers.range)
      const chunks = []
      for await (const chunk of req) chunks.push(chunk)
      const body = Buffer.concat(chunks)
      if (req.method === 'POST')
        originals.push(body.toString('latin1').match(/name="requestId"\r\n\r\n([a-f0-9-]+)/)?.[1])
      const headers = { ...req.headers, host: '127.0.0.1:' + port, 'x-koko-user-id': actor, 'x-koko-gateway-key': key }
      delete headers.connection
      const upstream = httpRequest(
        {
          host: '127.0.0.1',
          port: Number(port),
          path: req.url.replace('/koko-api/', '/api/'),
          method: req.method,
          headers,
        },
        (reply) => {
          backendRequests++
          if (req.method === 'POST' && loseReply && reply.statusCode === 202) {
            loseReply = false
            reply.resume()
            reply.on('end', () =>
              send(res, 503, {
                code: 'EXPLICIT_LOST_AFTER_COMMIT',
                message: 'Runtime injected response loss after actual accepted upload',
              }),
            )
            return
          }
          res.writeHead(reply.statusCode, reply.headers)
          reply.pipe(res)
        },
      )
      upstream.on('error', () => {
        if (!res.headersSent) send(res, 502, { message: 'Owned backend unavailable' })
        else res.destroy()
      })
      upstream.end(body)
      return
    }
    if (path === '/koko-api/auth/me')
      return send(res, 200, {
        id: actor,
        handle: 'video_' + actor,
        displayName: '合成视频创作者' + actor,
        email: 'video-' + actor + '@example.invalid',
      })
    if (path === '/koko-api/chat/calls/capabilities') return send(res, 200, { enabled: false })
    if (path.includes('/koko-api/chat/') || path.includes('/koko-api/notifications') || path.endsWith('/mine'))
      return send(res, 200, [])
    if (path.startsWith('/koko-api/'))
      return send(res, 404, { code: 'NOT_FOUND', message: 'Explicit unrelated fixture route absent' })
    const relative = path.replace(/^\/koko\//, '') || 'index.html'
    if (!/^(index\.html|assets\/[a-zA-Z0-9_.-]+)$/.test(relative)) return send(res, 404)
    const bytes = await readFile(dist + '/' + relative)
    res.writeHead(200, {
      'Content-Type': relative.endsWith('.js')
        ? 'application/javascript'
        : relative.endsWith('.css')
          ? 'text/css'
          : 'text/html',
    })
    res.end(bytes)
  } catch {
    if (!res.headersSent) send(res, 500, { message: 'Owned test edge failed' })
    else res.destroy()
  }
})
server.on('connection', (socket) => {
  sockets.add(socket)
  socket.on('close', () => sockets.delete(socket))
})
server.on('upgrade', (_, socket) => socket.destroy())
await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
origin = 'http://127.0.0.1:' + server.address().port
let browser, context, page
try {
  browser = await chromium.launch({
    headless: true,
    ...(process.env.VIDEO_CHECK_BROWSER_EXECUTABLE
      ? { executablePath: process.env.VIDEO_CHECK_BROWSER_EXECUTABLE }
      : {}),
    args: ['--autoplay-policy=no-user-gesture-required'],
  })
  context = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true })
  page = await context.newPage()
  page.on('pageerror', (failure) => {
    report.pageErrors++
    report.pageErrorMessages.push(String(failure.message).slice(0, 300))
  })
  await page.goto(origin + '/koko/#/studio/videos')
  await page.getByRole('heading', { name: '视频工作台', exact: true }).waitFor()
  await page.getByRole('button', { name: '预览视频', exact: true }).waitFor()
  await page.getByLabel('选择MP4视频').setInputFiles(source)
  await page.getByLabel('标题', { exact: true }).fill('浏览器真实视频')
  await page.getByLabel('说明', { exact: true }).fill('隔离数据，不公开发布')
  await page.getByRole('button', { name: '上传私有草稿', exact: true }).click()
  await page.getByRole('alert').filter({ hasText: '上传结果尚未确认' }).waitFor()
  assert.equal(await page.getByLabel('标题', { exact: true }).isDisabled(), true)
  await page.getByRole('button', { name: '重试原上传', exact: true }).click()
  await page.waitForFunction(() => {
    const input = document.querySelector('input[maxlength="100"]')
    return input && !input.disabled && input.value === ''
  })
  assert.equal(originals.length, 2)
  assert.ok(originals[0])
  assert.equal(originals[0], originals[1])
  assert.equal(await page.getByLabel('标题', { exact: true }).inputValue(), '')
  const tile = page.locator('.video-draft-list li').filter({ hasText: '浏览器真实视频' })
  await tile.getByRole('button', { name: '预览视频', exact: true }).waitFor({ timeout: 45000 })
  assert.equal(await tile.count(), 1)
  report.groups.push('REAL_MULTIPART_ACCEPT_THEN_RESPONSE_LOSS_IMMUTABLE_ORIGINAL_RETRY_ONE_DRAFT_ACTUAL_PROCESS_READY')
  await tile.getByRole('button', { name: '预览视频', exact: true }).click()
  const video = page.locator('video')
  assert.equal(
    await page
      .getByRole('region', { name: '视频预览', exact: true })
      .evaluate((element) => document.activeElement === element),
    true,
  )
  await page.waitForFunction(
    () => {
      const v = document.querySelector('video')
      return v && v.readyState >= 2 && v.duration >= 1
    },
    { timeout: 20000 },
  )
  assert.equal(await video.evaluate((v) => v.paused), true)
  await video.evaluate(async (v) => {
    await v.play()
  })
  await page.waitForFunction(() => document.querySelector('video')?.currentTime > 0.2)
  await video.evaluate((v) => {
    v.pause()
    v.currentTime = 0.8
  })
  await page.waitForFunction(() => {
    const v = document.querySelector('video')
    return v && !v.seeking && v.currentTime >= 0.7 && v.readyState >= 2
  })
  for (const width of [390, 320, 1440]) {
    await page.setViewportSize({ width, height: width === 1440 ? 1000 : 844 })
    const dimensions = await page.evaluate(() => ({ scroll: document.documentElement.scrollWidth, layout: innerWidth }))
    assert.ok(
      dimensions.scroll <= width + 1 && dimensions.layout <= width + 1,
      JSON.stringify({ expected: width, ...dimensions }),
    )
    await page.screenshot({ path: evidence + '/video-' + width + '.png', fullPage: true })
  }
  const previewPath = await video.getAttribute('src')
  assert.match(previewPath, /^\/koko-api\/assets\/videos\/[a-f0-9-]+\/content$/)
  assert.equal(previewPath.includes('token'), false)
  assert.ok(ranges.length > 0)
  report.groups.push(
    'REAL_BROWSER_H264_DECODE_PLAY_AND_RANGE_SEEK_NO_AUTOPLAY_PRODUCT_CONTROLS_RESPONSIVE_320_390_1440',
  )
  await page.getByRole('button', { name: '关闭预览', exact: true }).click()
  assert.equal(await video.count(), 0)
  assert.equal(
    await tile
      .getByRole('button', { name: '预览视频', exact: true })
      .evaluate((element) => element === document.activeElement),
    true,
  )
  report.groups.push('PREVIEW_AUTO_SCROLL_FOCUS_AND_CLOSE_RETURNS_TO_ORIGINAL_BUTTON')
  actor = '202'
  const denied = await page.evaluate(
    async (path) => (await fetch(path, { headers: { Range: 'bytes=0-15' } })).status,
    previewPath,
  )
  assert.equal(denied, 404)
  await page.reload()
  await page.getByText('处理失败', { exact: true }).waitFor()
  assert.equal(await page.locator('video').count(), 0)
  assert.equal(await page.getByRole('button', { name: '预览视频', exact: true }).count(), 0)
  report.groups.push('REAL_SQL_OTHER_OWNER_PREVIEW_404_NO_PRIVATE_VIDEO_IN_OTHER_ACCOUNT_LIST')
  assert.equal(report.pageErrors, 0)
  report.passed = true
} catch (failure) {
  report.failureType = failure.name
  report.failureReason = String(failure.message).slice(0, 450)
  await page?.screenshot({ path: evidence + '/failure.png', fullPage: true }).catch(() => {})
  process.exitCode = 1
} finally {
  await context?.close()
  await browser?.close()
  for (const socket of sockets) socket.destroy()
  await new Promise((resolve) => server.close(resolve))
  report.backendRequests = backendRequests
  await writeFile(evidence + '/verdict.json', JSON.stringify(report, null, 2))
  console.log(JSON.stringify(report))
}
