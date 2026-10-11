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
  return { title: typeof value.title === 'string' ? value.title.slice(0, 120) : '', enter: true }
}
/** 一次入房与收听编排；未知JOIN、换身份或核验失败不进入SDK，不隐式开启设备。 */
export async function arriveInVoiceRoom(flow: {
  load(): Promise<void>
  current(): boolean
  ready(): boolean
  joined(): boolean
  pending(): boolean
  prepare(): Promise<boolean>
  join(): Promise<void> | void
  connect(): Promise<void>
  connected(): boolean
}) {
  await flow.load()
  if (!flow.current() || !flow.ready() || flow.pending()) return false
  if (!flow.joined()) {
    if (!(await flow.prepare()) || !flow.current()) return false
    await flow.join()
  }
  if (!flow.current() || !flow.ready() || flow.pending() || !flow.joined()) return false
  await flow.connect()
  return flow.current() && flow.connected()
}
