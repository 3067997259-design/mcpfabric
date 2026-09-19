import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js'

// Measures whether the bot FACES the direction it moves, for each movement key,
// at a fixed look yaw. Position alone is not enough: at yaw 270 a key can move
// the bot west while its body still faces east, which is what "AIRI runs
// backwards" looks like on screen and can also break actions that check facing.
//
// Usage: node facing-probe.mjs <yaw> [port] [holdMs]
const [yawRaw, portRaw, holdMsRaw] = process.argv.slice(2)
const yaw = Number(yawRaw ?? 270)
const port = Number(portRaw ?? 25600)
const holdMs = Number(holdMsRaw ?? 1200)

const client = new Client({ name: 'facing-probe', version: '1.0.0' })
await client.connect(new StreamableHTTPClientTransport(new URL(`http://127.0.0.1:${port}/mcp`)))
function textOf(result) {
  return result.structuredContent && typeof result.structuredContent === 'object'
    ? result.structuredContent
    : JSON.parse((result.content ?? []).find(p => p.type === 'text')?.text ?? '{}')
}
async function call(name, args = {}) {
  return textOf(await client.callTool({ name, arguments: args }))
}
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))

await call('look', { yaw, pitch: 0 })
await call('stop_movement')
await sleep(300)

/** Movement direction the yaw points at, as a unit vector. */
function facingVector(deg) {
  const rad = deg * Math.PI / 180
  return { x: -Math.sin(rad), z: Math.cos(rad) }
}

for (const key of ['forward', 'back', 'left', 'right']) {
  await call('stop_movement')
  await sleep(250)
  await call('look', { yaw, pitch: 0 })
  await sleep(100)
  const before = await call('get_self')
  await call('set_movement', { forward: false, back: false, left: false, right: false, sprint: false, jump: false, [key]: true })
  await sleep(holdMs)
  const after = await call('get_self')
  await call('stop_movement')

  const dx = after.x - before.x
  const dz = after.z - before.z
  const dist = Math.hypot(dx, dz)
  const moveYaw = dist < 0.05 ? undefined : Math.atan2(-dx, dz) * 180 / Math.PI
  const face = facingVector(after.yaw)
  const move = dist < 0.05 ? undefined : { x: dx / dist, z: dz / dist }
  const dot = move ? move.x * face.x + move.z * face.z : undefined

  console.log(`${key.padEnd(8)} moved dx=${dx.toFixed(2).padStart(7)} dz=${dz.toFixed(2).padStart(7)}`)
  console.log(`         yaw: before=${before.yaw} after=${after.yaw}  moveHeading=${moveYaw === undefined ? 'n/a' : moveYaw.toFixed(1)}`)
  console.log(`         facing·movement = ${dot === undefined ? 'n/a' : dot.toFixed(2)} (${dot === undefined ? '-' : dot > 0.5 ? 'FACE FIRST' : dot < -0.5 ? 'RUNNING BACKWARDS' : 'strafe/sideways'})`)
}
await client.close()
