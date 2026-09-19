/**
 * Measures the clearance under a ceiling slab, column by column.
 *
 * The fixture's obsidian roof is 29x31 blocks, so "how high is the passage" is a
 * per-column fact, not one number: this prints, for every (x, z), the gap
 * between the slab's underside and the highest solid below it, and the layer
 * histogram, so the navigable slot is visible without trusting a description.
 *
 * Usage: node ceiling-gap.mjs <x0> <x1> <z0> <z1> <bottomY> <topY> [serverPort] [id]
 */
import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js'

const [x0Raw, x1Raw, z0Raw, z1Raw, bottomRaw, topRaw, portRaw, idRaw] = process.argv.slice(2)
const x0 = Number(x0Raw)
const x1 = Number(x1Raw)
const z0 = Number(z0Raw)
const z1 = Number(z1Raw)
const bottomY = Number(bottomRaw ?? 60)
const topY = Number(topRaw ?? 84)
const port = Number(portRaw ?? 25602)
const ceilingId = idRaw ?? 'minecraft:obsidian'

const client = new Client({ name: 'ceiling-gap', version: '1.0.0' })
await client.connect(new StreamableHTTPClientTransport(new URL(`http://127.0.0.1:${port}/mcp`)))
const read = await client.callTool({
  name: 'get_blocks_region',
  arguments: { from: { x: x0, y: bottomY, z: z0 }, to: { x: x1, y: topY, z: z1 }, includeAir: true },
})
const record = read.structuredContent ?? JSON.parse((read.content ?? []).find(p => p.type === 'text')?.text ?? '{}')
const blocks = record.blocks ?? []
console.log(`volume ${record.volume ?? '?'} cells ${blocks.length}`)
if (blocks.length === 0) {
  console.log('EMPTY READ — chunks not loaded')
  process.exit(0)
}

const isAir = id => !id || id.endsWith('air')
const columns = new Map()
for (const block of blocks) {
  const key = `${block.x},${block.z}`
  const column = columns.get(key) ?? []
  column.push(block)
  columns.set(key, column)
}

const histogram = new Map()
const rows = []
for (const [key, column] of columns) {
  const [x, z] = key.split(',').map(Number)
  const sorted = column.sort((a, b) => a.y - b.y)
  const ceiling = sorted.find(block => block.id === ceilingId)
  if (!ceiling) {
    rows.push({ x, z, gap: undefined, ceilingY: undefined, floorY: undefined })
    continue
  }
  const below = sorted.filter(block => block.y < ceiling.y && !isAir(block.id))
  const floor = below.at(-1)
  const gap = ceiling.y - (floor ? floor.y + 1 : bottomY)
  rows.push({ x, z, gap, ceilingY: ceiling.y, floorY: floor?.y })
  histogram.set(gap, (histogram.get(gap) ?? 0) + 1)
}

console.log('--- gap histogram (blocks of air between floor and ceiling) ---')
for (const [gap, count] of [...histogram.entries()].sort((a, b) => a[0] - b[0]))
  console.log(`  gap ${String(gap).padStart(3)} : ${count} columns`)

const withCeiling = rows.filter(row => row.gap !== undefined)
const min = Math.min(...withCeiling.map(row => row.gap))
const atMin = withCeiling.filter(row => row.gap === min)
console.log(`--- narrowest ${min} block(s): ${atMin.length} columns ---`)
console.log(`  x ${Math.min(...atMin.map(r => r.x))}..${Math.max(...atMin.map(r => r.x))}  z ${Math.min(...atMin.map(r => r.z))}..${Math.max(...atMin.map(r => r.z))}`)
for (const row of atMin.slice(0, 12))
  console.log(`  (${row.x}, ${row.z}) floor y=${row.floorY} ceiling y=${row.ceilingY} gap ${row.gap}`)

console.log('--- gap by z row (x from west to east) ---')
const zs = [...new Set(rows.map(row => row.z))].sort((a, b) => a - b)
for (const z of zs) {
  const line = rows.filter(row => row.z === z).sort((a, b) => a.x - b.x)
    .map(row => row.gap === undefined ? ' .' : String(row.gap).padStart(2)).join('')
  console.log(`  z${String(z).padStart(4)} ${line}`)
}
await client.close()
