import { appendFileSync, writeFileSync } from 'node:fs'
import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js'

// Tracks one player's position until interrupted.
//
// `list_players` is a server tool (the sample rate is not limited by any client
// bridge), and only a line whose position actually changed is written, so a
// stationary player does not flood the file.
//
// Usage: node track-player.mjs [targetName] [outPath] [intervalMs] [serverPort]
const target = process.argv[2] ?? 'AfterRain'
const outPath = process.argv[3] ?? 'afterrain-track.jsonl'
const intervalMs = Number(process.argv[4] ?? 1200)
const serverPort = Number(process.argv[5] ?? 25602)

const world = new Client({ name: 'player-tracker', version: '1.0.0' })
await world.connect(new StreamableHTTPClientTransport(new URL(`http://127.0.0.1:${serverPort}/mcp`)))

function textOf(result) {
  const structured = result.structuredContent
  if (structured && typeof structured === 'object')
    return structured
  return JSON.parse((result.content ?? []).find(part => part.type === 'text')?.text ?? '{}')
}

writeFileSync(outPath, '')
console.log(`tracking "${target}" every ${intervalMs} ms -> ${outPath}`)
console.log('time      x        y       z        yaw     pitch   mode')

let lastKey = ''
const startedAt = Date.now()
let samples = 0
let moves = 0

// The caller stops this with a job kill; the loop is bounded only by that.
for (;;) {
  let record
  try {
    record = textOf(await world.callTool({ name: 'list_players', arguments: {} }))
  }
  catch {
    await new Promise(resolve => setTimeout(resolve, intervalMs))
    continue
  }
  samples++
  const player = (record.players ?? []).find(entry => entry.name === target)
  const at = Date.now()
  if (!player) {
    if (lastKey !== 'offline') {
      console.log(`${new Date(at).toTimeString().slice(0, 8)}  (offline)`)
      appendFileSync(outPath, `${JSON.stringify({ at, name: target, online: false })}\n`)
      lastKey = 'offline'
    }
  }
  else {
    const key = `${player.x.toFixed(1)},${player.y.toFixed(1)},${player.z.toFixed(1)}`
    if (key !== lastKey) {
      const line = `${new Date(at).toTimeString().slice(0, 8)}  ${player.x.toFixed(1).padStart(8)} ${player.y.toFixed(1).padStart(7)} ${player.z.toFixed(1).padStart(8)} ${String(Math.round(player.yaw)).padStart(7)} ${String(Math.round(player.pitch)).padStart(7)}   ${player.gameMode}`
      console.log(line)
      appendFileSync(outPath, `${JSON.stringify({ at, name: target, online: true, x: player.x, y: player.y, z: player.z, yaw: player.yaw, pitch: player.pitch, gameMode: player.gameMode, health: player.health })}\n`)
      lastKey = key
      moves++
    }
  }
  if (samples % 100 === 0) {
    const seconds = ((at - startedAt) / 1000).toFixed(0)
    console.log(`-- ${seconds}s: ${samples} samples, ${moves} distinct positions`)
  }
  await new Promise(resolve => setTimeout(resolve, intervalMs))
}
