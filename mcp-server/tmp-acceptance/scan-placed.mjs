import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js'

const [port, x1, z1, x2, z2, yMin, yMax, filter] = process.argv.slice(2)
const yMinN = Number(yMin)
const yMaxN = Number(yMax)
const client = new Client({ name: 'scan-placed', version: '1.0.0' })
await client.connect(new StreamableHTTPClientTransport(new URL(`http://127.0.0.1:${port}/mcp`)))
const result = await client.callTool({
  name: 'get_blocks_region',
  arguments: { from: { x: Number(x1), y: yMinN, z: Number(z1) }, to: { x: Number(x2), y: yMaxN, z: Number(z2) }, includeAir: true },
})
const blocks = Array.isArray(result.structuredContent?.blocks) ? result.structuredContent.blocks : []
const natural = /(air|grass_block|short_grass|tall_grass|dirt|coarse_dirt|rooted_dirt|smooth_stone|stone|granite|diorite|andesite|deepslate|gravel|sand|clay|water|lava|oak_|birch_|spruce_|jungle_|acacia_|dark_oak_|mangrove_|cherry_|azalea|flowering_azalea|leaves|vine|fern|large_fern|poppy|dandelion|snow|moss|mud|sandstone|tuff|calcite)/i
const placed = blocks.filter((block) => {
  const id = String(block.id ?? '')
  if (!id)
    return false
  if (filter)
    return id.includes(filter)
  return !natural.test(id)
})
const byId = new Map()
for (const block of placed)
  byId.set(block.id, [...(byId.get(block.id) ?? []), `${block.x},${block.y},${block.z}`])
for (const [id, positions] of byId)
  console.log(id, positions.length, positions.slice(0, 12).join(' '))
await client.close()
