import './ChatLayout.css'
import {KeycloakContext} from '../../contexts/keycloak'
import {Link} from 'react-router-dom'
import {FontAwesomeIcon} from '@fortawesome/react-fontawesome'
import {faArrowTurnDown} from '@fortawesome/free-solid-svg-icons'
import {useContext, useRef, useState} from 'react'
import {SocketContext} from '../../contexts/socket'

const MessageLog = ({messages}) => {
	return (
		<div className={'log'}>
			{messages.map((message, index) => <p key={message.id || index}><span style={{fontWeight: "bold"}}>{message.sender || 'sender'}: </span>{message.message}</p>)}
		</div>
	)
}


export const ChatLayout = () => {
	const [typedMessage, setTypedMessage] = useState("")

	const {profile, getLogoutUrl} = useContext(KeycloakContext)

	const {messageLog, sendMessage} = useContext(SocketContext)

	const textareaEl = useRef(null)

	function handleSubmitChat() {
		sendMessage(profile.username, typedMessage)
		setTypedMessage('')
	}

	return (
		<>
			<div className={'ChatLayout-left'}>
				<h1> chat :)</h1>
				<p>hi, {profile.username}</p>
				<div className={'scrolly-bit'}>
					<p>scrolly stuff</p>
				</div>
				<div className={'bottom-stuff'}>
					<Link to={'/settings'}>Settings</Link>
					<a href={getLogoutUrl()}>Log out</a>
				</div>
			</div>
			<div className={'ChatLayout-right'}>
				<div className={'top-part'}>context</div>
				<MessageLog messages={messageLog} />
				<form className={'bottom-bar'} onSubmit={e => {
					e.preventDefault()
					handleSubmitChat()
				}}>
					<textarea ref={textareaEl} placeholder={'...'} rows={2} value={typedMessage} onChange={e => setTypedMessage(e.target.value)} onKeyDown={(e) => {
						if (e.key === 'Enter' && !e.shiftKey) {
							e.preventDefault()
							handleSubmitChat()
						}
					}}/>
					<button aria-label={'Send'} disabled={typedMessage.trim().length <= 0}><FontAwesomeIcon icon={faArrowTurnDown} rotation={90} size={'2x'}/></button>
				</form>
			</div>
		</>
	)
}