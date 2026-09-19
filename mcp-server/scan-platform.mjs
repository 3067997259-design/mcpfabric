import { writeFileSync } from 'node:fs'
import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js'

// Scans the main test platform: reads one y slice over a wide window and prints
// the horizontal extent, the rim material and a coarse block map.
//
// Two endpoints are needed: the player state read is client-only (`get_self`),
// and the block read is SERVER_FIRST — a client-side call answers
// `no_server` because there is no integrated server. Hence `botPort` and
// `regionPort`.
//
// Usage: node scan-platform.mjs <y> <half> [outPath] [botPort] [regionPort]
//   y     : the y level to read (default 200)
//   half  : half-extent around the bot in blocks (default 64)
const [yRaw, halfRaw, outPath, botPortRaw, regionPortRaw] = process.argv.slice(2)
const level = Number(yRaw ?? 200)
const half = Number(halfRaw ?? 64)
const botPort = Number(botPortRaw ?? 25600)
const regionPort = Number(regionPortRaw ?? 25602)

async function connect(port, name) {
  const client = new Client({ name, version: '1.0.0' })
  await client.connect(new StreamableHTTPClientTransport(new URL(`http://127.0.0.1:${port}/mcp`)))
  return client
}
const bot = await connect(botPort, 'platform-scan-bot')
const world = await connect(regionPort, 'platform-scan-world')

function textOf(result) {
  return result.structuredContent && typeof result.structuredContent === 'object'
    ? result.structuredContent
    : JSON.parse((result.content ?? []).find(part => part.type === 'text')?.text ?? '{}')
}

async function call(client, name, args = {}) {
  const result = await client.callTool({ name, arguments: args })
  if (result.isError === true)
    throw new Error(textOf(result).text ?? `tool ${name} failed`)
  return textOf(result)
}

const self = await call(bot, 'get_self')
const cx = Math.floor(self.x)
const cz = Math.floor(self.z)
console.log(`self: (${self.x.toFixed(2)}, ${self.y}, ${self.z.toFixed(2)}) ${self.name} ${self.dimension}`)

const from = { x: cx - half, y: level, z: cz - half }
const to = { x: cx + half, y: level, z: cz + half }
const region = await call(world, 'get_blocks_region', { from, to })
const blocks = region.blocks ?? []
console.log(`read ${blocks.length} cells at y=${level} over ${half * 2 + 1}^2 (window x ${from.x}..${to.x}, z ${from.z}..${to.z})`)

const ids = new Map()
const occupied = []
let minX = Infinity; let maxX = -Infinity; let minZ = Infinity; let maxZ = -Infinity
for (const b of blocks) {
  ids.set(b.id, (ids.get(b.id) ?? 0) + 1)
  if (b.id === 'minecraft:air')
    continue
  occupied.push(b)
  minX = Math.min(minX, b.x); maxX = Math.max(maxX, b.x)
  minZ = Math.min(minZ, b.z); maxZ = Math.max(maxZ, b.z)
}

console.log('--- block ids at this level ---')
for (const [id, count] of [...ids.entries()].sort((a, b) => b[1] - a[1]))
  console.log(`  ${count.toString().padStart(6)}  ${id}`)

console.log('--- occupied extent ---')
console.log(`  x ${minX}..${maxX}  (${maxX - minX + 1} wide)`)
console.log(`  z ${minZ}..${maxZ}  (${maxZ - minZ + 1} deep)`)

// Row/column profiles: the run of non-air per line, as a coarse ASCII map.
const byXZ = new Map(occupied.map(b => [`${b.x},${b.z}`, b.id]))
const legend = { 'minecraft:white_concrete': 'W', 'minecraft:stone': 'S', 'minecraft:smooth_stone': 'S', 'minecraft:air': '.' }
const mapMinX = Math.max(minX - 2, cx - 34)
const mapMaxX = Math.min(maxX + 2, cx + 34)
console.log(`--- map (W=white concrete, S=stone, ?=other, .=air/absent), x ${mapMinX}..${mapMaxX} ---`)
for (let z = minZ - 2; z <= maxZ + 2; z++) {
  let line = ''
  for (let x = mapMinX; x <= mapMaxX; x++) {
    const id = byXZ.get(`${x},${z}`)
    line += id === undefined ? (x >= minX && x <= maxX && z >= minZ && z <= maxZ ? '.' : ' ') : (legend[id] ?? '?')
  }
  console.log(`  z=${String(z).padStart(5)} ${line}`)
}

// Exact extent of the platform surface itself: the stone floor plus its rim.
const surface = occupied.filter(b => b.id === 'minecraft:stone' || b.id === 'minecraft:white_concrete')
const surfaceIds = new Set(['minecraft:stone', 'minecraft:white_concrete'])
const sx = surface.map(b => b.x)
const sz = surface.map(b => b.z)
const platform = {
  minX: Math.min(...sx), maxX: Math.max(...sx),
  minZ: Math.min(...sz), maxZ: Math.max(...sz),
}
console.log('--- platform surface extent (stone + white concrete at this level) ---')
console.log(`  x ${platform.minX}..${platform.maxX}  (${platform.maxX - platform.minX + 1})`)
console.log(`  z ${platform.minZ}..${platform.maxZ}  (${platform.maxZ - platform.minZ + 1})`)
console.log(`  cells ${surface.length} of ${(platform.maxX - platform.minX + 1) * (platform.maxZ - platform.minZ + 1)} in the bounding box`)

// Holes: bounding-box cells with no surface block, which are not a solid pad.
const holes = []
for (let x = platform.minX; x <= platform.maxX; x++) {
  for (let z = platform.minZ; z <= platform.maxZ; z++) {
    if (!surfaceIds.has(byXZ.get(`${x},${z}`) ?? ''))
      holes.push(`${x},${z}`)
  }
}
console.log(`  non-surface cells inside the box: ${holes.length}${holes.length ? ` e.g. ${holes.slice(0, 12).join(' ')}` : ''}`)

// Read window coverage: the window is centred on the bot, so a platform wider
// than the half-extent is clipped and the numbers above are a lower bound.
console.log('--- coverage ---')
console.log(`  window x ${from.x}..${to.x}, z ${from.z}..${to.z}`)
const clipped = platform.minX <= from.x || platform.maxX >= to.x || platform.minZ <= from.z || platform.maxZ >= to.z
console.log(`  clipped by the read window: ${clipped ? 'YES — the extent above is a lower bound' : 'no'}`)

if (outPath) {
  writeFileSync(outPath, JSON.stringify({ self, level, half, ids: Object.fromEntries(ids), platform, holes: holes.length, clipped }, null, 2))
  console.log(`wrote ${outPath}`)
}
await bot.close()
await world.close()
