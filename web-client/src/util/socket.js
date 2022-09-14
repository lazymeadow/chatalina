export let websocket = null

export function getNewWebsocket(uri, onopen, onclose, onmessage) {
	websocket = new WebSocket(uri)
	websocket.onopen = onopen
	websocket.onclose = onclose
	websocket.onmessage = onmessage
}

export function setWebsocketOnMessage(callback) {
	websocket.onmessage = callback
}

let timeout = null
export let attempt = 0
let enabled = true

export function attemptReconnect(callback) {
	websocket = null
	enabled = attempt < 3
	if (enabled) {
		if (!!timeout) {
			window.clearTimeout(timeout)
		}
		attempt = attempt + 1
		console.log(`attempting reconnect #${attempt}`)
		timeout = window.setTimeout(
			callback,
			(
				attempt === 1
			) ? 500 : 3500
		)
	} else {
		console.log(`tried to reconnect ${attempt} time(s), giving up`)
	}
	return enabled
}

export function resetReconnect() {
	attempt = 0
	if (!!timeout) {
		window.clearTimeout(timeout)
	}
	timeout = null
	enabled = true
}

let messageId = 0

export function sendWebsocketRpc(method, params) {
	messageId++
	websocket.send(JSON.stringify({jsonrpc: '2.0', id: messageId, method, params}))
	return messageId.toString()
}