import { writeFileSync } from 'node:fs'
import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js'

// E-01 calibration glide capture (elytra-navigation design §9, checklist B1).
//
// The live run is the only part of E-01 that cannot be rehearsed offline: it
// needs the Minecraft client, the bridge and a launch spot. This script does the
// machine half of it and leaves the human half (position the bot, watch the
// glide) to the runbook in RUNBOOK.md.
//
// What it does, in order:
//   1. Read the launch state. Abort when the bot already glides.
//   2. Aim at the requested yaw with pitch 0, hold the requested strafe key and
//      sprint until the bot is airborne.
//   3. Press jump once when the airborne bot's glider is closed.
//   4. Hold the requested yaw and pitch with no rockets and sample get_self as
//      fast as the bridge answers.
//   5. Stop the input and write the recording as JSON.
//
// The sprint holds BOTH a look direction and a movement key, and the key — not
// the look angle — decides the motion. Measured on the main test platform with
// `calibrate-direction.mjs` at yaw 270: forward = +x, back = -x, left = -z,
// right = +z. So "run west" is BACK at yaw 270, not left; earlier attempts used
// left (which runs north) and forward (which runs east) and never reached the
// drop.
//
// Usage: node capture-glide.mjs <outPath> [yaw] [pitch] [port] [strafe] [edgeX]
//   yaw/pitch in degrees (default 90 / -3; yaw 90 faces west at this site).
//   port is the client bridge (25600).
//   strafe is the key held while sprinting: left | right | forward | back
//   (default forward, which runs the way the bot faces).
//   edgeX is the x the bot must pass to be over the void (default 235.5, the
//   west rim of the main test platform); pass `none` to rely on descent alone.
// Run from a directory where @modelcontextprotocol/sdk resolves (checklist
// §1.3 has the node_modules junction trick).
const [outPath, yawRaw, pitchRaw, portRaw, strafeRaw, edgeXRaw] = process.argv.slice(2)
if (!outPath) {
  console.error('usage: node capture-glide.mjs <outPath> [yaw] [pitch] [port] [strafe] [edgeX]')
  process.exit(1)
}
const holdYaw = Number(yawRaw ?? 90)
const holdPitch = Number(pitchRaw ?? -3)
const port = Number(portRaw ?? 25600)
const strafe = strafeRaw ?? 'forward'
/** Port of the server-side bridge; `list_players` needs it (default 25602). */
const serverPort = Number(process.env.E01_SERVER_PORT ?? 25602)
if (!['forward', 'back', 'left', 'right'].includes(strafe)) {
  console.error(`strafe must be forward|back|left|right, got "${strafe}"`)
  process.exit(1)
}

const client = new Client({ name: 'e01-glide-capture', version: '1.0.0' })
await client.connect(new StreamableHTTPClientTransport(new URL(`http://127.0.0.1:${port}/mcp`)))
/** Server-side connection, opened on first server-tool call. */
let serverClient

function textOf(result) {
  return result.structuredContent && typeof result.structuredContent === 'object'
    ? result.structuredContent
    : JSON.parse((result.content?.find(block => block.type === 'text')?.text ?? '{}'))
}

async function call(name, args = {}, targetPort = port) {
  // `list_players` is a server tool; every other call in this script is
  // client-only. Open the second connection lazily so a client-only run never
  // pays for it.
  if (targetPort !== port) {
    if (!serverClient) {
      serverClient = new Client({ name: 'e01-glide-capture-server', version: '1.0.0' })
      await serverClient.connect(new StreamableHTTPClientTransport(new URL(`http://127.0.0.1:${targetPort}/mcp`)))
    }
    return textOf(await serverClient.callTool({ name, arguments: args }))
  }
  return textOf(await client.callTool({ name, arguments: args }))
}

/** Releases every key the capture pressed; safe to call on every exit path. */
async function releaseAll() {
  await call('stop_movement').catch(() => {})
}

const before = await call('get_self')
if (before.fallFlying === true) {
  console.error('bot is already gliding; land first')
  process.exit(1)
}

