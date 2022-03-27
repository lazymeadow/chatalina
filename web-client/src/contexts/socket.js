import {createContext, useCallback, useEffect, useReducer, useState} from 'react'
import EncryptionManager from '../util/encryption'


export const SocketContext = createContext()
SocketContext.displayName = 'BestEvarSocketContext'

let websocket = null
// the socket is the only place we're encrypting/decrypting messages, so keep the encryption manager here
const encryptionManager = new EncryptionManager()

function messageReducer(state, action) {
	switch (action.type) {
		case 'new':
			return {...state, messageLog: [...state.messageLog, action.payload]}
		default:
			return state
	}
}

export const SocketProvider = ({children}) => {
	const [initialized, setInitialized] = useState(false)
	const [messagesState, messagesDispatch] = useReducer(messageReducer, {messageLog: []})

	const sendMessage = async (sender, messageText, accessToken) => {
		if (encryptionManager.serverKey === null) {
			console.error('unable to send messages before key exchange')
		}
		else {
			const encrypted = await encryptionManager.encrypt({type: 'text', message: messageText, sender})
			const response = await fetch('http://localhost:6969/api/v1/rpc', {
				method: 'POST',
				headers: {
					'BEC-Client-Key': encryptionManager.publicKey,
					'Authorization': 'Bearer ' + accessToken,
					'Content-Type': 'application/json'
				},
				body: JSON.stringify({id: '2', jsonrpc: '2.0', method: 'sendMessage', params: encrypted}),
				mode: 'cors',
			})
			if (!response.ok) {
				console.error('error sending message')
			}
		}
	}

	const connectSocket = async (getAccessToken) => {
		let token = await getAccessToken()
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
						messagesDispatch({type: 'new', payload: {id: parsedMessage.content.id, ...decrypted}})
						break
					}
					default: {
						console.log('hmmm', parsedMessage)
					}
				}
			}
		}
	}

	useEffect(() => {
		encryptionManager.init().then(() => {
			setInitialized(encryptionManager.initialized)
		})
	}, [])

	return (
		<SocketContext.Provider value={{
			ready: initialized,
			connectSocket,
			messageLog: messagesState.messageLog,
			sendMessage
		}}>
			{children}
		</SocketContext.Provider>
	)
}