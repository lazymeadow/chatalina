import './ChatLayout.css'
import {Link, useSearchParams} from 'react-router-dom'
import {FontAwesomeIcon} from '@fortawesome/react-fontawesome'
import {faArrowTurnDown, faAsterisk, faPlus} from '@fortawesome/free-solid-svg-icons'
import {useLayoutEffect, useState} from 'react'
import {useChat} from '../../contexts/chat'
import {Authentication} from '../../util/authentication'
import {useSettings} from '../../contexts/settings'
import {MessageLog} from './MessageLog'
import {NewGroupModal, SettingsModal} from '../modals'


const LeftBar = ({username, logoutUrl, parasites, groups, onDest, currentDest}) => {
	return (
		<>
			<h1>{process.env.REACT_APP_TITLE}</h1>
			<p>hi, {username}</p>
			<div className={'scrolly-bit'}>
				<div className={'list-header'}>
					<h2>Groups</h2>
					<Link to={'/?m=new-group'}>
						<FontAwesomeIcon icon={faPlus} aria-label={'create a new group'} />
					</Link>
				</div>
				<ul>
					{groups.map(group => (
						<li key={group.jid}
							onClick={() => onDest(group.jid)}
							className={`${group.unread ? 'unread' : ''} ${group.jid === currentDest
								? 'current'
								: ''}`.trim()}
						>
							{group.name}{group.unread && (
							<FontAwesomeIcon icon={faAsterisk} aria-label={'unread messages'} />
						)}
						</li>
					))}
				</ul>
				<div className={'list-header'}>
					<h2>Parasites</h2>
				</div>
				<ul>
					{parasites.map(parasite => (
						<li key={parasite.jid}
							onClick={() => onDest(parasite.jid)}
							className={`${parasite.unread ? 'unread' : ''} ${parasite.jid === currentDest
								? 'current'
								: ''}`.trim()}
						>
							{parasite.displayName}{parasite.unread && (
							<FontAwesomeIcon icon={faAsterisk} aria-label={'unread messages'} />
						)}
						</li>
					))}
				</ul>
			</div>
			<div className={'bottom-stuff'}>
				<Link to={'/?m=settings'}>Settings</Link>
				<a href={logoutUrl}>Log out</a>
			</div>
		</>
	)
}

let autoScroll = true

export const ChatLayout = () => {
	const [typedMessage, setTypedMessage] = useState('')
	const [lastCount, setLastCount] = useState(0)  // change was triggering twice

	const {messages, parasites, groups, sendMessage, notificationCount, setRead} = useChat()
	const {currentDest, displayName, setDest} = useSettings()

	const [searchParams] = useSearchParams()

	async function handleSubmitChat() {
		sendMessage(typedMessage, currentDest)
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
	}, [messages, currentDest])

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

	const handleClickDestination = (destination) => {
		setDest(destination)
		setRead(destination)
	}

	const destName = groups.find(g => g.jid === currentDest)?.name || parasites.find(p => p.jid
		=== currentDest)?.displayName || '-'

	return (
		<>
			<div className={'ChatLayout-left'}>
				<LeftBar username={displayName || 'there'}
						 logoutUrl={Authentication.getLogoutUrl()}
						 parasites={parasites}
						 groups={groups}
						 onDest={handleClickDestination}
						 currentDest={currentDest}
				/>
			</div>
			<div className={'ChatLayout-right'}>
				<div className={'top-part'}>
					{destName}
				</div>
				<div className={'middle-guy'} id={'middle'} onScrollCapture={updateAutoScroll}>
					<MessageLog messages={messages.filter(m => m.destination === currentDest)}
								currentDest={currentDest} />
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
					<button aria-label={'Send'} disabled={typedMessage.trim().length <= 0}>
						<FontAwesomeIcon icon={faArrowTurnDown} rotation={90} size={'2x'} />
					</button>
				</form>
			</div>
			{searchParams.get('m') === 'settings' && <SettingsModal show={true} />}
			{searchParams.get('m') === 'new-group' && <NewGroupModal show={true} />}
		</>
	)
}