/**
 * One acceptance walk for the movement corridor work.
 *
 * Teleports the bot to the start, submits `game_move_to` through the AIRI app
 * (CDP), samples the player through the client bridge while it runs, then
 * prints the receipt plus trajectory statistics (duration, path length,
 * lateral deviation, stalls, perpendicular jitter).
 *
 * Usage: node accept-move.mjs <startX> <startZ> <endX> <endZ> [tolerance] [label]
 */
import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js'
import { mkdirSync, readFileSync, statSync, writeFileSync } from 'node:fs'

const [startXRaw, startZRaw, endXRaw, endZRaw, tolRaw, labelRaw, startYRaw, goalYRaw] = process.argv.slice(2)
const start = { x: Number(startXRaw), z: Number(startZRaw) }
const end = { x: Number(endXRaw), z: Number(endZRaw) }
const tolerance = Number(tolRaw ?? 1)
const label = labelRaw ?? `move-${start.x}-${start.z}-${end.x}-${end.z}`
const startY = Number(startYRaw ?? 75)
const goalY = Number(goalYRaw ?? 75)

const server = new Client({ name: 'accept-server', version: '1.0.0' })
await server.connect(new StreamableHTTPClientTransport(new URL('http://127.0.0.1:25602/mcp')))
const game = new Client({ name: 'accept-game', version: '1.0.0' })
await game.connect(new StreamableHTTPClientTransport(new URL('http://127.0.0.1:25600/mcp')))

const targets = await (await fetch('http://127.0.0.1:9222/json/list')).json()
const target = targets.find(item => item.type === 'page' && item.url.includes('synced-leader=true'))
if (!target)
  throw new Error('AIRI leader window not found')
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

const teleport = await server.callTool({
  name: 'teleport_player',
  arguments: { player: 'airitest', x: start.x + 0.5, y: startY, z: start.z + 0.5, yaw: 0, pitch: 0 },
})
console.log('[teleport]', teleport.isError ? (teleport.content ?? []).map(part => part.text).join('') : 'ok')
// Keep hostile mobs and damage out of the movement sample.
await server.callTool({ name: 'run_command', arguments: { command: 'kill @e[type=!minecraft:player,distance=..64]' } })
await server.callTool({ name: 'run_command', arguments: { command: 'effect give airitest minecraft:instant_health 1 10 true' } })
await server.callTool({ name: 'run_command', arguments: { command: 'effect give airitest minecraft:saturation 1 10 true' } })
await new Promise(resolve => setTimeout(resolve, 1500))

// Capture the AIRI log offset so the plan path of THIS run can be parsed.
const airiLogPath = `${process.env.TEMP}\\airi-preview8.log`
const logOffset = statSync(airiLogPath).size

// The probe lives on the devtools route; a fresh app start lands on `/`.
await evalJs(`location.hash = '#/devtools/game-host'; 'ok'`)
await new Promise(resolve => setTimeout(resolve, 2500))
for (let attempt = 0; attempt < 10; attempt++) {
  const ready = await evalJs('window.__AIRI_GAME_HOST_SMOKE__ ? "ready" : "missing"')
  if (ready === 'ready')
    break
  await new Promise(resolve => setTimeout(resolve, 1000))
}

const payload = JSON.stringify({ x: end.x, y: goalY, z: end.z, tolerance, allowPlace: false })
await evalJs(`window.__moveResult = 'pending'
window.__AIRI_GAME_HOST_SMOKE__.executeGameTool('game_move_to', ${payload})
  .then(result => { window.__moveResult = result })
  .catch(error => { window.__moveResult = { error: String(error) } })
'started'`)

const samples = []
const startedAt = Date.now()
let receipt
for (;;) {
  const state = await game.callTool({ name: 'get_self', arguments: {} }).then(result => result.structuredContent)
  samples.push({
    t: Date.now() - startedAt,
    x: state.x,
    y: state.y,
    z: state.z,
    yaw: state.yaw,
    motion: state.motion,
  })
  const current = await evalJs('typeof window.__moveResult === "string" ? window.__moveResult : JSON.stringify(window.__moveResult)')
  if (current !== 'pending') {
    receipt = JSON.parse(current)
    break
  }
  if (Date.now() - startedAt > 120_000) {
    receipt = { error: 'timeout waiting for AIRI command' }
    break
  }
  await new Promise(resolve => setTimeout(resolve, 150))
}
ws.close()

