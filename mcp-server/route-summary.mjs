/**
 * Summarises a tracked-player JSONL route.
 *
 * Prints the extent, the travelled distance, the flight/ground split and the
 * dwell clusters, so a hand-built fixture can be located from the route that
 * built it.
 *
 * Usage: node route-summary.mjs <route.jsonl> [dwellRadiusBlocks] [dwellSeconds]
 */
import { readFileSync } from 'node:fs'

const [path, radiusRaw, secondsRaw] = process.argv.slice(2)
const radius = Number(radiusRaw ?? 6)
const dwellSeconds = Number(secondsRaw ?? 5)

const points = readFileSync(path, 'utf8').split('\n').filter(Boolean).map(line => JSON.parse(line)).filter(p => p.online)
if (points.length === 0) {
  console.log('no online samples')
  process.exit(0)
}

const first = points[0]
const last = points.at(-1)
const xs = points.map(p => p.x)
const ys = points.map(p => p.y)
const zs = points.map(p => p.z)
let travelled = 0
for (let index = 1; index < points.length; index++) {
  const a = points[index - 1]
  const b = points[index]
  travelled += Math.hypot(b.x - a.x, b.y - a.y, b.z - a.z)
}
const seconds = (last.at - first.at) / 1000

console.log(`samples: ${points.length}  span: ${seconds.toFixed(0)} s  travelled: ${travelled.toFixed(0)} blocks`)
console.log(`start: ${first.x.toFixed(1)}, ${first.y.toFixed(1)}, ${first.z.toFixed(1)}  (${new Date(first.at).toTimeString().slice(0, 8)})`)
console.log(`end:   ${last.x.toFixed(1)}, ${last.y.toFixed(1)}, ${last.z.toFixed(1)}  (${new Date(last.at).toTimeString().slice(0, 8)})`)
console.log(`x: ${Math.min(...xs).toFixed(1)} .. ${Math.max(...xs).toFixed(1)}   y: ${Math.min(...ys).toFixed(1)} .. ${Math.max(...ys).toFixed(1)}   z: ${Math.min(...zs).toFixed(1)} .. ${Math.max(...zs).toFixed(1)}`)

// Dwell clusters: consecutive samples staying inside one small sphere.
const clusters = []
let current = undefined
for (const point of points) {
  if (!current || Math.hypot(point.x - current.x, point.y - current.y, point.z - current.z) > radius) {
    if (current && (current.until - current.from) / 1000 >= dwellSeconds)
      clusters.push(current)
    current = { from: point.at, until: point.at, x: point.x, y: point.y, z: point.z, count: 1 }
  }
  else {
    current.until = point.at
    current.count++
    current.x = point.x
    current.y = point.y
    current.z = point.z
  }
}
if (current && (current.until - current.from) / 1000 >= dwellSeconds)
  clusters.push(current)

console.log(`dwell clusters (>= ${dwellSeconds}s within ${radius} blocks): ${clusters.length}`)
for (const cluster of clusters) {
  const held = ((cluster.until - cluster.from) / 1000).toFixed(0)
  console.log(`  ${new Date(cluster.from).toTimeString().slice(0, 8)}  ${held.padStart(4)}s  ${cluster.count.toString().padStart(4)} pts  at ${cluster.x.toFixed(1)}, ${cluster.y.toFixed(1)}, ${cluster.z.toFixed(1)}`)
}

console.log('--- path (every 10th sample) ---')
for (let index = 0; index < points.length; index += 10) {
  const p = points[index]
  console.log(`  ${new Date(p.at).toTimeString().slice(0, 8)}  ${p.x.toFixed(1).padStart(9)} ${p.y.toFixed(1).padStart(7)} ${p.z.toFixed(1).padStart(9)}`)
}
