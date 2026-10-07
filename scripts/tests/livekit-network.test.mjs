import assert from 'node:assert/strict'
import net from 'node:net'
import dgram from 'node:dgram'
import { once } from 'node:events'
import test from 'node:test'
import { checkLiveKitNetwork } from '../check-livekit-network.mjs'

test('真实TCP与STUN回应通过，send成功和伪事务回应不能冒充UDP可达', async () => {
  const tcp = net.createServer((socket) => socket.end())
  tcp.listen(0, '127.0.0.1')
  await once(tcp, 'listening')
  const udp = dgram.createSocket('udp4')
  udp.bind(0, '127.0.0.1')
  await once(udp, 'listening')
  let valid = true
  udp.on('message', (message, remote) => {
    const response = Buffer.from(message)
    response.writeUInt16BE(0x0101, 0)
    if (!valid) response[8] ^= 1
    udp.send(response, remote.port, remote.address)
  })
  try {
    const options = { tcpPort: tcp.address().port, stunPort: udp.address().port, timeoutMs: 200 }
    const success = await checkLiveKitNetwork('127.0.0.1', options)
    assert.equal(success.iceTcp.reachable, true)
    assert.equal(success.turnStunUdp.reachable, true)
    valid = false
    const fake = await checkLiveKitNetwork('127.0.0.1', options)
    assert.equal(fake.iceTcp.reachable, true)
    assert.equal(fake.turnStunUdp.reachable, false)
    assert.equal(fake.turnStunUdp.reason, 'NO_MATCHING_STUN_RESPONSE')
  } finally {
    udp.close()
    await new Promise((resolve) => tcp.close(resolve))
  }
})
test('凭据URL和越界端口在任何请求前拒绝', async () => {
  await assert.rejects(checkLiveKitNetwork('https://user:secret@example.invalid'))
  await assert.rejects(checkLiveKitNetwork('example.invalid', { tcpPort: 65536 }))
})