// Alive check against the server, not against the client. A dead bot keeps
// answering `get_self` (with health 20 from the client's own view) while the
// server holds `health: 0`, rejects every movement packet, and turns the sprint
// into a silent no-op. That cost a full debug cycle: the sprint reported
// `onGround: true, peakY = start y` with zero displacement.
try {
  const players = await call('list_players', {}, serverPort)
  const me = players?.players?.find(player => player.name === before.name)
  if (me && Number(me.health) <= 0) {
    console.error('bot is dead on the server (health 0); run respawn before capturing')
    process.exit(1)
  }
}
catch (error) {
  console.error(`could not verify aliveness on the server: ${error.message}`)
  process.exit(1)
}
console.log(`launch state: (${before.x}, ${before.y}, ${before.z}) onGround=${before.onGround}`)

// 1. Sprint off the edge. Pitch 0 keeps the run level until the edge, and the
// movement key — not the look angle — decides the motion.
//
// Jump is held with the sprint key, and every key stays held until the bot is
// genuinely FALLING, not merely airborne. This distinction is the whole launch
// problem on the main test platform:
//
//   - The west rim is a white-concrete row one block above the stone floor, so a
//     ground sprint stalls against it (x=237.30, horizontal motion 0); holding
//     jump carries the bot over the rim and off the drop.
//   - A plain hop also reports `onGround: false`, but its horizontal speed is
//     still near zero. Releasing the keys on that sample produced a 0.1 s
//     recording of a vertical hop with `motion.x = 0`.
//
// So the escape condition is sustained descent while past the edge:
// `onGround: false` with y dropping for FALL_CONFIRM_MS **and** the x position
// beyond the rim. A hop returns to the ground inside that window and keeps
// sprinting; a fall past the rim satisfies both. A pure descent test is not
// enough — the second half of every hop descends too.
//
// `edgeX` is the block column the bot must pass to be over the void (west of the
// main test platform's rim at x=236, so 235.5). Pass `none` to use descent alone
// on a platform whose edge geometry is unknown.
//
// The launch site is the orange-terracotta pad on the main test platform: stand
// on the orange glazed marker at (236, 201, -17) and run west along z=-17. That
// row is the clear lane; the neighbouring rows hold the moving-target fixtures
// (polished granite at x=240, z=-23/-24).
//
// The sprint must FACE the direction of travel, not merely move that way. The
// live facing probe (`facing-probe.mjs`) measured, at look yaw 270 on the main
// test platform:
//
//   forward  dx=+4.71  facing·movement=+1.00  (faces the way it runs)
//   back     dx=-4.92  facing·movement=-1.00  (runs BACKWARDS)
//   left/right         facing·movement= 0.00  (sideways)
//
// So yaw 270 faces east and `back` runs west: the first live attempts sent AIRI
// sliding toward the cliff tail-first, which is what the operator saw on screen.
// West is therefore yaw 90 with `forward`, which is the default here.
//
// (The same runs established that the movement key, not the look angle, decides
// the direction of travel, which is why the key is a parameter at all.)
const FALL_CONFIRM_MS = 100
const edgeX = Number.isFinite(Number(edgeXRaw)) ? Number(edgeXRaw) : undefined
const pastEdge = position => edgeX === undefined || position.x < edgeX

/**
 * Releases every run key and presses jump once.
 *
 * The release is not cosmetic: `deploy-from-edge.mjs` measured that the glider
 * never opens while the sprint holds `jump` (held through the fall, then pressed
 * again: still closed), and that the sprint cannot clear the pad's step without
 * holding it. So the launch is "hold to get out, release, press again", and every
 * attempt that kept the keys down ended in a fatal fall.
 */
