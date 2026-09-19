import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js'

// Prints a vertical column profile: for each (x,z) it lists the run of block ids
// from top to bottom, so a platform edge and its drop are readable directly.
//
// Usage: node column-profile.mjs <minX> <maxX> <z> <topY> <bottomY> [regionPort]
const [minXRaw, maxXRaw, zRaw, topYRaw, bottomYRaw, portRaw] = process.argv.slice(2)
const minX = Number(minXRaw)
const maxX = Number(maxXRaw)
const z = Number(zRaw)
const topY = Number(topYRaw)
const bottomY = Number(bottomYRaw)
const port = Number(portRaw ?? 25602)

const client = new Client({ name: 'column-profile', version: '1.0.0' })
await client.connect(new StreamableHTTPClientTransport(new URL(`http://127.0.0.1:${port}/mcp`)))

const result = await client.callTool({
  name: 'get_blocks_region',
  // `includeAir` is required: without it the source returns only non-air cells,
  // and a reader that assumes a full box reports holes where the world is air.
  arguments: { from: { x: minX, y: bottomY, z }, to: { x: maxX, y: topY, z }, includeAir: true },
})
if (result.isError === true) {
  console.error(`read failed: ${(result.content ?? []).map(p => p.text).join('')}`)
  process.exit(1)
}
const record = result.structuredContent ?? JSON.parse((result.content ?? []).find(p => p.type === 'text')?.text ?? '{}')
const blocks = record.blocks ?? []
const expected = (maxX - minX + 1) * (topY - bottomY + 1)
console.log(`z=${z}  x ${minX}..${maxX}  y ${bottomY}..${topY}  cells=${blocks.length}/${expected}${record.truncated ? ' TRUNCATED' : ''}`)

const byKey = new Map(blocks.map(b => [`${b.x},${b.y}`, b.id]))
const short = id => ({
  'minecraft:air': '.',
  'minecraft:stone': 'S',
  'minecraft:white_concrete': 'W',
  'minecraft:water': '~',
}[id] ?? (id ?? '?').replace('minecraft:', '').slice(0, 4))

for (let x = minX; x <= maxX; x++) {
  const cells = []
  for (let y = topY; y >= bottomY; y--)
    cells.push(`${y}:${short(byKey.get(`${x},${y}`))}`)
  console.log(`x=${String(x).padStart(4)}  ${cells.join(' ')}`)
}
await client.close()
