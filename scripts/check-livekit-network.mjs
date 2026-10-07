import net from 'node:net'
import dgram from 'node:dgram'
import { randomBytes } from 'node:crypto'
import { pathToFileURL } from 'node:url'

/** 只探测TCP连接和TURN/STUN绑定回应；UDP send成功不能冒充ICE或音频可达。 */
export async function checkLiveKitNetwork(host, { tcpPort = 7881, stunPort = 3478, timeoutMs = 5000 } = {}) {
  if (typeof host !== 'string' || !host || /[\s/@?#]/.test(host) || (!net.isIP(host) && !/^[a-zA-Z0-9.-]+$/.test(host)))
    throw new Error('只接受无凭据的主机名或IP')
  if (
    ![tcpPort, stunPort].every((port) => Number.isInteger(port) && port >= 1 && port <= 65535) ||
    !Number.isInteger(timeoutMs) ||
    timeoutMs < 20 ||
    timeoutMs > 15000
  )
    throw new Error('端口或等待上限无效')
  const tcp = new Promise((resolve) => {
    const socket = net.createConnection({ host, port: tcpPort })
    let ended = false
    const finish = (reachable, reason) => {
      if (ended) return
      ended = true
      clearTimeout(timer)
      socket.destroy()
      resolve({ port: tcpPort, reachable, reason })
    }
    const timer = setTimeout(() => finish(false, 'TIMEOUT'), timeoutMs)
    socket.once('connect', () => finish(true, 'TCP_CONNECTED'))
    socket.once('error', (error) => finish(false, error.code || 'CONNECT_FAILED'))
  })
  const stun = new Promise((resolve) => {
    const socket = dgram.createSocket(net.isIP(host) === 6 ? 'udp6' : 'udp4')
    const transaction = randomBytes(12),
      request = Buffer.alloc(20)
    request.writeUInt16BE(0x0001, 0)
    request.writeUInt32BE(0x2112a442, 4)
    transaction.copy(request, 8)
    let ended = false
    const finish = (reachable, reason) => {
      if (ended) return
      ended = true
      clearTimeout(timer)
      socket.close()
      resolve({ port: stunPort, reachable, reason })
    }
    const timer = setTimeout(() => finish(false, 'NO_MATCHING_STUN_RESPONSE'), timeoutMs)
    socket.on('message', (response) => {
      if (
        response.length < 20 ||
        response.readUInt32BE(4) !== 0x2112a442 ||
        !response.subarray(8, 20).equals(transaction) ||
        response.readUInt16BE(2) % 4 !== 0 ||
        response.readUInt16BE(2) !== response.length - 20
      )
        return
      if ([0x0101, 0x0111].includes(response.readUInt16BE(0))) finish(true, 'MATCHING_STUN_RESPONSE')
    })
    socket.on('error', (error) => finish(false, error.code || 'UDP_FAILED'))
    socket.send(request, stunPort, host, (error) => {
      if (error) finish(false, error.code || 'UDP_FAILED')
    })
  })
  const [iceTcp, turnStunUdp] = await Promise.all([tcp, stun])
  return {
    checkedAt: new Date().toISOString(),
    iceTcp,
    turnStunUdp,
    scope: '仅TCP握手及匹配事务的STUN回应；不证明UDP媒体7882、WebRTC、TLS或完整音频链路',
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    if (process.argv.length !== 4 || process.argv[2] !== '--host')
      throw new Error('用法：npm run check:media-network -- --host <网站主机或IP>')
    const result = await checkLiveKitNetwork(process.argv[3])
    console.log(JSON.stringify(result, null, 2))
    if (!result.iceTcp.reachable || !result.turnStunUdp.reachable) process.exitCode = 1
  } catch (error) {
    console.error(error.message)
    process.exitCode = 2
  }
}
