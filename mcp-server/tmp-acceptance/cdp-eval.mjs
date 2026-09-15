const port = process.argv[2] ?? '9222'
const expression = process.argv[3] ?? 'document.title'
const match = process.argv[4] ?? 'synced-leader=true'

const targets = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json()
const target = targets.find(item => item.type === 'page' && item.url.includes(match))
if (!target) {
  console.error(`target not found for match "${match}"`)
  console.error(targets.filter(item => item.type === 'page').map(item => item.url).join('\n'))
  process.exit(1)
}

const ws = new WebSocket(target.webSocketDebuggerUrl)
await new Promise((resolve, reject) => {
  ws.onopen = resolve
  ws.onerror = reject
})

const id = 1
ws.send(JSON.stringify({
  id,
  method: 'Runtime.evaluate',
  params: { expression, awaitPromise: true, returnByValue: true },
}))
const message = await new Promise((resolve) => {
  ws.onmessage = (event) => {
    const parsed = JSON.parse(event.data)
    if (parsed.id === id)
      resolve(parsed)
  }
})
ws.close()

if (message.error) {
  console.log(JSON.stringify({ cdpError: message.error }, null, 2))
  process.exit(1)
}
const result = message.result
if (result.exceptionDetails) {
  console.log(JSON.stringify({ exception: result.exceptionDetails.exception?.description ?? result.exceptionDetails.text }, null, 2))
  process.exit(1)
}
console.log(typeof result.result?.value === 'string' ? result.result.value : JSON.stringify(result.result?.value, null, 2))
