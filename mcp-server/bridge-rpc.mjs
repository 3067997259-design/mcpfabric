/**
 * Calls one RPC method on an in-game mcpfabric bridge over its HTTP endpoint.
 *
 * The bridge speaks `POST /rpc` with a bearer token from the client's
 * `config/mcpfabric.config.json`; the MCP wrappers (25600/25602) only forward to
 * it. This script exists for the port that has no MCP wrapper, which is how the
 * human client's own chat is reached: AIRI's chat command pipeline only accepts
 * admins, and the bot cannot command itself (`classifyChatEvent` rejects
 * `senderUuid === self.uuid`).
 *
 * Usage: node bridge-rpc.mjs <port> <method> '<json args>'
 *        node bridge-rpc.mjs <port> <method> @<path to json file>
 *
 * The file form exists because cmd.exe and PowerShell strip the inner quotes of
 * a JSON argument, which turns `{"message":"hi"}` into `{message:hi}`.
 */
import { readFileSync } from 'node:fs'

const [portRaw, method, argsRaw] = process.argv.slice(2)
const port = Number(portRaw ?? 25599)
const token = process.env.MCPFABRIC_TOKEN ?? '4065cb42ca7b48e2a4c7d35e9ad20378'
if (!method) {
  console.error('usage: node bridge-rpc.mjs <port> <method> \'<json args>\' | @<file>')
  process.exit(2)
}
const argsText = argsRaw?.startsWith('@')
  ? readFileSync(argsRaw.slice(1), 'utf8').replace(/^\uFEFF/, '')
  : argsRaw
const response = await fetch(`http://127.0.0.1:${port}/rpc`, {
  method: 'POST',
  headers: { 'content-type': 'application/json', 'authorization': `Bearer ${token}` },
  body: JSON.stringify({ method, params: argsText ? JSON.parse(argsText) : {} }),
})
const text = await response.text()
console.log(`${response.status} ${text.slice(0, 4000)}`)
