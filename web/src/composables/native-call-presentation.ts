import type { NativeCallView } from '../services/native-calls'

/** 业务同意状态与媒体连接分别展示，不把“已接听”伪装成正在发声。 */
export const nativeParticipantLabel: Record<NativeCallView['participants'][number]['state'], string> = {
  INVITED: '等待接听',
  ACCEPTED: '已接听',
  DECLINED: '已拒接',
  LEFT: '已离开',
  CANCELLED: '已取消',
  MISSED: '未接听',
}
const endReasons: Record<string, string> = {
  NO_ANSWER: '无人接听',
  DECLINED: '邀请已被拒绝',
  CANCELLED: '呼叫已取消',
  ENDED_BY_INITIATOR: '发起者结束了通话',
  PEER_OFFLINE: '通话成员离线',
  PERMISSION_CHANGED: '成员或联系权限已变化',
  WEBSITE_ENDED: '接听会话已退出',
}
/** 仅按本机已知事实解释状态；不推断远端麦克风、收听音量或手机后台能力。 */
export function nativeCallStatus(
  view: NativeCallView | null,
  userId: string | undefined,
  phase: 'idle' | 'joining' | 'connected' | 'reconnecting',
  stopped: boolean,
) {
  if (!view) return '正在确认通话'
  if (view.state === 'ENDED') {
    const reason = view.endReason && Object.hasOwn(endReasons, view.endReason) && endReasons[view.endReason]
    return reason ? `通话已结束 · ${reason}` : '通话已结束'
  }
  const self = view.participants.find((p) => p.userId === userId)
  if (self?.state === 'INVITED') return '接听前不会采集你的麦克风'
  if (self?.state !== 'ACCEPTED') return '你已退出本次通话'
  if (view.state === 'RINGING') return '等待对方接听…'
  if (stopped) return '本机声音已停止，可重新连接收听'
  if (phase === 'connected') return '音频已连接'
  if (phase === 'joining') return '正在连接音频…'
  if (phase === 'reconnecting') return '网络恢复中…'
  return '声音未连接'
}
