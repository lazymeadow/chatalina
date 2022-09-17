import {createContext, useCallback, useContext, useEffect, useReducer, useRef, useState} from 'react'
import {useEncryption} from '../util/encryption'
import {Authentication} from '../util/authentication'
import {
	attempt,
	attemptReconnect,
	getNewWebsocket,
	isWebsocketReady,
	resetReconnect,
	sendWebsocketRpc,
	setWebsocketOnMessage
} from '../util/socket'
import {Modal} from '../components/Modal'
import {useSettings} from './settings'


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
			key: false,
			messages: false,
			parasites: false,
			groups: false
		}
	}
}

let getMessagesId, getParasitesId, getGroupsId

function chatDataReducer(state, action) {
	switch (action.type) {
		case 'messages':
			// filter out the messages that are already in there
			const msgsToAdd = action.payload.filter(msg => !!state.messages.find(m => m.id === msg.id) !== true)
			return {...state, messages: [...state.messages, ...msgsToAdd]}
		case 'new message':
			const {message, shouldSetUnread} = action.payload
			const foundMessage = state.messages.findIndex(m => m.id === message.id) >= 0
			if (foundMessage) {
				return {...state}
			}

			const unread = state.parasites.find(p => p.jid === message.destination) || state.groups.find(g => g.jid
				=== message.destination)
			if (shouldSetUnread && !!unread) {
				unread.unread = true
			}
			return {...state, messages: [...state.messages, message]}
		case 'parasites':
			const parasitesToAdd = action.payload.filter(parasite => !!state.parasites.find(p => p.jid === parasite.jid)
				!== true)
			return {...state, parasites: [...state.parasites, ...parasitesToAdd]}
		case 'groups':
			const groupsToAdd = action.payload.filter(group => !!state.groups.find(g => g.jid === group.jid) !== true)
			return {...state, groups: [...state.groups, ...groupsToAdd]}
		case 'set read':
			const read = state.parasites.find(p => p.jid === action.payload) || state.groups.find(g => g.jid
				=== action.payload)
			if (!!read) {
				read.unread = false
			}
			return {...state}
		default:
			return state
	}
}

