import './ChatLayout.css'
import {KeycloakContext} from '../../contexts/keycloak'
import {Link} from 'react-router-dom'
import {FontAwesomeIcon} from '@fortawesome/react-fontawesome'
import {faArrowTurnDown} from '@fortawesome/free-solid-svg-icons'
import {useContext, useRef, useState} from 'react'

const MessageLog = ({messages}) => {
	return (
		<div className={'log'}>
			<p>chat log!!!!!!!!</p>
			{messages.map((message, index) => <p key={index}><span>message: </span>{message}</p>)}
		</div>
	)
}


export const ChatLayout = () => {
	const [typedMessage, setTypedMessage] = useState("")
	const [messageLog, setMessageLog] = useState([])

	const {
		profile,
		keycloak: {createLogoutUrl}
	} = useContext(KeycloakContext)

	const textareaEl = useRef(null)

	function handleSubmitChat() {
		setMessageLog([...messageLog, typedMessage])
		setTypedMessage("")
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
					<a href={createLogoutUrl()}>Log out</a>
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