import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js'

// Isolates why the elytra does not open during the capture's sprint launch.
//
// Teleport-based trials show every available input opens the glider in free fall
// (single jump, strafe + jump, jump held). The sprint launch differs in one
// respect: `jump` is already HELD when the deploy is attempted, and a held key is
// not a new press. This walks off the platform edge under each key state and
// reports whether the glider opens.
//
// Usage: node deploy-from-edge.mjs [serverPort]
const serverPort = Number(process.argv[2] ?? 25602)
const port = 25600
const PAD = { x: 238, y: 202, z: -17 }

const bot = new Client({ name: 'edge-deploy-bot', version: '1.0.0' })
await bot.connect(new StreamableHTTPClientTransport(new URL(`http://127.0.0.1:${port}/mcp`)))
const world = new Client({ name: 'edge-deploy-world', version: '1.0.0' })
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

/**
 * Runs off the pad edge and reports how the glider opened.
 *
 * `keepJumpHeld` mirrors the capture's sprint input; `releaseFirst` clears every
 * key once the fall starts and then presses jump, which is the variant the
 * teleport trials proved works.
 */
async function trial(label, { keepJumpHeld, releaseFirst }) {
  if (!(await alive())) {
    await call(bot, 'respawn', {}).catch(() => {})
    await sleep(1500)
  }
  await call(bot, 'stop_movement').catch(() => {})
  await call(world, 'teleport_player', { player: 'airitest', ...PAD })
  await sleep(400)
  await call(bot, 'look', { yaw: 90, pitch: 0 })
  await call(bot, 'set_movement', { forward: true, back: false, left: false, right: false, sprint: true, jump: keepJumpHeld })

  // Wait until the bot is past the rim and descending.
  let self
  const deadline = Date.now() + 8000
  let fell = false
  while (Date.now() < deadline) {
    await sleep(30)
    self = await call(bot, 'get_self')
    if (self.onGround === false && self.x < 235.5) {
      fell = true
      break
    }
  }
  if (!fell) {
    await call(bot, 'stop_movement').catch(() => {})
    console.log(`${label.padEnd(30)} never cleared the edge (x=${self?.x}, onGround=${self?.onGround})`)
    return undefined
  }

  const autoOpened = self.fallFlying === true
  if (!autoOpened) {
    if (releaseFirst)
      await call(bot, 'stop_movement').catch(() => {})
    await call(bot, 'jump')
  }
  let opened = autoOpened
  let last = self
  for (let i = 0; i < 15; i++) {
    await sleep(80)
    last = await call(bot, 'get_self')
    if (last.fallFlying === true) {
      opened = true
      break
    }
  }
  await call(bot, 'stop_movement').catch(() => {})
  console.log(`${label.padEnd(30)} autoOpened=${String(autoOpened).padEnd(5)} opened=${String(opened).padEnd(5)} edgeX=${self.x.toFixed(2)} fellTo y=${last.y.toFixed(1)} onGround=${last.onGround}`)
  return opened
}

const results = {}
results['jump held through fall'] = await trial('jump held through fall', { keepJumpHeld: true, releaseFirst: false })
results['release keys, then jump'] = await trial('release keys, then jump', { keepJumpHeld: false, releaseFirst: true })

console.log('--- summary ---')
for (const [label, opened] of Object.entries(results))
  console.log(`${opened === true ? 'OPENED' : opened === false ? 'closed' : 'no-run'}  ${label}`)
await bot.close()
await world.close()
