/**
 * Prints one column as `y:id` pairs on a single line.
 *
 * Usage: node col-ids.mjs <x> <y0> <y1> <z> [serverPort]
 */
import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js'

const [xRaw, y0Raw, y1Raw, zRaw, portRaw] = process.argv.slice(2)
const x = Number(xRaw)
const y0 = Number(y0Raw)
const y1 = Number(y1Raw)
const z = Number(zRaw)
const port = Number(portRaw ?? 25602)

const client = new Client({ name: 'col-ids', version: '1.0.0' })
await client.connect(new StreamableHTTPClientTransport(new URL(`http://127.0.0.1:${port}/mcp`)))
const read = await client.callTool({
  name: 'get_blocks_region',
  arguments: { from: { x, y: y0, z }, to: { x, y: y1, z }, includeAir: true },
})
const record = read.structuredContent ?? JSON.parse((read.content ?? []).find(p => p.type === 'text')?.text ?? '{}')
const blocks = (record.blocks ?? []).sort((a, b) => a.y - b.y)
if (blocks.length === 0) {
  console.log(`(${x}, ${z}) y${y0}..${y1}: EMPTY READ`)
  process.exit(0)
}
console.log(`(${x}, ${z}): ${blocks.map(block => `${block.y}:${(block.id ?? '').replace('minecraft:', '')}`).join(' ')}`)
await client.close()
