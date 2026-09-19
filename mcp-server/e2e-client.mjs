import { readFileSync } from 'node:fs'
import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js'

// Args may be given inline or as `@path/to.json`: cmd.exe and PowerShell strip
// the inner quotes of a JSON argument, which turns a payload into a syntax error.
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
  // A file written by PowerShell may start with a UTF-8 BOM, which JSON.parse rejects.
  const raw = argsRaw?.startsWith('@') ? readFileSync(argsRaw.slice(1), 'utf8') : argsRaw
  const argsText = raw?.replace(/^\uFEFF/, '')
  const args = argsText ? JSON.parse(argsText) : {}
  const result = await client.callTool({ name: tool, arguments: args })
  const text = (result.content ?? []).filter(part => part.type === 'text').map(part => part.text).join('\n')
  console.log(JSON.stringify({ isError: result.isError === true, text: text.slice(0, 4000), structured: result.structuredContent ?? null }, null, 2))
}
await client.close()
