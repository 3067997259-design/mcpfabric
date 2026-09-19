/**
 * Prints a compact inventory summary from the in-game bridge.
 *
 * Written because parsing the nested JSON in PowerShell keeps mangling quotes;
 * this is the readable view the durability and rocket live checks need.
 *
 * Usage: node inv-summary.mjs [port]
 */
import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js'

const port = Number(process.argv[2] ?? 25600)
const client = new Client({ name: 'inv-summary', version: '1.0.0' })
await client.connect(new StreamableHTTPClientTransport(new URL(`http://127.0.0.1:${port}/mcp`)))
const result = await client.callTool({ name: 'get_inventory', arguments: {} })
const record = result.structuredContent ?? JSON.parse((result.content ?? []).find(p => p.type === 'text')?.text ?? '{}')

const line = item => item && item.id
  ? `${item.id} x${item.count ?? 1}${item.damage !== undefined ? ` dmg ${item.damage}/${item.maxDamage ?? '?'}` : ''}`
  : '(empty)'

console.log(`selectedSlot: ${record.selectedSlot}`)
for (const item of record.hotbar ?? [])
  console.log(`  hotbar ${item.slot}: ${line(item)}`)
for (const item of record.main ?? [])
  console.log(`  main   ${item.slot}: ${line(item)}`)
for (const item of record.armor ?? [])
  console.log(`  armor  ${item.slot}: ${line(item)}`)
console.log(`  offhand: ${line(record.offhand)}`)
await client.close()
