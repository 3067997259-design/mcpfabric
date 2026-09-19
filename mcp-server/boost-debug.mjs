import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js'

// Diagnoses why a `use_item` rocket did not take effect during a glide.
//
// One boost capture produced a full burn (speed 0.56 -> 1.68 and back), a later
// one produced nothing at all while its inventory still held 64 rockets. The
// difference between the two was only the tick at which the fire was requested,
// so this reports every step of the sequence instead of the last one.
//
// Usage: node boost-debug.mjs [port] [serverPort] [hotbar]
const port = Number(process.argv[2] ?? 25600)
const serverPort = Number(process.argv[3] ?? 25602)
const hotbar = Number(process.argv[4] ?? 5)

const bot = new Client({ name: 'boost-debug', version: '1.0.0' })
await bot.connect(new StreamableHTTPClientTransport(new URL(`http://127.0.0.1:${port}/mcp`)))
const world = new Client({ name: 'boost-debug-world', version: '1.0.0' })
await world.connect(new StreamableHTTPClientTransport(new URL(`http://127.0.0.1:${serverPort}/mcp`)))

function textOf(result) {
  const structured = result.structuredContent
  if (structured && typeof structured === 'object')
    return structured
  return JSON.parse((result.content ?? []).find(part => part.type === 'text')?.text ?? '{}')
}
async function raw(client, name, args = {}) {
  const result = await client.callTool({ name, arguments: args })
  return { isError: result.isError === true, body: textOf(result) }
}
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))

console.log('--- before ---')
const before = await raw(bot, 'get_self')
console.log(`fallFlying=${before.body.fallFlying} y=${before.body.y} onGround=${before.body.onGround}`)
const inv = await raw(bot, 'get_inventory')
const rockets = (inv.body.hotbar ?? []).filter(slot => slot.id === 'minecraft:firework_rocket')
console.log(`rockets in hotbar: ${JSON.stringify(rockets.map(slot => ({ slot: slot.slot, count: slot.count })))}`)
console.log(`selectedSlot=${inv.body.selectedSlot}`)

console.log(`--- select_hotbar_slot ${hotbar} ---`)
const selected = await raw(bot, 'select_hotbar_slot', { slot: hotbar })
console.log(JSON.stringify(selected))
await sleep(300)
const afterSelect = await raw(bot, 'get_inventory')
console.log(`selectedSlot now=${afterSelect.body.selectedSlot}`)

console.log('--- use_item ---')
const used = await raw(bot, 'use_item')
console.log(JSON.stringify(used))
await sleep(1200)

const after = await raw(bot, 'get_inventory')
const rocketsAfter = (after.body.hotbar ?? []).filter(slot => slot.id === 'minecraft:firework_rocket')
console.log(`rockets after: ${JSON.stringify(rocketsAfter.map(slot => ({ slot: slot.slot, count: slot.count })))}`)
const self = await raw(bot, 'get_self')
console.log(`motion after=(${self.body.motion.x.toFixed(4)}, ${self.body.motion.y.toFixed(4)}, ${self.body.motion.z.toFixed(4)}) fallFlying=${self.body.fallFlying}`)

await bot.close()
await world.close()
