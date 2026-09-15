import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js'

const [port, x1, z1, x2, z2, yMin, yMax] = process.argv.slice(2).map(Number)
const client = new Client({ name: 'strip-profile', version: '1.0.0' })
await client.connect(new StreamableHTTPClientTransport(new URL(`http://127.0.0.1:${port}/mcp`)))
const result = await client.callTool({
  name: 'get_blocks_region',
  arguments: { from: { x: x1, y: yMin, z: z1 }, to: { x: x2, y: yMax, z: z2 }, includeAir: true },
})
const record = result.structuredContent ?? {}
const blocks = Array.isArray(record.blocks) ? record.blocks : []

const tops = new Map()
let partialShapes = 0
let emptyShapes = 0
let fullCubes = 0
const solidIds = new Map()
for (const block of blocks) {
  const id = String(block.id ?? '')
  const key = `${block.x},${block.z}`
  const air = id.endsWith('air') || id === 'minecraft:cave_air' || id === 'minecraft:void_air'
  if (Array.isArray(block.collision)) {
    if (block.collision.length > 0)
      partialShapes++
    else if (!air && !id.includes('water') && !id.includes('lava'))
      emptyShapes++
  }
  else if (!air) {
    fullCubes++
  }
  if (!air && !id.includes('water') && !id.includes('lava')) {
    const top = Number(block.y)
    if (!tops.has(key) || tops.get(key) < top)
      tops.set(key, top)
    solidIds.set(id, (solidIds.get(id) ?? 0) + 1)
  }
}
const columns = [...tops.entries()].map(([key, top]) => `${key}:${top}`).join(' ')
console.log(JSON.stringify({
  exactShapes: record.exactShapes === true,
  partialShapes,
  emptyShapes,
  fullCubes,
  topIds: [...solidIds.entries()].sort((a, b) => b[1] - a[1]).slice(0, 6),
  columns,
}, null, 2))
await client.close()
