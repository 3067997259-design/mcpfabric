import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js'

// Prints the highest solid block along a line of columns, read with
// `includeAir: true`. This is the shape needed to answer "does the terrain reach
// this flight altitude", without dumping a whole box.
//
// Usage: node surface-map.mjs <x0> <z0> <stepX> <stepZ> <count> <bottomY> <topY> [regionPort]
//   Walks `count` columns from (x0, z0), stepping (stepX, stepZ) each time.
const [x0Raw, z0Raw, stepXRaw, stepZRaw, countRaw, bottomYRaw, topYRaw, portRaw] = process.argv.slice(2)
let x = Number(x0Raw)
let z = Number(z0Raw)
const stepX = Number(stepXRaw)
const stepZ = Number(stepZRaw)
const count = Number(countRaw)
const bottomY = Number(bottomYRaw ?? 60)
const topY = Number(topYRaw ?? 205)
const port = Number(portRaw ?? 25602)

const client = new Client({ name: 'surface-map', version: '1.0.0' })
await client.connect(new StreamableHTTPClientTransport(new URL(`http://127.0.0.1:${port}/mcp`)))

for (let index = 0; index < count; index++) {
  const result = await client.callTool({
    name: 'get_blocks_region',
    arguments: { from: { x, y: bottomY, z }, to: { x, y: topY, z }, includeAir: true },
  })
  const record = result.structuredContent ?? JSON.parse((result.content ?? []).find(p => p.type === 'text')?.text ?? '{}')
  const blocks = record.blocks ?? []
  // `air` is the source's own field; the id check is the fallback for entries
  // that omit it. Reading the id first avoids counting air as a surface.
  const solid = blocks.filter(b => b.id !== 'minecraft:air' && b.air !== true)
  const top = solid.length ? Math.max(...solid.map(b => b.y)) : undefined
  const id = top === undefined ? 'none' : (solid.find(b => b.y === top)?.id ?? '?').replace('minecraft:', '')
  const complete = blocks.length === (topY - bottomY + 1)
  console.log(`(${String(x).padStart(5)}, ${String(z).padStart(5)})  cells=${blocks.length}/${topY - bottomY + 1}${complete ? '' : ' INCOMPLETE'}  highest solid y=${top ?? 'none'} (${id})`)
  x += stepX
  z += stepZ
}
await client.close()
