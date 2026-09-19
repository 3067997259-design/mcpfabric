import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js'

// Verifies which inventory index really is the chest armor slot, end to end.
//
// The compiled 1.21.1 classes say `armor` is indexed FEET=0, LEGS=1, CHEST=2,
// HEAD=3 and that `Inventory.getItem` walks `[items(36), armor(4), offhand(1)]`
// with subtraction, so inventory 36..39 must be armor[0..3] and the chestplate
// must be 38. A live swap decides whether that mapping also survives the bridge.
//
// The swap is only trusted when the worn chest item actually changes, so this
// prints the chest id either side of each attempt.
//
// Usage: node chest-slot-verify.mjs [port] [candidateSlot]
const port = Number(process.argv[2] ?? 25600)
const candidate = Number(process.argv[3] ?? 38)

const bot = new Client({ name: 'chest-slot-verify', version: '1.0.0' })
await bot.connect(new StreamableHTTPClientTransport(new URL(`http://127.0.0.1:${port}/mcp`)))
function textOf(result) {
  const structured = result.structuredContent
  if (structured && typeof structured === 'object')
    return structured
  return JSON.parse((result.content ?? []).find(part => part.type === 'text')?.text ?? '{}')
}
async function call(name, args = {}) {
  const result = await bot.callTool({ name, arguments: args })
  if (result.isError === true)
    throw new Error(textOf(result).text ?? `tool ${name} failed`)
  return textOf(result)
}
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const chestId = async () => (await call('get_equipment')).chest?.id ?? 'empty'

// Two swappable stacks in the hotbar; the first is the one sent to the chest.
const inventory = await call('get_inventory')
const hotbar = (inventory.hotbar ?? []).filter(entry => entry.id !== 'minecraft:air')
console.log(`hotbar: ${hotbar.map(entry => `${entry.slot}:${entry.id.replace('minecraft:', '')}`).join('  ')}`)
const source = hotbar[0]?.slot
console.log(`chest before: ${await chestId()}`)

for (const target of [36, 37, 38, 39]) {
  const before = await chestId()
  await call('swap_slots', { slotA: source, slotB: target })
  await sleep(400)
  const after = await chestId()
  const moved = before !== after
  console.log(`swap(${source} <-> ${target}): chest ${before.replace('minecraft:', '')} -> ${after.replace('minecraft:', '')}  ${moved ? '*** WORE IT ***' : '(no change)'}`)
  if (moved) {
    // Put it back so the next candidate starts from the same state.
    await call('swap_slots', { slotA: source, slotB: target })
    await sleep(400)
    console.log(`  restored: chest = ${(await chestId()).replace('minecraft:', '')}`)
  }
}
console.log(`candidate ${candidate} was the one under test`)
await bot.close()
