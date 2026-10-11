/** 仅校验地址格式，不代替服务端房间/成员授权。 */
export function validVoiceRoomId(value: unknown): value is string {
  return typeof value === 'string' && /^[1-9][0-9]{0,18}$/.test(value) && BigInt(value) <= 9223372036854775807n
}
/** 消费一次明确点击的导航意图，保留Router自身历史字段；无Cookie/JWT，刷新和返回不自动重放。 */
export function consumeVoiceRoomEntry(
  roomId: string,
  userId: string,
  revision: number,
  history = window.history,
  documentOrigin = globalThis.performance?.timeOrigin,
) {
  const value = history.state?.kokoVoiceEntry
  if (!value) return null
  // null覆盖Router后续合并的旧对象，避免返回时复活；文档时钟隔离刷新/跨标签。
  history.replaceState({ ...history.state, kokoVoiceEntry: null }, '')
  if (
    !validVoiceRoomId(roomId) ||
    value.roomId !== roomId ||
    value.userId !== userId ||
    value.revision !== revision ||
    typeof documentOrigin !== 'number' ||
    value.documentOrigin !== documentOrigin
  )
    return null
  if (value.mode !== undefined && value.mode !== 'listen' && value.mode !== 'speak') return null
  return {
    title: typeof value.title === 'string' ? value.title.slice(0, 120) : '',
    enter: true,
    mode: (value.mode || 'listen') as 'listen' | 'speak',
  }
}
/** 一次入房与收听编排；未知JOIN、换身份或核验失败不进入SDK，不隐式开启设备。 */
export async function arriveInVoiceRoom(flow: {
  load(): Promise<void>
  current(): boolean
  ready(): boolean
  joined(): boolean
  pending(): boolean
  prepare(): Promise<boolean>
  join(): Promise<unknown> | void
  connect(): Promise<void>
  connected(): boolean
}) {
  if (!(await ensureVoiceRoomMembership(flow))) return false
  await flow.connect()
  return flow.current() && flow.connected()
}
/** 发言编排共用的入房核验，不为上麦前额外创建一条听众媒体连接。 */
export async function ensureVoiceRoomMembership(flow: {
  load(): Promise<void>
  current(): boolean
  ready(): boolean
  joined(): boolean
  pending(): boolean
  prepare(): Promise<boolean>
  join(): Promise<unknown> | void
}) {
  await flow.load()
  if (!flow.current() || !flow.ready() || flow.pending()) return false
  if (!flow.joined()) {
    if (!(await flow.prepare()) || !flow.current()) return false
    await flow.join()
  }
  if (!flow.current() || !flow.ready() || flow.pending() || !flow.joined()) return false
  return true
}
/** 单次明确发言意图：不重试未知写入，不自动代替房主批准，也不循环索取设备权限。 */
export async function runVoiceSpeakingFlow(flow: {
  join(): Promise<boolean>
  current(): boolean
  ready(): boolean
  hasSeat(): boolean
  hasRequest(): boolean
  owner(): boolean
  muted(): boolean
  takeSeat(): Promise<void>
  permit(): Promise<void>
  connect(): Promise<void>
  connected(): boolean
  consumeConsent(): void
  enableMicrophone(): Promise<boolean>
}): Promise<'speaking' | 'waiting' | 'failed' | 'canceled'> {
  const usable = () => flow.current() && flow.ready()
  if (!(await flow.join()) || !usable()) return flow.current() ? 'failed' : 'canceled'
  if (!flow.hasSeat() && !flow.hasRequest()) {
    await flow.takeSeat()
    if (!usable()) return flow.current() ? 'failed' : 'canceled'
  }
  if (flow.hasSeat() && flow.muted() && flow.owner()) {
    await flow.permit()
    if (!usable()) return flow.current() ? 'failed' : 'canceled'
  }
  const publishing = flow.hasSeat() && !flow.muted()
  // 发言许可已具备时先消耗设备意图，连接或设备失败不能由watch反复尝试。
  if (publishing) flow.consumeConsent()
  await flow.connect()
  if (!usable() || !flow.connected()) return flow.current() ? 'failed' : 'canceled'
  if (!publishing) return 'waiting'
  if (!flow.hasSeat() || flow.muted()) return 'failed'
  return (await flow.enableMicrophone()) && usable() && flow.hasSeat() && !flow.muted()
    ? 'speaking'
    : flow.current()
      ? 'failed'
      : 'canceled'
}
