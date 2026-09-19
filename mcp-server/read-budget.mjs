/**
 * Measures the bridge's region-read budget.
 *
 * The B0 corridor read is 65x33x65 = 139k cells, and on the live server it came
 * back as `read_failed`, which silently drops the whole corridor layer. This
 * times several box sizes around the bot and reports what the bridge actually
 * does: how many cells come back, whether the result is truncated, and how long
 * the call takes against the bridge's own 8 s call timeout.
 *
 * Usage: node read-budget.mjs [mcpPort] [x] [y] [z]
 */
import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js'

const [portRaw, xRaw, yRaw, zRaw] = process.argv.slice(2)
const port = Number(portRaw ?? 25600)
const fallback = { x: -1007, y: 74, z: 79 }
const center = {
  x: Number(xRaw ?? fallback.x),
  y: Number(yRaw ?? fallback.y),
  z: Number(zRaw ?? fallback.z),
}

const client = new Client({ name: 'read-budget', version: '1.0.0' })
await client.connect(new StreamableHTTPClientTransport(new URL(`http://127.0.0.1:${port}/mcp`)))

const shapes = [
  { label: 'radius 32, up/down 16 (B0 corridor)', radius: 32, up: 16, down: 16 },
  { label: 'radius 32, up/down 4', radius: 32, up: 4, down: 4 },
  { label: 'radius 30, up/down 4', radius: 30, up: 4, down: 4 },
  { label: 'radius 24, up/down 4', radius: 24, up: 4, down: 4 },
  { label: 'radius 20, up/down 8 (rollout near)', radius: 20, up: 8, down: 8 },
]

for (const shape of shapes) {
  const from = { x: center.x - shape.radius, y: center.y - shape.down, z: center.z - shape.radius }
  const to = { x: center.x + shape.radius, y: center.y + shape.up, z: center.z + shape.radius }
  const volume = (to.x - from.x + 1) * (to.y - from.y + 1) * (to.z - from.z + 1)
  const started = Date.now()
  try {
    const result = await client.callTool({ name: 'get_blocks_region', arguments: { from, to, includeAir: true } })
    const record = result.structuredContent ?? JSON.parse((result.content ?? []).find(p => p.type === 'text')?.text ?? '{}')
    const ms = Date.now() - started
    console.log(`${shape.label.padEnd(38)} volume ${String(volume).padStart(7)}  cells ${String((record.blocks ?? []).length).padStart(6)}  truncated=${record.truncated}  reported=${record.volume ?? '?'}  ${ms} ms`)
  }
  catch (error) {
    console.log(`${shape.label.padEnd(38)} volume ${String(volume).padStart(7)}  FAILED after ${Date.now() - started} ms: ${error.message.slice(0, 160)}`)
  }
}
await client.close()
