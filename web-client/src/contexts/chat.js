import {createContext, useCallback, useContext, useEffect, useReducer, useRef, useState} from 'react'
import {useEncryption} from '../util/encryption'
import {Authentication} from '../util/authentication'


const ChatContext = createContext()
ChatContext.displayName = 'BestEvarChatContext'

export const useChat = () => useContext(ChatContext)

const apiUri = `http${process.env.REACT_APP_SERVER_SECURE ? 's' : ''}://${process.env.REACT_APP_SERVER_HOST}/api/v1/rpc`
const wsUri = `ws${process.env.REACT_APP_SERVER_SECURE ? 's' : ''}://${process.env.REACT_APP_SERVER_HOST}/chat`
let websocket = null

function messageReducer(state, action) {
	switch (action.type) {
		case 'add':
			// filter out the messages that are already in there
			const msgsToAdd = action.payload.filter(msg => !!state.log.find(m => m.id === msg.id) !== true)
			return {...state, log: [...state.log, ...msgsToAdd]}
		case 'new':
			return {...state, log: [...state.log, action.payload]}
		default:
			return state
	}
}

function notificationsReducer(state, action) {
	switch (action.type) {
		case 'allowSound':
			return {...state, allowSound: true}
		case 'enable':
			return {...state, enabled: true}
		case 'disable':
			return {...state, enabled: false, count: 0}
		case 'notify':
			return {
				...state,
				count: state.enabled ? (
					state.count + 1
				) : 0
			}
		default:
			return state
	}
}

function getNotificationsInitialState() {
	return {
		enabled: !window.document.hasFocus(),
		// if the window isn't focused when this page loads, we won't allow sound until it DOES get focus
		allowSound: window.document.hasFocus(),
		count: 0
	}
}

