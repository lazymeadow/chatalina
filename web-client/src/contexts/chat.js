import {createContext, useCallback, useContext, useEffect, useReducer, useState} from 'react'
import EncryptionManager from '../util/encryption'

const ChatContext = createContext()
ChatContext.displayName = 'BestEvarChatContext'

export const useChat = () => useContext(ChatContext)

let websocket = null
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

export const ChatProvider = ({children}) => {
	const [ready, setReady] = useState(false)
	const [initialized, setInitialized] = useState(false)
	const [initializing, setInitializing] = useState(false)
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
		if (!initialized && !initializing) {
			setInitializing(true)
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
			setInitializing(false)
		}
	}, [initialized, initializing])


	useEffect(() => {
		encryptionManager.init().then(() => {
			setReady(encryptionManager.initialized)
		})
	}, [])

	return (
		<ChatContext.Provider value={{
			ready,
			initialized,
			initSocket,
			messageLog: messagesState.messageLog,
			sendMessage
		}}>
			{children}
		</ChatContext.Provider>
	)
}