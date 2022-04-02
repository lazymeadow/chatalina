import {createContext, useCallback, useEffect, useReducer, useState} from 'react'
import EncryptionManager from '../util/encryption'


export const SocketContext = createContext()
SocketContext.displayName = 'BestEvarSocketContext'

let websocket = null
// the socket is the only place we're encrypting/decrypting messages, so keep the encryption manager here
const encryptionManager = new EncryptionManager()

function messageReducer(state, action) {
	switch (action.type) {
		case 'add':
			// filter out the messages that are already in there
			const msgsToAdd = action.payload.filter(msg => !!state.messageLog.find(m => m.id === msg.id) !== true)
			return {...state, messageLog: [...state.messageLog, ...msgsToAdd]}
		case 'new':
			return {...state, messageLog: [...state.messageLog, action.payload]}
		default:
			return state
	}
}

export const SocketProvider = ({children}) => {
	const [ready, setReady] = useState(false)
	const [initialized, setInitialized] = useState(false)
	const [messagesState, messagesDispatch] = useReducer(messageReducer, {messageLog: []})

	const sendMessage = async (sender, messageText, accessToken) => {
		if (encryptionManager.serverKey === null) {
			console.error('unable to send messages before key exchange')
		}
		else {
			const encrypted = await encryptionManager.encrypt({
				type: 'text',
				message: messageText,
				sender,
				destination: 'bec'
			})
			const response = await fetch('http://localhost:6969/api/v1/rpc', {
				method: 'POST',
				headers: {
					'BEC-Client-Key': encryptionManager.publicKey,
					'Authorization': 'Bearer ' + accessToken,
					'Content-Type': 'application/json'
				},
				body: JSON.stringify({id: '2', jsonrpc: '2.0', method: 'sendMessage', params: encrypted}),
				mode: 'cors'
			})
			if (!response.ok) {
				console.error('error sending message')
			}
		}
	}

	const connectSocket = async (token) => {
		if (websocket == null) {
			websocket = new WebSocket('ws://localhost:6969/chat')
			websocket.onopen = () => {
				console.log(':)')
				// step 1: send access token to server
				websocket.send(JSON.stringify({method: 'authorization', jsonrpc: '2.0', params: {token}}))
			}

			websocket.onclose = () => {
				console.log(':(')
				// add timeout
				websocket = null
				// connectSocket(getAccessToken)
			}

			websocket.onerror = (event) => {
				console.error(event)
			}

			websocket.onmessage = async messageEvent => {
				const parsedMessage = JSON.parse(messageEvent.data)
				switch (parsedMessage.type) {
					case 'keyExchange': {
						// step 2: when server sends its key, send our key back
						// TODO: don't let chat be used until this happens
						await encryptionManager.setServerKey(parsedMessage.content.key)
						websocket.send(JSON.stringify({
							jsonrpc: '2.0',
							method: 'keyExchange',
							params: {key: encryptionManager.publicKey}
						}))
						break
					}
					case 'newMessage': {
						const decrypted = await encryptionManager.decrypt(parsedMessage.content)
						messagesDispatch({type: 'new', payload: {id: parsedMessage.id, ...decrypted}})
						break
					}
					default: {
						console.log('hmmm', parsedMessage)
					}
				}
			}
		}
	}

	const initSocket = useCallback(async (getAccessToken) => {
		if (!initialized) {
			let token = await getAccessToken()
			// first we'll make all the requests that we need to initialize everything

			const response = await fetch('http://localhost:6969/api/v1/rpc', {
				method: 'POST',
				headers: {
					'BEC-Client-Key': encryptionManager.publicKey,
					'Authorization': 'Bearer ' + token,
					'Content-Type': 'application/json'
				},
				body: JSON.stringify({id: '2', jsonrpc: '2.0', method: 'getMessages'}),
				mode: 'cors'
			})
			await encryptionManager.setServerKey(response.headers.get("BEC-Server-Key"))
			if (response.ok) {
				const responseBody = await response.json()
				const messages = responseBody.result.map(async message => {
					const decrypted = await encryptionManager.decrypt(message.content)
					return {id: message.id, ...decrypted}
				})
				Promise.all(messages).then((msgs) => {
					messagesDispatch({type: 'add', payload: msgs})
				})
			}
			// then we connect the socket
			connectSocket(token)
			// then we're done
			setInitialized(true)
		}
	}, [initialized])


	useEffect(() => {
		encryptionManager.init().then(() => {
			setReady(encryptionManager.initialized)
		})
	}, [])

	return (
		<SocketContext.Provider value={{
			ready,
			initialized,
			initSocket,
			messageLog: messagesState.messageLog,
			sendMessage
		}}>
			{children}
		</SocketContext.Provider>
	)
}