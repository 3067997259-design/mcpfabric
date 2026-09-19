import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js'

// Measures the free vertical space above the floor at each waypoint of a route,
// so a cave passage can be judged against what an elytra glider needs.
//
// A walkable floor is not enough: the glider sinks continuously, so what matters
// is how many blocks of verified air sit above the floor, and whether the
// passage is wide enough for a turn once the planner's 12-tick horizon (about 28
// blocks at cruise speed) has been spent.
//
// Usage: node clearance-probe.mjs <x0> <y0> <z0> <x1> <y1> <z1> [steps] [regionPort]
//   Samples `steps` points along the straight line between the two endpoints.
const [x0Raw, y0Raw, z0Raw, x1Raw, y1Raw, z1Raw, stepsRaw, portRaw] = process.argv.slice(2)
const x0 = Number(x0Raw); const y0 = Number(y0Raw); const z0 = Number(z0Raw)
const x1 = Number(x1Raw); const y1 = Number(y1Raw); const z1 = Number(z1Raw)
const steps = Number(stepsRaw ?? 12)
const topY = Number(y1Raw) + 30
const bottomY = Math.min(y0, y1) - 20
const port = Number(portRaw ?? 25602)

const client = new Client({ name: 'clearance-probe', version: '1.0.0' })
await client.connect(new StreamableHTTPClientTransport(new URL(`http://127.0.0.1:${port}/mcp`)))

function textOf(result) {
  const structured = result.structuredContent
  if (structured && typeof structured === 'object')
    return structured
  return JSON.parse((result.content ?? []).find(part => part.type === 'text')?.text ?? '{}')
}

console.log(`line (${x0}, ${y0}, ${z0}) -> (${x1}, ${y1}, ${z1}), ${steps + 1} samples`)
console.log('   x      z    floorY  headroom  ceiling block        floor block')
for (let index = 0; index <= steps; index++) {
  const t = steps === 0 ? 0 : index / steps
  const x = Math.round(x0 + (x1 - x0) * t)
  const z = Math.round(z0 + (z1 - z0) * t)
  let record
  try {
    record = textOf(await client.callTool({
      name: 'get_blocks_region',
      arguments: { from: { x, y: bottomY, z }, to: { x, y: topY, z }, includeAir: true },
    }))
  }
  catch (error) {
    console.log(`${String(x).padStart(5)} ${String(z).padStart(6)}   read failed: ${error.message}`)
    continue
  }
  const blocks = record.blocks ?? []
  if (blocks.length === 0) {
    console.log(`${String(x).padStart(5)} ${String(z).padStart(6)}   (chunk not loaded)`)
    continue
  }
  const solid = blocks.filter(b => b.id !== 'minecraft:air' && b.air !== true)
  if (solid.length === 0) {
    console.log(`${String(x).padStart(5)} ${String(z).padStart(6)}   (no solid in the window)`)
    continue
  }
  // Floor: the highest solid below the route line; ceiling: the lowest solid above it.
  const lineY = Math.round(y0 + (y1 - y0) * t)
  const below = solid.filter(b => b.y <= lineY)
  const above = solid.filter(b => b.y > lineY)
  const floor = below.length ? below.reduce((best, b) => (b.y > best.y ? b : best)) : undefined
  const ceiling = above.length ? above.reduce((best, b) => (b.y < best.y ? b : best)) : undefined
  const headroom = floor && ceiling ? ceiling.y - floor.y - 1 : (floor ? 'open above' : 'n/a')
  console.log(`${String(x).padStart(5)} ${String(z).padStart(6)}   ${floor ? String(floor.y).padStart(6) : '  none'}  ${String(headroom).padStart(8)}  ${(ceiling?.id ?? '-').replace('minecraft:', '').padEnd(20)} ${(floor?.id ?? '-').replace('minecraft:', '')}`)
}
await client.close()
