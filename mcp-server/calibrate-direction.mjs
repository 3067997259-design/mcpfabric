import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js'

// Measures what each movement key does at a given look yaw, by holding one key
// for a bounded time and reading the position delta. Elytra work needs this:
// two live attempts at yaw 270 moved the bot along +z and then +x, so the
// look-to-motion mapping cannot be assumed from the tool docs.
//
// Usage: node calibrate-direction.mjs <yaw> [port] [holdMs]
const [yawRaw, portRaw, holdMsRaw] = process.argv.slice(2)
const yaw = Number(yawRaw ?? 270)
const port = Number(portRaw ?? 25600)
const holdMs = Number(holdMsRaw ?? 1500)

const client = new Client({ name: 'direction-calibration', version: '1.0.0' })
await client.connect(new StreamableHTTPClientTransport(new URL(`http://127.0.0.1:${port}/mcp`)))

function textOf(result) {
  return result.structuredContent && typeof result.structuredContent === 'object'
    ? result.structuredContent
    : JSON.parse((result.content ?? []).find(p => p.type === 'text')?.text ?? '{}')
}
async function call(name, args = {}) {
  const result = await client.callTool({ name, arguments: args })
  return textOf(result)
}
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))

console.log(`look yaw=${yaw}, holding each key ${holdMs} ms`)
await call('look', { yaw, pitch: 0 })

for (const key of ['forward', 'back', 'left', 'right']) {
  // Release everything, confirm the bot is standing, then hold one key.
  await call('stop_movement')
  await sleep(300)
  const before = await call('get_self')
  await call('set_movement', { forward: false, back: false, left: false, right: false, sprint: false, jump: false, [key]: true })
  await sleep(holdMs)
  const after = await call('get_self')
  await call('stop_movement')
  const dx = after.x - before.x
  const dz = after.z - before.z
  const distance = Math.hypot(dx, dz)
  const heading = distance < 0.05 ? '(no movement)' : `(${(dx / distance).toFixed(2)}, ${(dz / distance).toFixed(2)})`
  console.log(`${key.padEnd(8)} dx=${dx.toFixed(2).padStart(7)} dz=${dz.toFixed(2).padStart(7)} dist=${distance.toFixed(2).padStart(6)} unit=${heading} onGround=${after.onGround}`)
}
await client.close()
