import { writeFileSync } from 'node:fs'
import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js'

// Maps where the platform edge is: reads one short vertical column at each grid
// point and reports the highest solid block, so a drop shows up as "air".
//
// Caveat: a box read without `includeAir` returns only non-air cells, and a
// region read is capped near 30k cells, so this reads one column at a time. The
// grid is deliberately coarse; refine it around a candidate edge.
//
// Usage: node edge-map.mjs <minX> <maxX> <stepX> <minZ> <maxZ> <stepZ> <yLow> <yHigh> [regionPort]
const [minXRaw, maxXRaw, stepXRaw, minZRaw, maxZRaw, stepZRaw, yLowRaw, yHighRaw, portRaw] = process.argv.slice(2)
const minX = Number(minXRaw); const maxX = Number(maxXRaw); const stepX = Number(stepXRaw)
const minZ = Number(minZRaw); const maxZ = Number(maxZRaw); const stepZ = Number(stepZRaw)
const yLow = Number(yLowRaw); const yHigh = Number(yHighRaw)
const port = Number(portRaw ?? 25602)

const client = new Client({ name: 'edge-map', version: '1.0.0' })
await client.connect(new StreamableHTTPClientTransport(new URL(`http://127.0.0.1:${port}/mcp`)))

async function topAt(x, z) {
  const result = await client.callTool({
    name: 'get_blocks_region',
    arguments: { from: { x, y: yLow, z }, to: { x, y: yHigh, z }, includeAir: true },
  })
  const record = result.structuredContent ?? JSON.parse((result.content ?? []).find(p => p.type === 'text')?.text ?? '{}')
  const blocks = record.blocks ?? []
  const solid = blocks.filter(b => b.id !== 'minecraft:air' && b.air !== true)
  return { top: solid.length ? Math.max(...solid.map(b => b.y)) : null, cells: blocks.length, expected: yHigh - yLow + 1 }
}

const rows = []
for (let z = minZ; z <= maxZ; z += stepZ) {
  const cells = []
  for (let x = minX; x <= maxX; x += stepX) {
    const { top } = await topAt(x, z)
    cells.push({ x, top })
  }
  rows.push({ z, cells })
  const line = cells.map(c => (c.top === null ? '  --' : String(c.top).padStart(4))).join('')
  console.log(`z=${String(z).padStart(5)} ${line}`)
}
console.log(`columns x=${minX}..${maxX} step ${stepX}; '--' means no solid block in y ${yLow}..${yHigh}`)

const out = process.argv[10]
if (out) {
  writeFileSync(out, JSON.stringify({ yLow, yHigh, rows }, null, 2))
  console.log(`wrote ${out}`)
}
await client.close()
