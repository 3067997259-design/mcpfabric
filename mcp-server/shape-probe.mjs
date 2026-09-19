import { Client } from "@modelcontextprotocol/sdk/client/index.js"
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js"
const c = new Client({ name: "shape-probe", version: "1.0.0" })
await c.connect(new StreamableHTTPClientTransport(new URL("http://127.0.0.1:25600/mcp")))
const r = await c.callTool({ name: "get_inventory", arguments: {} })
const text = (r.content ?? []).filter(p => p.type === "text").map(p => p.text).join("")
console.log("structuredContent keys:", r.structuredContent ? Object.keys(r.structuredContent) : null)
console.log("structured hotbar isArray:", Array.isArray(r.structuredContent?.hotbar), "len:", r.structuredContent?.hotbar?.length)
console.log("text first 300:", text.slice(0, 300))
console.log("text keys:", Object.keys(JSON.parse(text)))
await c.close()
