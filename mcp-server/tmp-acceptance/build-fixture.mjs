/**
 * Builds one named acceptance fixture on the smooth-stone pad.
 *
 * Usage: node build-fixture.mjs <clear|hairpin|stepup|staircase>
 */
import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js'

const name = process.argv[2] ?? 'clear'
const server = new Client({ name: 'fixture-builder', version: '1.0.0' })
await server.connect(new StreamableHTTPClientTransport(new URL('http://127.0.0.1:25602/mcp')))
const call = (tool, args) => server.callTool({ name: tool, arguments: args })
const fill = (from, to, blockId) => call('fill_blocks', { from, to, blockId })
const set = (x, y, z, blockId) => call('set_block', { x, y, z, blockId })
const air = 'minecraft:air'
const stone = 'minecraft:smooth_stone'

// Every fixture starts from a clean pad top.
await fill({ x: 74, y: 75, z: -30 }, { x: 98, y: 80, z: -10 }, air)

if (name === 'hairpin') {
  // Comb: two 180-degree turns around wall ends. The walls run past the pad
  // edges so the route cannot leave the pad to go around them.
  await fill({ x: 66, y: 75, z: -25 }, { x: 92, y: 77, z: -25 }, stone)
  await fill({ x: 80, y: 75, z: -20 }, { x: 106, y: 77, z: -20 }, stone)
}
else if (name === 'stepup') {
  // One full-block platform, extended past the pad edges.
  await fill({ x: 66, y: 75, z: -34 }, { x: 106, y: 75, z: -23 }, stone)
}
else if (name === 'maze') {
  // S-shaped route: a wall with one gap, then a wall forcing a second bend.
  await fill({ x: 70, y: 75, z: -23 }, { x: 89, y: 77, z: -23 }, stone)
  await fill({ x: 93, y: 75, z: -23 }, { x: 102, y: 77, z: -23 }, stone)
  await fill({ x: 84, y: 75, z: -22 }, { x: 84, y: 77, z: -17 }, stone)
}
else if (name === 'arena') {
  // Sealed arena: the outer wall removes natural-terrain bypasses, and the
  // internal detour stays inside the planner's local window (start/goal +/- 8).
  await fill({ x: 73, y: 75, z: -31 }, { x: 73, y: 78, z: -9 }, stone)
  await fill({ x: 99, y: 75, z: -31 }, { x: 99, y: 78, z: -9 }, stone)
  await fill({ x: 73, y: 75, z: -31 }, { x: 99, y: 78, z: -31 }, stone)
  await fill({ x: 73, y: 75, z: -9 }, { x: 99, y: 78, z: -9 }, stone)
  await fill({ x: 74, y: 75, z: -23 }, { x: 80, y: 78, z: -23 }, stone)
  await fill({ x: 83, y: 75, z: -22 }, { x: 83, y: 78, z: -18 }, stone)
  await fill({ x: 81, y: 75, z: -19 }, { x: 86, y: 75, z: -19 }, stone)
}
else if (name === 'arena-wide') {
  // The same sealed arena, but the only gap sits beyond the old 16-cells
  // window: this leg needs the 32-cell expansion to see the detour.
  await fill({ x: 73, y: 75, z: -31 }, { x: 73, y: 78, z: -9 }, stone)
  await fill({ x: 99, y: 75, z: -31 }, { x: 99, y: 78, z: -9 }, stone)
  await fill({ x: 73, y: 75, z: -31 }, { x: 99, y: 78, z: -31 }, stone)
  await fill({ x: 73, y: 75, z: -9 }, { x: 99, y: 78, z: -9 }, stone)
  await fill({ x: 74, y: 75, z: -23 }, { x: 96, y: 78, z: -23 }, stone)
}
else if (name === 'hill') {
  // A natural-terrain style slope: three one-block bands up to a grass plateau
  // (surfaces 76 / 77 / 78).
  await fill({ x: 78, y: 75, z: -26 }, { x: 92, y: 75, z: -25 }, 'minecraft:dirt')
  await fill({ x: 78, y: 75, z: -24 }, { x: 92, y: 76, z: -23 }, 'minecraft:dirt')
  await fill({ x: 78, y: 75, z: -22 }, { x: 92, y: 77, z: -12 }, 'minecraft:dirt')
  await fill({ x: 78, y: 75, z: -26 }, { x: 92, y: 75, z: -25 }, 'minecraft:grass_block')
  await fill({ x: 78, y: 76, z: -24 }, { x: 92, y: 76, z: -23 }, 'minecraft:grass_block')
  await fill({ x: 78, y: 77, z: -22 }, { x: 92, y: 77, z: -12 }, 'minecraft:grass_block')
}
else if (name === 'staircase') {
  // Three full-block steps up (jump-up edges) then a plateau at top y=78.
  await fill({ x: 84, y: 75, z: -26 }, { x: 84, y: 75, z: -22 }, stone)
  await fill({ x: 85, y: 75, z: -26 }, { x: 85, y: 76, z: -22 }, stone)
  await fill({ x: 86, y: 75, z: -26 }, { x: 86, y: 77, z: -22 }, stone)
  await fill({ x: 87, y: 75, z: -26 }, { x: 94, y: 77, z: -22 }, stone)
}

const verify = await call('get_block', { x: 84, y: 75, z: -24 })
console.log(`fixture=${name} probe84=${verify.structuredContent?.id ?? 'error'}`)
await server.close()
