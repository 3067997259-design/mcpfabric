/**
 * Finds a flat launch pad inside one terrain box.
 *
 * Reads the box in one coverage-aware call and reports, for every candidate
 * 3x3 patch, the surface height spread and the proven clearance above it. OV-5's
 * flat takeoff needs level ground and open sky, and a single column profile
 * cannot prove either: a 1-block step makes the stand point ambiguous and the
 * scan has to see the air above the patch, not just the ground below it.
 *
 * Usage: node flat-pad.mjs <x0> <x1> <z0> <z1> <bottomY> <topY> [port] [needClearance]
 */
import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js'

const [x0Raw, x1Raw, z0Raw, z1Raw, bottomYRaw, topYRaw, portRaw, needRaw] = process.argv.slice(2)
const x0 = Number(x0Raw)
const x1 = Number(x1Raw)
const z0 = Number(z0Raw)
const z1 = Number(z1Raw)
const bottomY = Number(bottomYRaw ?? 120)
const topY = Number(topYRaw ?? 155)
const port = Number(portRaw ?? 25602)
const needClearance = Number(needRaw ?? 8)

const client = new Client({ name: 'flat-pad', version: '1.0.0' })
await client.connect(new StreamableHTTPClientTransport(new URL(`http://127.0.0.1:${port}/mcp`)))

const result = await client.callTool({
  name: 'get_blocks_region',
  arguments: { from: { x: x0, y: bottomY, z: z0 }, to: { x: x1, y: topY, z: z1 }, includeAir: true },
})
const record = result.structuredContent ?? JSON.parse((result.content ?? []).find(p => p.type === 'text')?.text ?? '{}')
const blocks = record.blocks ?? []
const isAir = id => !id || id === 'minecraft:air' || id === 'minecraft:cave_air' || id === 'minecraft:void_air'
const cells = new Map()
for (const block of blocks)
  cells.set(`${block.x},${block.y},${block.z}`, block.id)

/** Highest solid block in a column, walking down from the read's top. */
function surfaceAt(x, z) {
  let sawCell = false
  for (let y = topY; y >= bottomY; y--) {
    const id = cells.get(`${x},${y},${z}`)
    if (id === undefined)
      continue
    sawCell = true
    if (!isAir(id))
      return y
  }
  return sawCell ? undefined : null
}

/** Air blocks proven above `surface`, stopping at the first solid or unread cell. */
function clearanceAt(x, surface, z) {
  for (let step = 1; step <= needClearance + 2; step++) {
    const id = cells.get(`${x},${surface + step},${z}`)
    if (id === undefined || !isAir(id))
      return step - 1
    if (step === needClearance + 2)
      return step
  }
  return 0
}

const pads = []
for (let x = x0; x <= x1 - 2; x++) {
  for (let z = z0; z <= z1 - 2; z++) {
    const heights = []
    let clearance = Infinity
    let ok = true
    let support
    for (let dx = 0; dx < 3 && ok; dx++) {
      for (let dz = 0; dz < 3 && ok; dz++) {
        const y = surfaceAt(x + dx, z + dz)
        if (y === undefined || y === null) {
          ok = false
          break
        }
        heights.push(y)
        support = support ?? cells.get(`${x + dx},${y},${z + dz}`)
        clearance = Math.min(clearance, clearanceAt(x + dx, y, z + dz))
      }
    }
    if (!ok)
      continue
    const min = Math.min(...heights)
    const max = Math.max(...heights)
    pads.push({ x, z, y: max, spread: max - min, clearance, support })
  }
}

pads.sort((a, b) => (a.spread - b.spread) || (b.clearance - a.clearance))
console.log(`cells=${cells.size} blocks=${blocks.length} candidates=${pads.length}`)
for (const pad of pads.filter(p => p.spread === 0 && p.clearance >= needClearance).slice(0, 12))
  console.log(`pad x=${pad.x}..${pad.x + 2} z=${pad.z}..${pad.z + 2} y=${pad.y} spread=${pad.spread} clearance=${pad.clearance} support=${pad.support}`)
console.log('--- flattest regardless of clearance ---')
for (const pad of pads.slice(0, 6))
  console.log(`pad x=${pad.x} z=${pad.z} y=${pad.y} spread=${pad.spread} clearance=${pad.clearance} support=${pad.support}`)
await client.close()