function getChatDataInitialState() {
	return {
		messages: [],
		parasites: [],
		groups: []
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

	const [showModal, setShowModal] = useState(false)
	const [showRetry, setShowRetry] = useState(false)
	const [error, setError] = useState(null)
	const [initMessage, setInitMessage] = useState('')

	const {currentDest} = useSettings()

	const [initializationState, initializationDispatch] = useReducer(
		initializationReducer,
		{},
		getInitializationInitialState
	)
	const [chatDataState, chatDataDispatch] = useReducer(chatDataReducer, {}, getChatDataInitialState)
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

	const setRead = (destination) => chatDataDispatch({type: 'set read', payload: destination})

	const refreshAuth = () => {
		if (isWebsocketReady()) {
			console.log('refreshing authorization with server')
			sendWebsocketRpc('authorization', {token: Authentication.getToken()})
		}
	}

	const processMessage = useCallback(async (messageContent) => {
		const decrypted = await encryption.decrypt(messageContent)
		decrypted.time = new Date(decrypted.time)
		chatDataDispatch({
			type: 'new message',
			payload: {
				shouldSetUnread: decrypted.destination !== currentDest,
				message: decrypted
			}
		})
		notificationsDispatch({type: 'notify'})
		if (notificationsState.allowSound) {
			if (decrypted.sender === Authentication.getProfile().username) {
				sentSoundRef.current?.play()
			} else {
				receiveSoundRef.current?.play()
			}
		}
	}, [currentDest, encryption, notificationsState.allowSound])

	const sendMessage = useCallback(async (messageText, destination) => {
		if (encryption.serverKey === null) {
			console.error('unable to send messages before key exchange')
		} else {
			const encrypted = await encryption.encrypt({
				type: 'text',
				message: messageText,
				sender: Authentication.getProfile().username,
				destination
			})
			// we send messages via http. the response is usually faster than the socket, so we save it.
			const response = await fetch(apiUri, {
				method: 'POST',
				headers: {
					'BEC-Client-Key': encryption.getPublicKey(),
					'Authorization': 'Bearer ' + Authentication.getToken(),
					'Content-Type': 'application/json'
				},
				body: JSON.stringify({id: '2', jsonrpc: '2.0', method: 'messages.send', params: encrypted}),
				mode: 'cors'
			})
			if (!response.ok) {
				console.log(response)
				console.error('error sending message')
			} else {
				const responseBody = await response.json()
				processMessage(responseBody.result)
			}
		}
	}, [encryption, processMessage])

	const wsOnMessage = useCallback(async (messageEvent) => {
		const parsedMessage = JSON.parse(messageEvent.data)
		if (parsedMessage.hasOwnProperty('error')) {
			setError(`The server returned an error: ${JSON.stringify(parsedMessage.error)}`)
			return
		}
		switch (parsedMessage.method) {
			case 'encryption.key': {
				// step 2: when server sends its key, send our key back
				await encryption.setServerKey(parsedMessage.params.key)
				sendWebsocketRpc('encryption.key', {key: encryption.getPublicKey()})
				initializationDispatch({type: 'step done', payload: 'key'})
				// we know we're authenticated now, but lets make sure we're sending an updated token over
				Authentication.addRefreshCallback(refreshAuth)
				break
			}
			case 'messages.new': {
				const messageContent = parsedMessage.params
				processMessage(messageContent)
				break
			}
			case undefined: {
				// messages without a type are responses, so they have an id
				if (parsedMessage.id === getMessagesId) {
					const messages = parsedMessage.result.map(async encrypted => {
						const message =  await encryption.decrypt(encrypted)
						message.time = new Date(message.time)
						return message
					})
					Promise.all(messages).then((msgs) => {
						msgs.sort((a, b) => a.time - b.time)
						chatDataDispatch({type: 'messages', payload: msgs})
					})
					initializationDispatch({type: 'step done', payload: 'messages'})
				} else if (parsedMessage.id === getParasitesId) {
					chatDataDispatch({type: 'parasites', payload: parsedMessage.result})
					initializationDispatch({type: 'step done', payload: 'parasites'})
				} else if (parsedMessage.id === getGroupsId) {
					chatDataDispatch({type: 'groups', payload: parsedMessage.result})
					initializationDispatch({type: 'step done', payload: 'groups'})
				}
				break
			}
			default: {
				console.log('hmmm', parsedMessage, parsedMessage.type)
			}
		}
	}, [encryption, processMessage])

	const wsOnOpen = useCallback(() => {
		console.log(':)')
		resetReconnect()
		initializationDispatch({type: 'step done', payload: 'socket'})
		setShowModal(false)
		// step 1: send access token to server
		sendWebsocketRpc('authorization', {token: Authentication.getToken()})
	}, [])

	const reconnectSocket = useCallback(() => {
		Authentication.refresh()
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
		if (initializationState.working
			&& initializationState.steps.key
			&& !initializationState.steps.messages) {
			getMessagesId = sendWebsocketRpc('messages.get')
			getParasitesId = sendWebsocketRpc('parasites.get')
			getGroupsId = sendWebsocketRpc('groups.get')
		}
	}, [initializationState.working, initializationState.steps])

	useEffect(() => {
		if (initializationState.working) {
			setInitMessage(`Reticulating splines... ${initializationState.done ? 'done' : ''}
			Connecting socket... ${initializationState.steps.socket ? 'done' : ''}
			Exchanging keys... ${initializationState.steps.key ? 'done' : ''}
			Retrieving parasites... ${initializationState.steps.parasites ? 'done' : ''}
			Requesting groups... ${initializationState.steps.groups ? 'done' : ''}
			Decrypting messages... ${initializationState.steps.messages ? 'done' : ''}`)
		}
	}, [initializationState.working, initializationState.done, initializationState.steps])

	const sentSoundRef = useRef(null)
	const receiveSoundRef = useRef(null)

	return (
		<ChatContext.Provider value={{
			initialized: initializationState.done,
			initMessage,
			messages: chatDataState.messages,
			parasites: chatDataState.parasites,
			groups: chatDataState.groups,
			setRead,
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
						Retry
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