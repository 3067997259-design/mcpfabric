import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js'

// Isolates which bridge input actually opens the elytra in free fall.
//
// The operator manually deployed by pressing A + space, but the bridge exposes no
// raw key tool — only `jump`, `set_movement` and `use_item`. Live captures
// disagreed about what works: one glide deployed by itself, four stayed closed
// through a single `jump`. This tests each available method from a clean fall.
//
// Usage: node deploy-probe.mjs [port] [serverPort] [x] [y] [z]
const [portRaw, serverPortRaw, xRaw, yRaw, zRaw] = process.argv.slice(2)
const port = Number(portRaw ?? 25600)
const serverPort = Number(serverPortRaw ?? 25602)
const x = Number(xRaw ?? 220)
const y = Number(yRaw ?? 200)
const z = Number(zRaw ?? -17)

const bot = new Client({ name: 'deploy-probe-bot', version: '1.0.0' })
await bot.connect(new StreamableHTTPClientTransport(new URL(`http://127.0.0.1:${port}/mcp`)))
const world = new Client({ name: 'deploy-probe-world', version: '1.0.0' })
await world.connect(new StreamableHTTPClientTransport(new URL(`http://127.0.0.1:${serverPort}/mcp`)))

function textOf(result) {
  const structured = result.structuredContent
  if (structured && typeof structured === 'object')
    return structured
  return JSON.parse((result.content ?? []).find(part => part.type === 'text')?.text ?? '{}')
}
async function call(client, name, args = {}) {
  const result = await client.callTool({ name, arguments: args })
  if (result.isError === true)
    throw new Error(textOf(result).text ?? `tool ${name} failed`)
  return textOf(result)
}
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))

async function alive() {
  const players = await call(world, 'list_players', {})
  const me = players.players?.find(player => player.name === 'airitest')
  return me && Number(me.health) > 0
}

/** Drops the bot from (x, y, z) and reports whether the glider opened. */
async function trial(label, act) {
  // A failed trial kills the bot on impact, so each one starts from a respawn.
  if (!(await alive())) {
    await call(bot, 'respawn', {}).catch(() => {})
    await sleep(1500)
  }
  await call(bot, 'stop_movement').catch(() => {})
  await call(world, 'teleport_player', { player: 'airitest', x, y, z })
  // Let it fall so the deploy is attempted while genuinely descending.
  await sleep(250)
  const before = await call(bot, 'get_self')
  await act()
  let opened = false
  let last
  for (let i = 0; i < 12; i++) {
    await sleep(100)
    last = await call(bot, 'get_self')
    if (last.fallFlying === true) {
      opened = true
      break
    }
  }
  await call(bot, 'stop_movement').catch(() => {})
  console.log(`${label.padEnd(34)} opened=${String(opened).padEnd(5)} fell from y=${before.y.toFixed(1)} to y=${last.y.toFixed(1)} onGround=${last.onGround}`)
  return opened
}

console.log(`trial site (${x}, ${y}, ${z}); each trial respawns first when the bot is dead`)
const results = {}
results['single jump'] = await trial('single jump', async () => {
  await call(bot, 'jump')
})
results['A strafe + jump'] = await trial('left strafe + single jump', async () => {
  await call(bot, 'set_movement', { left: true, forward: false, back: false, right: false, sprint: false })
  await call(bot, 'jump')
})
results['jump held via set_movement'] = await trial('jump held (set_movement)', async () => {
  await call(bot, 'set_movement', { left: false, forward: false, back: false, right: false, sprint: false, jump: true })
})
results['A strafe + jump held'] = await trial('left + jump held', async () => {
  await call(bot, 'set_movement', { left: true, forward: false, back: false, right: false, sprint: false, jump: true })
})

console.log('--- summary ---')
for (const [label, opened] of Object.entries(results))
  console.log(`${opened ? 'OPENED ' : 'closed '}  ${label}`)
await bot.close()
await world.close()
