/**
 * Prints the air corridor of a cave, z-slice by z-slice.
 *
 * A tunnel is not a box: the useful fact is where the open cells are at each
 * depth, and whether the passage stays wide/tall enough along the way. Each
 * slice prints, per y layer, the x runs of air cells inside the read box.
 *
 * Usage: node cave-profile.mjs <x0> <x1> <z0> <z1> <bottomY> <topY> [serverPort] [step]
 */
import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js'

const [x0Raw, x1Raw, z0Raw, z1Raw, bottomRaw, topRaw, portRaw, stepRaw] = process.argv.slice(2)
const x0 = Math.min(Number(x0Raw), Number(x1Raw))
const x1 = Math.max(Number(x0Raw), Number(x1Raw))
const z0 = Math.min(Number(z0Raw), Number(z1Raw))
const z1 = Math.max(Number(z0Raw), Number(z1Raw))
const bottomY = Number(bottomRaw ?? 60)
const topY = Number(topRaw ?? 76)
const port = Number(portRaw ?? 25602)
const step = Number(stepRaw ?? 2)

const client = new Client({ name: 'cave-profile', version: '1.0.0' })
await client.connect(new StreamableHTTPClientTransport(new URL(`http://127.0.0.1:${port}/mcp`)))
const read = await client.callTool({
  name: 'get_blocks_region',
  arguments: { from: { x: x0, y: bottomY, z: z0 }, to: { x: x1, y: topY, z: z1 }, includeAir: true },
})
const record = read.structuredContent ?? JSON.parse((read.content ?? []).find(p => p.type === 'text')?.text ?? '{}')
const blocks = record.blocks ?? []
console.log(`volume ${record.volume ?? '?'} cells ${blocks.length} (x ${x0}..${x1}, y ${bottomY}..${topY}, z ${z0}..${z1})`)
if (blocks.length === 0) {
  console.log('EMPTY READ — chunks not loaded')
  process.exit(0)
}

const isAir = id => !id || id.endsWith('air')
const bySlice = new Map()
for (const block of blocks) {
  if (!isAir(block.id))
    continue
  const list = bySlice.get(block.z) ?? []
  list.push(block)
  bySlice.set(block.z, list)
}

function runs(xs) {
  const sorted = [...new Set(xs)].sort((a, b) => a - b)
  const out = []
  let start = sorted[0]
  let previous = sorted[0]
  for (const x of sorted.slice(1)) {
    if (x === previous + 1) {
      previous = x
      continue
    }
    out.push(start === previous ? `${start}` : `${start}-${previous}`)
    start = x
    previous = x
  }
  out.push(start === previous ? `${start}` : `${start}-${previous}`)
  return out.join(',')
}

for (let z = z0; z <= z1; z += step) {
  const cells = bySlice.get(z)
  if (!cells || cells.length === 0) {
    console.log(`  z${String(z).padStart(5)}  (all solid)`)
    continue
  }
  const layers = [...new Set(cells.map(cell => cell.y))].sort((a, b) => a - b)
  const parts = layers.map(y => `y${y}:${runs(cells.filter(cell => cell.y === y).map(cell => cell.x))}`)
  console.log(`  z${String(z).padStart(5)}  ${parts.join('  ')}`)
}
await client.close()
