import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js'

// Determines the real player-inventory index of the chest armor slot.
//
// The bridge answers `swapped` for any pair of in-range indices and maps them
// through `toMenuSlot`, so a wrong index is silent: the item stays where it was.
// This moves a distinguishable item into one candidate index and reads back
// where it landed, which is the only claim worth trusting.
//
// Usage: node slot-probe.mjs [port] [sourceSlot]
const port = Number(process.argv[2] ?? 25600)
const source = Number(process.argv[3] ?? 13)

const bot = new Client({ name: 'slot-probe', version: '1.0.0' })
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

/** Every item the player holds, with its reported slot or armor label. */
async function snapshot() {
  const inventory = await call('get_inventory')
  const armor = (inventory.armor ?? []).map(entry => `${entry.slot}:${(entry.id ?? 'empty').replace('minecraft:', '')}`)
  const hotbar = (inventory.hotbar ?? []).map(entry => `${entry.slot}:${(entry.id ?? 'empty').replace('minecraft:', '')}`)
  const main = (inventory.main ?? []).map(entry => `${entry.slot}:${(entry.id ?? 'empty').replace('minecraft:', '')}`)
  return { hotbar, main, armor, selectedSlot: inventory.selectedSlot }
}

const before = await snapshot()
console.log(`source slot ${source} holds: ${[...before.hotbar, ...before.main].find(entry => entry.startsWith(`${source}:`)) ?? 'empty'}`)
console.log(`armor before: ${before.armor.join('  ') || '(none)'}`)

for (const candidate of [36, 37, 38, 39]) {
  // Put the source item back first so each candidate starts from the same state.
  await call('swap_slots', { slotA: source, slotB: candidate }).catch(() => {})
  await sleep(300)
  const after = await snapshot()
  const where = [...after.hotbar, ...after.main].find(entry => entry.endsWith(':cobblestone'))
    ?? (after.armor.some(entry => entry.endsWith(':cobblestone')) ? `armor[${after.armor.findIndex(entry => entry.endsWith(':cobblestone'))}]` : 'not found')
  console.log(`swap(${source} -> ${candidate}): armor after = ${after.armor.join('  ') || '(none)'}   cobblestone now at ${where}`)
  // Undo, so the next candidate sees the original layout.
  await call('swap_slots', { slotA: source, slotB: candidate }).catch(() => {})
  await sleep(300)
}

await bot.close()
