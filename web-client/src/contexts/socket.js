import {createContext, useCallback, useEffect, useState} from 'react'
import EncryptionManager from '../util/encryption'


export const SocketContext = createContext()
SocketContext.displayName = 'BestEvarSocketContext'

let websocket = null
// the socket is the only place we're encrypting/decrypting messages, so keep the encryption manager here
const encryptionManager = new EncryptionManager()

export const SocketProvider = ({children}) => {
	const [initialized, setInitialized] = useState(false)

	const connectSocket = (accessToken) => {
		if (websocket == null) {
			websocket = new WebSocket('ws://localhost:6969/chat')
			websocket.onopen = () => {
				// step 1: send access token to server
				websocket.send(JSON.stringify({id: '1', type: 'authorization', content: accessToken}))
			}
			websocket.onclose = () => {
				console.log(':(')
				// add timeout
				websocket = new WebSocket('ws://localhost:6969/chat')
			}
			websocket.onmessage = async messageEvent => {
				const parsedMessage = JSON.parse(messageEvent.data)
				switch (parsedMessage.type) {
					case 'keyExchange': {
						// step 2: when server sends its key, send our key back
						// TODO: don't let chat be used until this happens
						await encryptionManager.setServerKey(parsedMessage.content)
						websocket.send(JSON.stringify({type: 'keyExchange', content: encryptionManager.publicKey}))
						break
					}
					case 'newMessage': {
						console.log(parsedMessage)
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
		setInitialized(encryptionManager.initialized)
	}, [encryptionManager.initialized])

	return (
		<SocketContext.Provider value={{
			ready: initialized,
			connectSocket
		}}>
			{children}
		</SocketContext.Provider>
	)
}