/**
 * Verifies a built fixture by reading its blocks.
 *
 * For each site: teleports the bot to a safe stand point (so the server has the
 * chunks loaded — a region read of unloaded terrain returns nothing), then reads
 * the box and prints, per block id, the count and the bounding box. That is the
 * shape needed to check "is the bridge 2 wide", "how long is the ceiling", "is
 * there a gap in the wool wall", without trusting a description.
 *
 * Usage: node fixture-scan.mjs <sites.json> [mcpPort] [serverPort]
 *   sites.json: [{ name, tp:[x,y,z], from:[x,y,z], to:[x,y,z] }]
 */
import { readFileSync } from 'node:fs'
import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js'

const [sitesPath, portRaw, serverPortRaw] = process.argv.slice(2)
const port = Number(portRaw ?? 25600)
const serverPort = Number(serverPortRaw ?? 25602)
const sites = JSON.parse(readFileSync(sitesPath, 'utf8'))

const bot = new Client({ name: 'fixture-scan-bot', version: '1.0.0' })
await bot.connect(new StreamableHTTPClientTransport(new URL(`http://127.0.0.1:${port}/mcp`)))
const world = new Client({ name: 'fixture-scan-world', version: '1.0.0' })
await world.connect(new StreamableHTTPClientTransport(new URL(`http://127.0.0.1:${serverPort}/mcp`)))

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))

async function call(client, name, args) {
  const result = await client.callTool({ name, arguments: args })
  const text = (result.content ?? []).filter(p => p.type === 'text').map(p => p.text).join('\n')
  try {
    return JSON.parse(text)
  }
  catch {
    return result.structuredContent ?? { raw: text }
  }
}

const isAir = id => !id || id.endsWith('air')

for (const site of sites) {
  console.log(`\n=== ${site.name} ===`)
  try {
    await call(bot, 'teleport_player', { player: 'airitest', x: site.tp[0], y: site.tp[1], z: site.tp[2] })
  }
  catch (error) {
    console.log(`  teleport failed: ${error.message}`)
  }
  // A freshly teleported area needs the server to load (and sometimes generate)
  // its chunks; a read before that returns nothing, not "empty terrain".
  await sleep(site.settleMs ?? 4500)
  let read
  try {
    read = await call(world, 'get_blocks_region', {
      from: { x: site.from[0], y: site.from[1], z: site.from[2] },
      to: { x: site.to[0], y: site.to[1], z: site.to[2] },
      includeAir: true,
    })
  }
  catch (error) {
    console.log(`  read failed: ${error.message}`)
    continue
  }
  const blocks = read.blocks ?? []
  if (blocks.length === 0) {
    console.log(`  EMPTY READ (volume ${read.volume ?? '?'}) — chunks likely not loaded at ${site.tp.join(',')}`)
    continue
  }
  const byId = new Map()
  for (const block of blocks) {
    if (isAir(block.id))
      continue
    const entry = byId.get(block.id) ?? { count: 0, x: [Infinity, -Infinity], y: [Infinity, -Infinity], z: [Infinity, -Infinity] }
    entry.count++
    entry.x = [Math.min(entry.x[0], block.x), Math.max(entry.x[1], block.x)]
    entry.y = [Math.min(entry.y[0], block.y), Math.max(entry.y[1], block.y)]
    entry.z = [Math.min(entry.z[0], block.z), Math.max(entry.z[1], block.z)]
    byId.set(block.id, entry)
  }
  console.log(`  volume ${read.volume ?? blocks.length}, cells ${blocks.length}, solid ids ${byId.size}`)
  for (const [id, entry] of [...byId.entries()].sort((a, b) => b[1].count - a[1].count))
    console.log(`  ${id.padEnd(34)} x${String(entry.count).padStart(4)}  x ${entry.x[0]}..${entry.x[1]}  y ${entry.y[0]}..${entry.y[1]}  z ${entry.z[0]}..${entry.z[1]}`)

  // Shape of the ids this site is about: per layer the x spans for each z, which
  // tells a wall (one span per z) from a diagonal strip (the span walks).
  for (const id of site.ids ?? []) {
    const cells = blocks.filter(block => block.id === id)
    if (cells.length === 0)
      continue
    console.log(`  --- ${id} shape (${cells.length} cells) ---`)
    const layers = [...new Set(cells.map(cell => cell.y))].sort((a, b) => a - b)
    for (const y of layers) {
      const rows = []
      const zs = [...new Set(cells.filter(cell => cell.y === y).map(cell => cell.z))].sort((a, b) => a - b)
      for (const z of zs) {
        const xs = cells.filter(cell => cell.y === y && cell.z === z).map(cell => cell.x).sort((a, b) => a - b)
        const spans = []
        let start = xs[0]
        let previous = xs[0]
        for (const x of xs.slice(1)) {
          if (x === previous + 1) {
            previous = x
            continue
          }
          spans.push(start === previous ? `${start}` : `${start}-${previous}`)
          start = x
          previous = x
        }
        spans.push(start === previous ? `${start}` : `${start}-${previous}`)
        rows.push(`z${z}:${spans.join(',')}`)
      }
      console.log(`    y=${String(y).padStart(3)}  ${rows.join('  ')}`)
    }
  }
}

await bot.close()
await world.close()