export const ChatProvider = ({children}) => {
	const {initialized: encryptionInitialized, encryption} = useEncryption()
	const [initialized, setInitialized] = useState(false)
	const [initializing, setInitializing] = useState(false)

	const [messagesState, messagesDispatch] = useReducer(messageReducer, {log: []})
	const [notificationsState, notificationsDispatch] = useReducer(
		notificationsReducer,
		{},
		getNotificationsInitialState
	)

	const handleWindowFocus = useCallback(() => {
		notificationsDispatch({type: 'disable'})
		notificationsDispatch({type: 'allowSound'})
	}, [])

	const handleWindowBlur = useCallback(() => {
		notificationsDispatch({type: 'enable'})
	}, [])

	const sendMessage = async (messageText) => {
		if (encryption.serverKey === null) {
			console.error('unable to send messages before key exchange')
		}
		else {
			const encrypted = await encryption.encrypt({
				type: 'text',
				message: messageText,
				sender: Authentication.getProfile().username,
				destination: 'bec'
			})
			const response = await fetch(apiUri, {
				method: 'POST',
				headers: {
					'BEC-Client-Key': encryption.getPublicKey(),
					'Authorization': 'Bearer ' + Authentication.getToken(),
					'Content-Type': 'application/json'
				},
				body: JSON.stringify({id: '2', jsonrpc: '2.0', method: 'sendMessage', params: encrypted}),
				mode: 'cors'
			})
			if (!response.ok) {
				console.log(response)
				console.error('error sending message')
			}
		}
	}

	const handleMessage = useCallback(async (messageEvent) => {
		const parsedMessage = JSON.parse(messageEvent.data)
		switch (parsedMessage.type) {
			case 'keyExchange': {
				// step 2: when server sends its key, send our key back
				// TODO: don't let chat be used until this happens
				await encryption.setServerKey(parsedMessage.content.key)
				websocket.send(JSON.stringify({
					jsonrpc: '2.0',
					method: 'keyExchange',
					params: {key: encryption.getPublicKey()}
				}))
				// NOW we're done...
				setInitialized(true)
				setInitializing(false)
				break
			}
			case 'newMessage': {
				const decrypted = await encryption.decrypt(parsedMessage.content)
				messagesDispatch({type: 'new', payload: {id: parsedMessage.id, ...decrypted}})
				notificationsDispatch({type: 'notify'})
				if (notificationsState.allowSound) {
					if (decrypted.sender === Authentication.getProfile().username) {
						sentSoundRef.current?.play()
					}
					else {
						receiveSoundRef.current?.play()
					}
				}
				break
			}
			default: {
				console.log('hmmm', parsedMessage)
			}
		}
	}, [notificationsState.allowSound, encryption])

	const connectSocket = useCallback((token) => {
		if (websocket == null) {
			websocket = new WebSocket(wsUri)

			websocket.onopen = () => {
				console.log(':)')
				// step 1: send access token to server
				websocket.send(JSON.stringify({method: 'authorization', jsonrpc: '2.0', params: {token}}))
			}

			websocket.onclose = () => {
				console.log(':(')
				// add timeout
				websocket = null
				window.confirm(':( your socket disconnected. you should refresh the page.')
			}

			websocket.onerror = (event) => {
				console.error(event)
			}

			websocket.onmessage = handleMessage
		}
	}, [handleMessage])

	const initChat = useCallback(async () => {
			if (encryptionInitialized && !initialized && !initializing) {
				window.onblur = handleWindowBlur
				window.onfocus = handleWindowFocus

				setInitializing(true)
				// first we'll make all the requests that we need to initialize everything
				const response = await fetch(apiUri, {
					method: 'POST',
					headers: {
						'BEC-Client-Key': encryption.getPublicKey(),
						'Authorization': 'Bearer ' + Authentication.getToken(),
						'Content-Type': 'application/json'
					},
					body: JSON.stringify({id: '2', jsonrpc: '2.0', method: 'getMessages'}),
					mode: 'cors'
				})
				if (response.ok) {
					await encryption.setServerKey(response.headers.get('BEC-Server-Key'))
					const responseBody = await response.json()
					const messages = responseBody.result.map(async message => {
						const decrypted = await encryption.decrypt(message.content)
						return {id: message.id, ...decrypted}
					})
					Promise.all(messages).then((msgs) => {
						messagesDispatch({type: 'add', payload: msgs})
					})
				}
				// then we connect the socket
				connectSocket(Authentication.getToken())
				// then we're done
				// setInitialized(true)
				// setInitializing(false)
				console.log(
					' ░▀█▀░▀█▀░█▀▀░░░▀█▀░█░█░█▀▀░░░█▀▀░█░█░█▀▀░█░█░▀█▀░█▀█                  \n'
					+ ' ░░█░░░█░░▀▀█░░░░█░░█▀█░█▀▀░░░█▀▀░█░█░█░░░█▀▄░░█░░█░█                 \n'
					+ ' ░▀▀▀░░▀░░▀▀▀░░░░▀░░▀░▀░▀▀▀░░░▀░░░▀▀▀░▀▀▀░▀░▀░▀▀▀░▀░▀                 \n'
					+ '                                                                      \n'
					+ ' ██████╗██╗  ██╗ █████╗ ████████╗ █████╗ ██╗     ██╗███╗   ██╗ █████╗ \n'
					+ '██╔════╝██║  ██║██╔══██╗╚══██╔══╝██╔══██╗██║     ██║████╗  ██║██╔══██╗\n'
					+ '██║     ███████║███████║   ██║   ███████║██║     ██║██╔██╗ ██║███████║\n'
					+ '██║     ██╔══██║██╔══██║   ██║   ██╔══██║██║     ██║██║╚██╗██║██╔══██║\n'
					+ '╚██████╗██║  ██║██║  ██║   ██║   ██║  ██║███████╗██║██║ ╚████║██║  ██║\n'
					+ ' ╚═════╝╚═╝  ╚═╝╚═╝  ╚═╝   ╚═╝   ╚═╝  ╚═╝╚══════╝╚═╝╚═╝  ╚═══╝╚═╝  ╚═╝\n'
					+ '                                                                      \n'
					+ '██╗    ██╗██╗███╗   ██╗███████╗                                       \n'
					+ '██║    ██║██║████╗  ██║██╔════╝                                       \n'
					+ '██║ █╗ ██║██║██╔██╗ ██║█████╗                                         \n'
					+ '██║███╗██║██║██║╚██╗██║██╔══╝                                         \n'
					+ '╚███╔███╔╝██║██║ ╚████║███████╗                                       \n'
					+ ' ╚══╝╚══╝ ╚═╝╚═╝  ╚═══╝╚══════╝                                       \n'
					+ '                                                                      \n'
					+ '███╗   ███╗██╗██╗  ██╗███████╗██████╗                                 \n'
					+ '████╗ ████║██║╚██╗██╔╝██╔════╝██╔══██╗                                \n'
					+ '██╔████╔██║██║ ╚███╔╝ █████╗  ██████╔╝                                \n'
					+ '██║╚██╔╝██║██║ ██╔██╗ ██╔══╝  ██╔══██╗                                \n'
					+ '██║ ╚═╝ ██║██║██╔╝ ██╗███████╗██║  ██║                                \n'
					+ '╚═╝     ╚═╝╚═╝╚═╝  ╚═╝╚══════╝╚═╝  ╚═╝                                '
				)
			}
		},
		[
			initialized,
			initializing,
			handleWindowBlur,
			handleWindowFocus,
			connectSocket,
			encryptionInitialized,
			encryption
		]
	)


	useEffect(() => {
		initChat()
	}, [initChat, encryptionInitialized])

	useEffect(() => {
		// for websocket to know about changed react callback, we must reset the handler when changed
		if (!!websocket && initialized) {
			websocket.onmessage = handleMessage
		}
	}, [initialized, handleMessage])

	const sentSoundRef = useRef(null)
	const receiveSoundRef = useRef(null)

	return (
		<ChatContext.Provider value={{
			initialized,
			initSocket: initChat,
			messageLog: messagesState.log,
			sendMessage,
			notificationCount: notificationsState.count
		}}>
			{children}
			<audio ref={sentSoundRef}
				   type={'audio/mpeg'}
				   src={'https://audio.bestevarchat.com/AIM/message-send.wav'}
			/>
			<audio ref={receiveSoundRef}
				   type={'audio/mpeg'}
				   src={'https://audio.bestevarchat.com/AIM/message-receive.wav'}
			/>
		</ChatContext.Provider>
	)
}