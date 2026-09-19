import { writeFileSync } from 'node:fs'
import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js'

// E-01 scripted-input capture: steady glide plus ONE planned manoeuvre.
//
// The plain glide capture holds a constant attitude, which covers only the pitch
// dimension. Design §9 also requires turning and rocket propulsion, and both need
// an input that changes during the glide:
//
//   --mode turn   re-issues `look` every poll so yaw sweeps at a fixed rate
//   --mode boost  fires one firework rocket at a chosen tick, after a steady lead-in
//
// Everything else matches `capture-glide.mjs`, including the launch sequence that
// took several live attempts to get right: sprint FACE-FIRST (west is yaw 90 with
// `forward`), hold jump to clear the pad's step and the rim, then release every
// key before the single space press that opens the glider.
//
// Usage:
//   node capture-maneuver.mjs <outPath> --mode turn  --yaw-rate <deg/s> [--pitch -3] [--seconds 40]
//   node capture-maneuver.mjs <outPath> --mode boost --boost-tick <n>   [--pitch -3] [--seconds 50]
// Options: --port 25600, --server-port 25602, --hotbar <slot> (rocket slot, boost only)
const argv = process.argv.slice(2)
const outPath = argv[0]
if (!outPath || outPath.startsWith('--')) {
  console.error('usage: node capture-maneuver.mjs <outPath> --mode turn|boost [...]')
  process.exit(1)
}
function option(name, fallback) {
  const index = argv.indexOf(`--${name}`)
  return index === -1 ? fallback : argv[index + 1]
}
const mode = option('mode')
if (mode !== 'turn' && mode !== 'boost') {
  console.error(`--mode must be turn or boost, got "${mode}"`)
  process.exit(1)
}
const port = Number(option('port', 25600))
const serverPort = Number(option('server-port', 25602))
const basePitch = Number(option('pitch', -3))
const seconds = Number(option('seconds', mode === 'boost' ? 50 : 40))
const yawRate = Number(option('yaw-rate', 10))
const boostTick = Number(option('boost-tick', 40))
const hotbarSlot = Number(option('hotbar', 0))
/**
 * Ticks of lead-in before the burn, in boost mode.
 *
 * The firework boost lives 10 ticks, and a rocket accelerates along the look
 * direction — at this heading that is horizontal speed, not climb. A first boost
 * capture fired 40 ticks after the deploy and then started sampling, so the whole
 * window sat inside the burn and no onset was visible. Waiting past the window
 * first gives the audit a settled glide on both sides of one burn.
 */
const boostLeadTicks = Number(option('boost-lead', 120))
/** Launch attitude and lane, measured on the main test platform (RUNBOOK §2). */
const LAUNCH_YAW = 90
const EDGE_X = 235.5

const bot = new Client({ name: 'e01-maneuver-capture', version: '1.0.0' })
await bot.connect(new StreamableHTTPClientTransport(new URL(`http://127.0.0.1:${port}/mcp`)))
let world

function textOf(result) {
  const structured = result.structuredContent
  if (structured && typeof structured === 'object')
    return structured
  return JSON.parse((result.content ?? []).find(part => part.type === 'text')?.text ?? '{}')
}
async function call(name, args = {}) {
  const result = await bot.callTool({ name, arguments: args })
  if (result.isError === true)
    throw new Error(textOf(result).text ?? `tool ${name} failed`)
  return textOf(result)
}
async function callWorld(name, args = {}) {
  world ??= await (async () => {
    const client = new Client({ name: 'e01-maneuver-world', version: '1.0.0' })
    await client.connect(new StreamableHTTPClientTransport(new URL(`http://127.0.0.1:${serverPort}/mcp`)))
    return client
  })()
  const result = await world.callTool({ name, arguments: args })
  if (result.isError === true)
    throw new Error(textOf(result).text ?? `tool ${name} failed`)
  return textOf(result)
}
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const releaseAll = () => call('stop_movement').catch(() => {})

/** Rocket count in the hotbar, plus the slot holding it. -1 count means none. */
async function countRockets() {
  const inventory = await call('get_inventory')
  const slot = (inventory.hotbar ?? []).find(entry => entry?.id === 'minecraft:firework_rocket')
  return { count: slot?.count ?? -1, slot: slot?.slot ?? -1 }
}

/**
 * Selects `slot` and confirms it took effect.
 *
 * `select_hotbar_slot` reported success while the inventory kept reporting
 * another slot selected, so the caller must not trust its return value.
 */
async function selectRocketSlot(slot) {
  for (let attempt = 0; attempt < 3; attempt++) {
    await call('select_hotbar_slot', { slot }).catch(() => {})
    await sleep(80)
    const inventory = await call('get_inventory')
    if (inventory.selectedSlot === slot)
      return true
  }
  return false
}

// Alive check against the server: a dead bot still answers `get_self` with
// health 20 while the server rejects every movement packet, which turns the
// launch into a silent no-op.
const before = await call('get_self')
const players = await callWorld('list_players')
const me = players.players?.find(player => player.name === before.name)
if (!me || Number(me.health) <= 0) {
  console.error(`bot is not alive on the server (health ${me?.health}); respawn and teleport first`)
  process.exit(1)
}
const equipment = await call('get_equipment')
if (equipment.chest?.id !== 'minecraft:elytra') {
  console.error(`no elytra on the chest slot (got ${equipment.chest?.id ?? 'empty'})`)
  process.exit(1)
}
console.log(`launch state: (${before.x}, ${before.y}, ${before.z}) elytra damage=${equipment.chest.damage ?? 0}/${equipment.chest.maxDamage ?? '?'}`)

