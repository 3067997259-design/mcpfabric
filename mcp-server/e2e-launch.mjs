/**
 * Live acceptance driver for the OV-5 elytra launch macro.
 *
 * Submits `elytra_launch` once and polls `elytra_launch_status` until the macro
 * reaches a terminal state, writing every sample to a JSON recording plus a
 * compact trace on stdout. It exists because the macro is a per-tick sequence
 * inside the client: the only way to see the phase order, the deploy tick and
 * the rocket's climb is to sample the status while it runs.
 *
 * Usage:
 *   node e2e-launch.mjs <out.json> <goalX> <goalY> <goalZ> [port] [observeMs]
 */
import { writeFileSync } from 'node:fs'
import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js'

const [outRaw, goalXRaw, goalYRaw, goalZRaw, portRaw, observeRaw] = process.argv.slice(2)
const port = Number(portRaw ?? 25600)
const observeMs = Number(observeRaw ?? 0)
const goal = { x: Number(goalXRaw), y: Number(goalYRaw), z: Number(goalZRaw) }

const client = new Client({ name: 'ov5-launch-driver', version: '1.0.0' })
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

const started = Date.now()
const before = await call('get_self')
const samples = []
const first = await call('elytra_launch', {
  goalX: goal.x,
  goalY: goal.y,
  goalZ: goal.z,
  deadlineMs: Date.now() + 8000,
  withFireworks: true,
})
samples.push({ t: Date.now() - started, status: first })

const terminal = new Set(['done', 'failed', 'cancelled'])
let last = first
while (!terminal.has(last.state ?? 'idle')) {
  await new Promise(resolve => setTimeout(resolve, 120))
  last = await call('elytra_launch_status')
  samples.push({ t: Date.now() - started, status: last })
  const position = last.position ?? {}
  console.log([
    `t=${String(samples.at(-1).t).padStart(5)}ms`,
    `phase=${String(last.phase).padEnd(12)}`,
    `ticks=${String(last.ticks).padStart(3)}`,
    `y=${Number(position.y ?? 0).toFixed(2).padStart(7)}`,
    `x=${Number(position.x ?? 0).toFixed(2).padStart(8)}`,
    `vy=${Number(last.verticalSpeed ?? 0).toFixed(3).padStart(6)}`,
    `deployed=${last.deployed === true}`,
    `boost=${last.boostSeen === true}`,
    `climb=${Number(last.climb ?? 0).toFixed(2)}`,
  ].join(' '))
}

if (observeMs > 0) {
  const until = Date.now() + observeMs
  while (Date.now() < until) {
    await new Promise(resolve => setTimeout(resolve, 150))
    const state = await call('get_self')
    samples.push({ t: Date.now() - started, state })
    console.log([
      `t=${String(samples.at(-1).t).padStart(5)}ms`,
      'glide      ',
      `y=${Number(state.y ?? 0).toFixed(2).padStart(7)}`,
      `x=${Number(state.x ?? 0).toFixed(2).padStart(8)}`,
      `vy=${Number(state.motion?.y ?? 0).toFixed(3).padStart(6)}`,
      `fallFlying=${state.fallFlying === true}`,
      `onGround=${state.onGround === true}`,
    ].join(' '))
  }
}

const after = await call('get_self')
await client.close()
const recording = {
  goal,
  port,
  startedAt: new Date(started).toISOString(),
  before,
  after,
  samples,
}
if (outRaw)
  writeFileSync(outRaw, JSON.stringify(recording, null, 2))
console.log('--- summary ---')
console.log(JSON.stringify({
  finalState: last.state,
  endReason: last.endReason,
  ticks: last.ticks,
  fireworksUsed: last.fireworksUsed,
  climb: last.climb,
  deployed: last.deployed,
  airborne: last.airborne,
  jumpReleased: last.jumpReleased,
  boostPressed: last.boostPressed,
  boostSeen: last.boostSeen,
  samples: samples.length,
  out: outRaw,
}, null, 2))
