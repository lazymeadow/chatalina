import './ChatLayout.css'
import {Link} from 'react-router-dom'
import {FontAwesomeIcon} from '@fortawesome/react-fontawesome'
import {faArrowTurnDown} from '@fortawesome/free-solid-svg-icons'
import {useLayoutEffect, useState} from 'react'
import {useChat} from '../../contexts/chat'
import {Authentication} from '../../util/authentication'


const MessageLog = ({messages}) => {
	return (
		<div className={'log'}>
			{messages.map((message, index) => (
				<p key={message.id || index}>
					<span style={{fontStyle: 'italic'}}>
						[{new Date(message.time).toLocaleString()}]&nbsp;&nbsp;
					</span>
					<span style={{fontWeight: 'bold'}}>
						{`${message.sender || 'sender'}: `}
					</span>
					{message.message}
				</p>
			))}
		</div>
	)
}

const LeftBar = ({username, logoutUrl, parasites, groups}) => {
	return (
		<>
			<h1>{process.env.REACT_APP_TITLE}</h1>
			<p>hi, {username}</p>
			<div className={'scrolly-bit'}>
				<h2>Groups</h2>
				<ul>
					{groups.map(group => (
						<li key={group.jid}>
							{group.name}
						</li>
					))}
				</ul>
				<h2>Parasites</h2>
				<ul>
					{parasites.map(parasite => (
						<li key={parasite.jid}>
							{parasite.displayName}
						</li>
					))}
				</ul>
			</div>
			<div className={'bottom-stuff'}>
				<Link to={'/settings'}>Settings</Link>
				<a href={logoutUrl}>Log out</a>
			</div>
		</>
	)
}

let autoScroll = true

export const ChatLayout = () => {
	const [typedMessage, setTypedMessage] = useState('')
	const [lastCount, setLastCount] = useState(0)  // change was triggering twice

	const {messages, parasites, groups, sendMessage, notificationCount} = useChat()

	async function handleSubmitChat() {
		sendMessage(typedMessage)
		setTypedMessage('')
	}

	function updateAutoScroll(event) {
		const eventLog = event.currentTarget
		const scrollThreshold = (
			(
				eventLog.scrollHeight / 4
			) > 100
		) ? (
			eventLog.scrollHeight / 4
		) : 100
		autoScroll = Math.abs(eventLog.scrollTopMax - eventLog.scrollTop) < scrollThreshold
	}

	useLayoutEffect(() => {
		if (autoScroll) {
			const log = document.getElementById('middle')
			!!log && log.scrollTo({top: log.scrollTopMax})
		}
	}, [messages])

	useLayoutEffect(() => {
		if (notificationCount !== lastCount) {
			setLastCount(notificationCount)
			let faviconPath = 'favicon.ico'
			let windowTitle = process.env.REACT_APP_TITLE
			if (notificationCount > 0) {
				faviconPath = 'favicon2.png'
				windowTitle = `(${notificationCount}) ` + windowTitle
			}
			document.getElementById('favicon').href = faviconPath
			window.document.title = windowTitle
		}
	}, [lastCount, notificationCount])

	return (
		<>
			<div className={'ChatLayout-left'}>
				<LeftBar username={Authentication.getProfile().username || 'there'}
						 logoutUrl={Authentication.getLogoutUrl()}
						 parasites={parasites}
						 groups={groups}
				/>
			</div>
			<div className={'ChatLayout-right'}>
				<div className={'top-part'}>
					{groups[0].name}
				</div>
				<div className={'middle-guy'} id={'middle'} onScrollCapture={updateAutoScroll}>
					<MessageLog messages={messages} />
				</div>
				<form className={'bottom-bar'} onSubmit={e => {
					e.preventDefault()
					handleSubmitChat()
				}}>
					<textarea placeholder={'...'} rows={2} value={typedMessage}
							  onChange={e => setTypedMessage(e.target.value)}
							  onKeyDown={(e) => {
								  if (e.key === 'Enter' && !e.shiftKey) {
									  e.preventDefault()
									  handleSubmitChat()
								  }
							  }}
					/>
					<button aria-label={'Send'} disabled={typedMessage.trim().length <= 0}><FontAwesomeIcon
						icon={faArrowTurnDown} rotation={90} size={'2x'} /></button>
				</form>
			</div>
		</>
	)
}