/**
 * Sends one chat line as the client's own player through the in-game bridge.
 *
 * This is how a command reaches AIRI: it polls MC chat and only delivers a turn
 * for an admin (or a mention), and it drops any line whose sender uuid is its own
 * (`classifyChatEvent`, reason `self`). The bot therefore cannot command itself,
 * so the human client's bridge is the only entry for a chat-driven acceptance.
 *
 * The message is a plain argument, not JSON: cmd.exe and PowerShell strip inner
 * quotes from a JSON argument, which silently turns a payload into a syntax
 * error.
 *
 * Usage: node chat-send.mjs <port> <message...>
 */
const [portRaw, ...words] = process.argv.slice(2)
const port = Number(portRaw ?? 25599)
const message = words.join(' ')
const token = process.env.MCPFABRIC_TOKEN ?? '4065cb42ca7b48e2a4c7d35e9ad20378'
if (!message) {
  console.error('usage: node chat-send.mjs <port> <message...>')
  process.exit(2)
}
const response = await fetch(`http://127.0.0.1:${port}/rpc`, {
  method: 'POST',
  headers: { 'content-type': 'application/json', 'authorization': `Bearer ${token}` },
  body: JSON.stringify({ method: 'chat.send', params: { message } }),
})
console.log(`${response.status} ${(await response.text()).slice(0, 500)}`)
