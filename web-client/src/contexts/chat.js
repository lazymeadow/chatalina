import {createContext, useCallback, useContext, useEffect, useReducer, useRef, useState} from 'react'
import {useEncryption} from '../util/encryption'
import {Authentication} from '../util/authentication'
import {
	attempt,
	attemptReconnect,
	getNewWebsocket,
	resetReconnect,
	sendWebsocketRpc,
	setWebsocketOnMessage
} from '../util/socket'
import {Modal} from '../components/Modal'


const ChatContext = createContext()
ChatContext.displayName = 'BestEvarChatContext'

export const useChat = () => useContext(ChatContext)

const secureServer = process.env.REACT_APP_SERVER_SECURE === 'true'
const apiUri = `http${secureServer ? 's' : ''}://${process.env.REACT_APP_SERVER_HOST}/api/v1/rpc`
const wsUri = `ws${secureServer ? 's' : ''}://${process.env.REACT_APP_SERVER_HOST}/chat`

function initializationReducer(state, action) {
	switch (action.type) {
		case 'step done':
			const newSteps = {...state.steps, [action.payload]: true}
			const done = Object.values(newSteps).reduce((acc, curr) => acc && curr, true)
			return {...state, done, steps: newSteps}
		case 'start':
			return {...state, working: true}
		case 'reset':
			return getInitializationInitialState()
		default:
			return state
	}
}

function getInitializationInitialState() {
	return {
		working: false,
		done: false,
		steps: {
			socket: false,
			keyExchange: false,
			messages: false
		}
	}
}

let getMessagesId

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

function getMessagesInitialState() {
	return {log: []}
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

	const [showModal, setShowModal] = useState(false)
	const [showRetry, setShowRetry] = useState(false)
	const [error, setError] = useState(null)
	const [initMessage, setInitMessage] = useState('')

	const [initializationState, initializationDispatch] = useReducer(
		initializationReducer,
		{},
		getInitializationInitialState
	)
	const [messagesState, messagesDispatch] = useReducer(messageReducer, {}, getMessagesInitialState)
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
		} else {
			const encrypted = await encryption.encrypt({
				type: 'text',
				message: messageText,
				sender: Authentication.getProfile().username,
				destination: 'bec/1'
			})
			// we send messages via http. the response is usually faster than the socket, so we save it.
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

	const wsOnMessage = useCallback(async (messageEvent) => {
		const parsedMessage = JSON.parse(messageEvent.data)
		switch (parsedMessage.type) {
			case 'keyExchange': {
				// step 2: when server sends its key, send our key back
				await encryption.setServerKey(parsedMessage.content.key)
				sendWebsocketRpc('keyExchange', {key: encryption.getPublicKey()})
				initializationDispatch({type: 'step done', payload: 'keyExchange'})
				break
			}
			case 'newMessage': {
				const decrypted = await encryption.decrypt(parsedMessage.content)
				messagesDispatch({type: 'new', payload: {id: parsedMessage.id, time: parsedMessage.time, ...decrypted}})
				notificationsDispatch({type: 'notify'})
				if (notificationsState.allowSound) {
					if (decrypted.sender === Authentication.getProfile().username) {
						sentSoundRef.current?.play()
					} else {
						receiveSoundRef.current?.play()
					}
				}
				break
			}
			case undefined: {
				// messages without a type are responses, so they have an id
				if (parsedMessage.id === getMessagesId) {
					const messages = parsedMessage.result.map(async message => {
						const decrypted = await encryption.decrypt(message.content)
						return {id: message.id, time: new Date(message.time), ...decrypted}
					})
					Promise.all(messages).then((msgs) => {
						msgs.sort((a, b) => a.time - b.time)
						messagesDispatch({type: 'add', payload: msgs})
					})
					initializationDispatch({type: 'step done', payload: 'messages'})
				}
				break
			}
			default: {
				console.log('hmmm', parsedMessage, parsedMessage.type)
			}
		}
	}, [notificationsState.allowSound, encryption])

	const wsOnOpen = useCallback(() => {
		console.log(':)')
		resetReconnect()
		initializationDispatch({type: 'step done', payload: 'socket'})
		// step 1: send access token to server
		sendWebsocketRpc('authorization', {token: Authentication.getToken()})
	}, [])

	const reconnectSocket = useCallback(() => {
		setShowModal(true)
		const willRetryAgain = attemptReconnect(() => getNewWebsocket(wsUri, wsOnOpen, reconnectSocket, wsOnMessage))
		if (!willRetryAgain) {
			setError(':( Error connecting socket. Retry?')
			setShowRetry(true)
		} else {
			setError(`Socket disconnected! Attempting reconnect #${attempt}`)
			setShowRetry(false)
		}
	}, [wsOnOpen, wsOnMessage])

	const connectSocket = useCallback(() => {
		getNewWebsocket(wsUri, wsOnOpen, reconnectSocket, wsOnMessage)
	}, [wsOnOpen, wsOnMessage, reconnectSocket])

	const initChat = useCallback(
		async () => {
			initializationDispatch({type: 'start'})
			window.onblur = handleWindowBlur
			window.onfocus = handleWindowFocus

			// start by connecting the socket. that's where we'll do our key exchange.
			connectSocket()
		},
		[
			handleWindowBlur,
			handleWindowFocus,
			connectSocket
		]
	)

	useEffect(() => {
		if (encryptionInitialized && !initializationState.done && !initializationState.working) {
			initChat()
		}
	}, [initChat, encryptionInitialized, initializationState.done, initializationState.working])

	useEffect(() => {
		// for websocket to know about changed react callback, we must reset the handler
		if (initializationState.done) {
			setWebsocketOnMessage(wsOnMessage)
		}
	}, [initializationState.done, wsOnMessage])

	useEffect(() => {
		// once the key exchange is done, we can send the rest of the intialization messages
		if (initializationState.working && initializationState.steps.keyExchange && !initializationState.steps.messages) {
			getMessagesId = sendWebsocketRpc('getMessages')
		}
	}, [initializationState.working, initializationState.steps])

	useEffect(() => {
		if (initializationState.working) {
			setInitMessage('Reticulating splines...\n' + JSON.stringify(initializationState.steps))
		}
	}, [initializationState.working, initializationState.steps])

	const sentSoundRef = useRef(null)
	const receiveSoundRef = useRef(null)

	return (
		<ChatContext.Provider value={{
			initialized: initializationState.done,
			initMessage,
			messageLog: messagesState.log,
			sendMessage,
			notificationCount: notificationsState.count
		}}>
			{children}
			<Modal show={showModal}>
				<p>
					{error}
				</p>
				{!!showRetry && (
					<button onClick={() => initializationDispatch({type: 'reset'})}>
					{/*<button onClick={() => window.location.reload()}>*/}
						Reload
					</button>
				)}
			</Modal>
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