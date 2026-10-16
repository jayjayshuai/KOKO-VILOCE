/** 单个网页文档的音频连接所有权；不是跨设备/跨标签页或服务端权限锁。 */
export function createAudioConnectionOwnership() {
  /** 每次占用均为不可复用标识，迟到释放不能清除新连接。 */
  let owner: symbol | null = null
  return {
    acquire(): symbol | null {
      if (owner) return null
      return (owner = Symbol('audio-connection'))
    },
    release(lease: symbol | null) {
      if (lease && owner === lease) owner = null
    },
    occupied(): boolean {
      return owner !== null
    },
  }
}
/** 公开语音与私信通话复用同一个文档级实例；凭据、SDK和设备数据不存入此处。 */
export const audioConnectionOwnership = createAudioConnectionOwnership()
