import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js'

const [port, cx, cy, cz, radius, blockId] = process.argv.slice(2)
const client = new Client({ name: 'find-blocks', version: '1.0.0' })
await client.connect(new StreamableHTTPClientTransport(new URL(`http://127.0.0.1:${port}/mcp`)))
const result = await client.callTool({
  name: 'find_blocks',
  arguments: {
    center: { x: Number(cx), y: Number(cy), z: Number(cz) },
    radius: Number(radius),
    blockIds: [blockId],
    maxResults: 1024,
  },
})
const record = result.structuredContent ?? {}
const matches = Array.isArray(record.matches) ? record.matches : []
if (result.isError) {
  console.log(JSON.stringify({ isError: true, text: (result.content ?? []).map(part => part.text).join('') }, null, 2))
}
else {
  const xs = matches.map(match => match.x)
  const ys = matches.map(match => match.y)
  const zs = matches.map(match => match.z)
  const counts = new Map()
  for (const match of matches) counts.set(match.y, (counts.get(match.y) ?? 0) + 1)
  console.log(JSON.stringify({
    totalFound: record.totalFound,
    returned: matches.length,
    x: xs.length ? [Math.min(...xs), Math.max(...xs)] : null,
    y: [...counts.entries()].sort((a, b) => b[1] - a[1]).slice(0, 4),
    z: zs.length ? [Math.min(...zs), Math.max(...zs)] : null,
  }, null, 2))
}
await client.close()
