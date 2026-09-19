/**
 * Live acceptance driver for cancelling the OV-5 launch macro mid-phase.
 *
 * Submits `elytra_launch`, waits `cancelAfterMs`, cancels, and records the
 * terminal status plus the player state a moment later. The macro advances one
 * phase per tick (about 50 ms each), so the delay is what selects the phase:
 * ~0 ms lands in prepare/jump, ~150 ms in release-jump/deploy, ~300 ms in boost.
 *
 * Usage:
 *   node e2e-launch-cancel.mjs <out.json> <cancelAfterMs> [port] [observeMs]
 */
import { writeFileSync } from 'node:fs'
import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js'

const [outRaw, delayRaw, portRaw, observeRaw] = process.argv.slice(2)
const port = Number(portRaw ?? 25600)
const cancelAfterMs = Number(delayRaw ?? 0)
const observeMs = Number(observeRaw ?? 1500)

const client = new Client({ name: 'ov5-cancel-driver', version: '1.0.0' })
await client.connect(new StreamableHTTPClientTransport(new URL(`http://127.0.0.1:${port}/mcp`)))

async function call(tool, args = {}) {
  const result = await client.callTool({ name: tool, arguments: args })
  const body = (result.content ?? []).filter(part => part.type === 'text').map(part => part.text).join('\n')
  try {
    return JSON.parse(body)
  }
  catch {
    return { raw: body, isError: result.isError === true }
  }
}

const before = await call('get_self')
const started = await call('elytra_launch', { goalX: 200, goalY: 195, goalZ: -17, deadlineMs: Date.now() + 8000, withFireworks: true })
await new Promise(resolve => setTimeout(resolve, cancelAfterMs))
const running = await call('elytra_launch_status')
const cancelled = await call('elytra_launch_cancel')
await new Promise(resolve => setTimeout(resolve, observeMs))
const after = await call('get_self')
const finalStatus = await call('elytra_launch_status')

const record = {
  cancelAfterMs,
  startedAtPhase: started.phase,
  phaseAtCancel: running.phase,
  ticksAtCancel: running.ticks,
  deployedAtCancel: running.deployed === true,
  cancelledState: cancelled.state,
  cancelledReason: cancelled.endReason,
  finalStatus,
  before: { position: before.position ?? { x: before.x, y: before.y, z: before.z }, onGround: before.onGround, fallFlying: before.fallFlying, health: before.health },
  after: { position: after.position ?? { x: after.x, y: after.y, z: after.z }, onGround: after.onGround, fallFlying: after.fallFlying, health: after.health, motion: after.motion },
}
if (outRaw)
  writeFileSync(outRaw, JSON.stringify(record, null, 2))
console.log(JSON.stringify(record, null, 2))
await client.close()
