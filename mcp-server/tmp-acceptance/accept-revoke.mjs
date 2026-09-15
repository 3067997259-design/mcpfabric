/**
 * CD-0 acceptance: an external revoke must end the drive.
 *
 * Submits a walk through AIRI, then (mid-run) sends `stop_movement` directly to
 * the bridge, which revokes the input session. AIRI's next `set_movement` must
 * be rejected (`stale_control_session`) and the command must settle with a
 * bounded failure instead of walking on.
 *
 * Usage: node accept-revoke.mjs
 */
import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js'

const server = new Client({ name: 'accept-revoke-server', version: '1.0.0' })
await server.connect(new StreamableHTTPClientTransport(new URL('http://127.0.0.1:25602/mcp')))
const game = new Client({ name: 'accept-revoke-game', version: '1.0.0' })
await game.connect(new StreamableHTTPClientTransport(new URL('http://127.0.0.1:25600/mcp')))

const targets = await (await fetch('http://127.0.0.1:9222/json/list')).json()
const target = targets.find(item => item.type === 'page' && item.url.includes('synced-leader=true'))
const ws = new WebSocket(target.webSocketDebuggerUrl)
await new Promise((resolve, reject) => { ws.onopen = resolve; ws.onerror = reject })
let messageId = 0
const pending = new Map()
ws.onmessage = (event) => {
  const message = JSON.parse(event.data)
  if (pending.has(message.id)) {
    pending.get(message.id)(message)
    pending.delete(message.id)
  }
}
async function evalJs(expression) {
  const id = ++messageId
  ws.send(JSON.stringify({ id, method: 'Runtime.evaluate', params: { expression, awaitPromise: true, returnByValue: true } }))
  const message = await new Promise(resolve => pending.set(id, resolve))
  if (message.result?.exceptionDetails)
    throw new Error(message.result.exceptionDetails.exception?.description ?? 'eval failed')
  return message.result?.result?.value
}

await server.callTool({ name: 'teleport_player', arguments: { player: 'airitest', x: 78.5, y: 75, z: -25.5, yaw: 0, pitch: 0 } })
await new Promise(resolve => setTimeout(resolve, 1500))
await evalJs(`location.hash = '#/devtools/game-host'; 'ok'`)
await new Promise(resolve => setTimeout(resolve, 2000))
for (let attempt = 0; attempt < 10; attempt++) {
  if (await evalJs('window.__AIRI_GAME_HOST_SMOKE__ ? "ready" : "missing"') === 'ready')
    break
  await new Promise(resolve => setTimeout(resolve, 1000))
}

const payload = JSON.stringify({ x: 96, y: 75, z: -26, tolerance: 1 })
await evalJs(`window.__moveResult = 'pending'
window.__AIRI_GAME_HOST_SMOKE__.executeGameTool('game_move_to', ${payload})
  .then(result => { window.__moveResult = result })
  .catch(error => { window.__moveResult = { error: String(error) } })
'started'`)

console.log('[walk] submitted; revoking input after 1.2s')
await new Promise(resolve => setTimeout(resolve, 1200))
const before = await game.callTool({ name: 'get_self', arguments: {} }).then(result => result.structuredContent)
const revoke = await game.callTool({ name: 'stop_movement', arguments: {} })
console.log('[revoke]', revoke.isError ? 'error' : 'ok')
await new Promise(resolve => setTimeout(resolve, 500))
const after = await game.callTool({ name: 'get_self', arguments: {} }).then(result => result.structuredContent)

const startedAt = Date.now()
let receipt
for (;;) {
  const current = await evalJs('typeof window.__moveResult === "string" ? window.__moveResult : JSON.stringify(window.__moveResult)')
  if (current !== 'pending') {
    receipt = JSON.parse(current)
    break
  }
  if (Date.now() - startedAt > 30_000) {
    receipt = { error: 'timeout waiting for the AIRI command' }
    break
  }
  await new Promise(resolve => setTimeout(resolve, 400))
}
console.log(JSON.stringify({
  positionAtRevoke: { x: Number(before.x.toFixed(2)), z: Number(before.z.toFixed(2)) },
  positionAfter: { x: Number(after.x.toFixed(2)), z: Number(after.z.toFixed(2)) },
  receiptEndReason: receipt?.endReason ?? receipt?.error ?? receipt?.result?.endReason,
  receiptChecked: receipt?.checked,
  receiptError: receipt?.error,
  finalPosition: receipt?.finalSnapshot?.position ?? receipt?.result?.finalSnapshot?.position,
}, null, 2))
ws.close()
await game.close()
await server.close()
