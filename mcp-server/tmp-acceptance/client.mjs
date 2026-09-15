import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js'

const [portRaw, tool, argsRaw] = process.argv.slice(2)
const port = Number(portRaw ?? 25600)
const client = new Client({ name: 'acceptance-driver', version: '1.0.0' })
const transport = new StreamableHTTPClientTransport(new URL(`http://127.0.0.1:${port}/mcp`))
await client.connect(transport)
if (!tool || tool === 'list') {
  const tools = await client.listTools()
  console.log(tools.tools.map(tool => tool.name).sort().join('\n'))
}
else {
  const args = argsRaw ? JSON.parse(argsRaw) : {}
  const result = await client.callTool({ name: tool, arguments: args })
  const text = (result.content ?? []).filter(part => part.type === 'text').map(part => part.text).join('\n')
  console.log(JSON.stringify({ isError: result.isError === true, text: text.slice(0, 4000), structured: result.structuredContent ?? null }, null, 2))
}
await client.close()
