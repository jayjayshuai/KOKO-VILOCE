import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'
const source = await readFile(new URL('../src/services/notification-time.ts', import.meta.url), 'utf8')
const module = new vm.SourceTextModule(
  ts.transpileModule(source, {
    compilerOptions: {
      module: ts.ModuleKind.ESNext,
      target: ts.ScriptTarget.ES2022,
    },
  }).outputText,
  { context: vm.createContext({}) },
)
await module.link(() => {
  throw new Error('Unexpected import')
})
await module.evaluate()
const { notificationDate, formatNotificationTime } = module.namespace
test('通知LocalDateTime明确按上海时区解释，支持微秒但不改变服务端时间字符串', () => {
  assert.equal(notificationDate('2026-10-12T08:30:00.123456').toISOString(), '2026-10-12T00:30:00.123Z')
  assert.equal(notificationDate('2024-02-29T00:00:00').toISOString(), '2024-02-28T16:00:00.000Z')
  assert.ok(formatNotificationTime('2026-10-12T08:30:00').includes('08:30:00'))
})
test('无效日期、越界时间、裸文本和非字符串不能成为已读核实事实', () => {
  for (const value of [
    null,
    undefined,
    1,
    '',
    'server-read',
    '2026-02-30T12:00:00',
    '2026-13-01T00:00:00',
    '2026-10-12T24:00:00',
    '2026-10-12T08:99:00',
    '2026-10-12T08:30:60',
  ])
    assert.equal(notificationDate(value), null)
  assert.equal(formatNotificationTime('bad'), '时间未确认')
})