// 1. Run west off the pad and clear the rim, holding jump (auto-jump).
await call('look', { yaw: LAUNCH_YAW, pitch: 0 })
await call('set_movement', { forward: true, back: false, left: false, right: false, sprint: true, jump: true })
let self = await call('get_self')
const launchDeadline = Date.now() + 12_000
let airborneSince
let fallingSince
let lastY = self.y
while (Date.now() < launchDeadline) {
  await sleep(30)
  self = await call('get_self')
  const now = Date.now()
  if (self.onGround === false) {
    airborneSince ??= now
    if (self.y < lastY)
      fallingSince ??= now
    else
      fallingSince = undefined
    if (fallingSince !== undefined && now - fallingSince >= 100 && now - airborneSince >= 100 && self.x < EDGE_X)
      break
  }
  else {
    airborneSince = undefined
    fallingSince = undefined
  }
  lastY = self.y
}
if (self.onGround !== false || self.x >= EDGE_X) {
  await releaseAll()
  console.error(`never cleared the edge (x=${self.x}, onGround=${self.onGround})`)
  process.exit(1)
}

// 2. Release every key, then press jump once: a held key is not a new press, and
// the glider only opens on a new press.
await releaseAll()
await sleep(60)
if (self.fallFlying !== true)
  await call('jump')
const deployDeadline = Date.now() + 600
while (self.fallFlying !== true && Date.now() < deployDeadline) {
  await sleep(25)
  self = await call('get_self')
}
if (self.fallFlying !== true) {
  await releaseAll()
  console.error('glider did not deploy')
  process.exit(1)
}
const deployedAt = Date.now()
console.log(`deployed at y=${self.y.toFixed(2)} x=${self.x.toFixed(2)}`)

// 3. Sample the manoeuvre. One `look` per poll keeps the attitude on plan; the
// recorded yaw/pitch per sample let the audit fit what was actually commanded.
await call('look', { yaw: LAUNCH_YAW, pitch: basePitch })
const samples = []
const startedAt = Date.now()
let boostFiredAt
let boostFiredTick
const deadline = startedAt + seconds * 1000
while (Date.now() < deadline) {
  const at = Date.now()
  const elapsedSeconds = (at - startedAt) / 1000
  let state
  try {
    state = await call('get_self')
  }
  catch {
    break
  }
  samples.push({
    at,
    tick: samples.length,
    position: { x: state.x, y: state.y, z: state.z },
    motion: state.motion ?? { x: 0, y: 0, z: 0 },
    yaw: state.yaw,
    pitch: state.pitch,
  })
  if (state.fallFlying !== true)
    break

  if (mode === 'turn') {
    // Yaw sweeps at a fixed rate from the launch heading.
    await call('look', { yaw: LAUNCH_YAW + yawRate * elapsedSeconds, pitch: basePitch })
  }
  else if (boostFiredAt === undefined && samples.length >= boostLeadTicks) {
    // One rocket. `select_hotbar_slot` answers `{"ok":true,"message":"selected
    // slot 5"}` while the inventory still reports slot 0, and `use_item` answers
    // `{"result":"PASS"}` while spending nothing — so both steps are verified
    // against a fresh read instead of their own return value. A boost capture
    // that trusted them recorded 28 seconds of plain glide with 64 rockets still
    // in the bag.
    const before = await countRockets()
    await selectRocketSlot(before.slot)
    await call('use_item')
    await sleep(150)
    const after = await countRockets()
    if (after.count < before.count) {
      boostFiredAt = Date.now()
      boostFiredTick = samples.length
      console.log(`fired a rocket at tick ${boostFiredTick} (y=${state.y.toFixed(2)}); rockets ${before.count} -> ${after.count}`)
    }
    else {
      console.warn(`rocket did not fire at tick ${samples.length}; rockets still ${after.count} (slot ${before.slot})`)
    }
  }
  // The poll cadence is what sets the sample count per tick; the audit dedupes
  // repeated reads of one tick, so no sleep is needed here.
}
await releaseAll()

const recording = {
  schema: 'e01-glide-recording/v1',
  mode,
  capturedAt: startedAt,
  deployedAt,
  inputs: {
    yaw: LAUNCH_YAW,
    pitch: basePitch,
    rockets: boostFiredAt === undefined ? 0 : 1,
    ...(mode === 'turn' ? { yawRatePerSecond: yawRate } : {}),
    ...(mode === 'boost' ? { boostTick, boostLeadTicks } : {}),
  },
  ...(boostFiredAt !== undefined ? { boost: { at: boostFiredAt, tick: boostFiredTick } } : {}),
  samples,
}
writeFileSync(outPath, `${JSON.stringify(recording, null, 2)}\n`)
const span = (samples.at(-1)?.at ?? startedAt) - startedAt
console.log(`recorded ${samples.length} samples over ${(span / 1000).toFixed(1)}s -> ${outPath}`)
console.log(`cadence ~${(span / Math.max(1, samples.length - 1)).toFixed(1)}ms per sample`)
if (world)
  await world.close()
await bot.close()
process.exit(0)