// The plan path this run executed (logged by the executor before the walk).
let planPath
try {
  const tail = readFileSync(airiLogPath, 'utf8').slice(logOffset)
  const matches = [...tail.matchAll(/plan path: (\[\[.*?\]\])/g)]
  if (matches.length > 0)
    planPath = JSON.parse(matches[matches.length - 1][1])
}
catch {}

function distanceToPolyline(x, z, polyline) {
  let best = Number.POSITIVE_INFINITY
  for (let index = 1; index < polyline.length; index++) {
    const [ax, , az] = polyline[index - 1]
    const [bx, , bz] = polyline[index]
    const abx = bx - ax
    const abz = bz - az
    const lengthSq = abx * abx + abz * abz
    const t = lengthSq <= 1e-12 ? 0 : Math.max(0, Math.min(1, ((x - ax) * abx + (z - az) * abz) / lengthSq))
    best = Math.min(best, Math.hypot(x - (ax + abx * t), z - (az + abz * t)))
  }
  return best
}

let maxPathLateral
let pathLateralBreaches
if (planPath && planPath.length >= 2) {
  maxPathLateral = 0
  pathLateralBreaches = 0
  for (let index = 2; index < samples.length; index++) {
    const distance = distanceToPolyline(samples[index].x, samples[index].z, planPath)
    maxPathLateral = Math.max(maxPathLateral, distance)
    if (distance > 0.6)
      pathLateralBreaches++
  }
}

const first = samples[0]
const last = samples[samples.length - 1]
const dx = end.x + 0.5 - (start.x + 0.5)
const dz = end.z + 0.5 - (start.z + 0.5)
const straight = Math.hypot(dx, dz)
let path = 0
let maxLateral = 0
let stalls = 0
let stallEpisodes = 0
let inStall = false
let reversals = 0
let previousPerp = undefined
let yawTravel = 0
for (let index = 0; index < samples.length; index++) {
  const sample = samples[index]
  if (index > 0) {
    const previous = samples[index - 1]
    path += Math.hypot(sample.x - previous.x, sample.z - previous.z)
    yawTravel += Math.abs(((sample.yaw - previous.yaw + 540) % 360) - 180)
    const speed = Math.hypot(sample.motion?.x ?? 0, sample.motion?.z ?? 0)
    const stalled = index > 2 && index < samples.length - 2 && speed < 0.02
    if (stalled) {
      stalls++
      if (!inStall) {
        stallEpisodes++
        inStall = true
      }
    }
    else {
      inStall = false
    }
  }
  const along = straight > 1e-6 ? ((sample.x - (start.x + 0.5)) * dx + (sample.z - (start.z + 0.5)) * dz) / straight : 0
  const perpendicular = straight > 1e-6 ? ((sample.x - (start.x + 0.5)) * dz - (sample.z - (start.z + 0.5)) * dx) / straight : 0
  maxLateral = Math.max(maxLateral, Math.abs(perpendicular))
  if (previousPerp !== undefined && Math.abs(perpendicular - previousPerp) > 1e-4) {
    if (previousPerp !== 0 && Math.sign(perpendicular) !== Math.sign(previousPerp))
      reversals++
    previousPerp = perpendicular
  }
  else if (previousPerp === undefined) {
    previousPerp = perpendicular
  }
}

const summary = {
  label,
  start: { x: start.x + 0.5, z: start.z + 0.5 },
  end: { x: end.x + 0.5, z: end.z + 0.5 },
  straightDistance: Number(straight.toFixed(2)),
  endReason: receipt?.endReason ?? receipt?.error ?? receipt?.result?.endReason,
  checked: receipt?.checked,
  finalSnapshot: receipt?.finalSnapshot ?? receipt?.result?.finalSnapshot,
  samples: samples.length,
  durationMs: last.t,
  pathLength: Number(path.toFixed(2)),
  pathRatio: Number((path / Math.max(1e-6, straight)).toFixed(3)),
  maxLateral: Number(maxLateral.toFixed(3)),
  stalls,
  stallEpisodes,
  perpendicularReversals: reversals,
  yawTravelDeg: Number(yawTravel.toFixed(1)),
  planCells: planPath?.length,
  maxPathLateral: maxPathLateral === undefined ? undefined : Number(maxPathLateral.toFixed(3)),
  pathLateralBreaches,
}
console.log(JSON.stringify(summary, null, 2))

mkdirSync('tmp-acceptance/evidence', { recursive: true })
writeFileSync(`tmp-acceptance/evidence/${label}.json`, JSON.stringify({ summary, receipt, samples }, null, 2))
await game.close()
await server.close()
