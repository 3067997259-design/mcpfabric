import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js'

// Walks one movement key for a bounded time and samples the position, so a
// blocked step shows up as a stall at a specific x/z with the id of the block
// in front. Built while calibrating the elytra launch run on the main test
// platform, where a ground sprint stopped one block short of the drop.
//
// Usage: node walk-probe.mjs <key> <seconds> [port] [yaw]
const [key, secondsRaw, portRaw, yawRaw] = process.argv.slice(2)
const seconds = Number(secondsRaw ?? 3)
const port = Number(portRaw ?? 25600)
const yaw = Number(yawRaw ?? 270)

const client = new Client({ name: 'walk-probe', version: '1.0.0' })
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

await call('look', { yaw, pitch: 0 })
await call('stop_movement')
await sleep(300)
const start = await call('get_self')
console.log(`start (${start.x.toFixed(2)}, ${start.y}, ${start.z.toFixed(2)}) onGround=${start.onGround}`)

await call('set_movement', { forward: false, back: false, left: false, right: false, sprint: true, [key]: true })
const samples = []
const deadline = Date.now() + seconds * 1000
while (Date.now() < deadline) {
  await sleep(150)
  const s = await call('get_self')
  samples.push(s)
}
await call('stop_movement')

for (const s of samples)
  console.log(`  (${s.x.toFixed(2)}, ${s.y}, ${s.z.toFixed(2)}) motion=(${s.motion.x.toFixed(3)}, ${s.motion.y.toFixed(3)}, ${s.motion.z.toFixed(3)}) onGround=${s.onGround}`)

const end = samples.at(-1)
const dx = end.x - start.x
console.log(`moved dx=${dx.toFixed(2)} dz=${(end.z - start.z).toFixed(2)}; stopped at x=${end.x.toFixed(2)} y=${end.y}`)

// What is in the next cell toward -x (the walk direction at yaw 270 + back)?
const probeX = Math.floor(end.x) - 1
for (const y of [end.y - 1, end.y, end.y + 1]) {
  const b = await call('get_block', { x: probeX, y, z: Math.floor(end.z) })
  console.log(`  block at (${probeX}, ${y}, ${Math.floor(end.z)}) = ${b.id ?? b.text ?? '?'}`)
}
await client.close()