async function releaseAndDeploy(current) {
  await releaseAll()
  await new Promise(resolve => setTimeout(resolve, 60))
  if (current.fallFlying !== true)
    await call('jump').catch(() => {})
  return current
}
const sprintInput = { forward: false, back: false, left: false, right: false, sprint: true, jump: true, [strafe]: true }
await call('look', { yaw: holdYaw, pitch: 0 })
await call('set_movement', sprintInput)
let self = await call('get_self')
const launchDeadline = Date.now() + 12_000
let airborneSince
let fallingSince
let lastY = self.y
let peakY = self.y
while (Date.now() < launchDeadline) {
  await new Promise(resolve => setTimeout(resolve, 30))
  self = await call('get_self')
  peakY = Math.max(peakY, self.y)
  const now = Date.now()
  if (self.onGround === false) {
    airborneSince ??= now
    // Descending means the hop's apex is behind and the bot is on its way down.
    if (self.y < lastY)
      fallingSince ??= now
    else
      fallingSince = undefined
    if (fallingSince !== undefined && now - fallingSince >= FALL_CONFIRM_MS && now - airborneSince >= FALL_CONFIRM_MS && pastEdge(self))
      break
  }
  else {
    airborneSince = undefined
    fallingSince = undefined
  }
  lastY = self.y
}
const launched = fallingSince !== undefined && self.onGround === false && pastEdge(self)
console.log(`after sprint: (${self.x.toFixed(2)}, ${self.y}, ${self.z.toFixed(2)}) onGround=${self.onGround} fallFlying=${self.fallFlying} peakY=${peakY.toFixed(2)} launched=${launched}`)
if (!launched) {
  await releaseAll()
  console.error(`never fell with movement=${strafe} at yaw=${holdYaw}; pick a real edge or another movement key`)
  process.exit(1)
}

// 2. Deploy: release every run key FIRST, then press jump. The release is what
// makes the press a new press (see `releaseAndDeploy`), and the horizontal speed
// that carried the bot off the rim survives the release long enough to matter.
if (self.fallFlying !== true) {
  await releaseAndDeploy(self)
  const deployDeadline = Date.now() + 600
  while (self.fallFlying !== true && Date.now() < deployDeadline) {
    await new Promise(resolve => setTimeout(resolve, 25))
    self = await call('get_self').catch(() => self)
  }
}
await releaseAll()
console.log(`deploy: onGround=${self.onGround} fallFlying=${self.fallFlying} y=${self.y.toFixed(2)}`)
if (self.fallFlying !== true) {
  console.error('glider did not deploy; recording would not be a glide')
  process.exit(1)
}

// 3. Glide: hold the calibration attitude with no movement input at all.
await call('look', { yaw: holdYaw, pitch: holdPitch })
const samples = []
const startedAt = Date.now()
let settled = 0
const glideDeadline = startedAt + 60_000
while (Date.now() < glideDeadline) {
  const at = Date.now()
  let state
  try {
    state = await call('get_self')
  }
  catch {
    break
  }
  samples.push({
    at,
    position: { x: state.x, y: state.y, z: state.z },
    motion: state.motion ?? { x: 0, y: 0, z: 0 },
    yaw: state.yaw,
    pitch: state.pitch,
  })
  if (state.fallFlying !== true) {
    settled += 1
    // Keep sampling briefly past the end so the tick marks near touch-down have
    // neighbours, then stop.
    if (settled >= 8)
      break
  }
  else {
    settled = 0
    // Re-assert the attitude each poll: look is a set-and-drift control.
    await call('look', { yaw: holdYaw, pitch: holdPitch })
  }
}
await releaseAll()

const recording = {
  schema: 'e01-glide-recording/v1',
  capturedAt: startedAt,
  inputs: { yaw: holdYaw, pitch: holdPitch, rockets: 0 },
  launch: { position: { x: before.x, y: before.y, z: before.z } },
  samples,
}
writeFileSync(outPath, `${JSON.stringify(recording, null, 2)}\n`)
const span = (samples.at(-1)?.at ?? startedAt) - startedAt
const cadence = span / Math.max(1, samples.length - 1)
console.log(`recorded ${samples.length} samples over ${(span / 1000).toFixed(1)}s -> ${outPath}`)
console.log(`cadence ~${cadence.toFixed(1)}ms per sample; the audit needs a sample within 100ms of ticks 10/20/40`)
process.exit(0)
