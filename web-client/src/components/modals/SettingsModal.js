import './SettingsModal.css'
import {FontAwesomeIcon} from '@fortawesome/react-fontawesome'
import {faExternalLink} from '@fortawesome/free-solid-svg-icons'
import {useNavigate} from 'react-router-dom'
import {Authentication} from '../../util/authentication'
import {Modal} from './Modal'
import {useChat} from '../../contexts/chat'
import {useCallback, useState} from 'react'
import {useSettings} from '../../contexts/settings'


export const SettingsModal = ({show}) => {
	const navigate = useNavigate()
	const {updateSettings} = useChat()
	const {displayName} = useSettings()
	const [newDisplayName, setNewDisplayName] = useState(displayName)
	const [error, setError] = useState(null)
	const [message, setMessage] = useState(null)

	const handleSaveSettings = useCallback(() => {
		try {
			updateSettings({displayName: newDisplayName})
			setError(null)
			setMessage('Updated successfully')
			window.setTimeout(() => {
				setMessage(null)
			}, 3000)
		} catch (e) {
			setMessage(null)
			setError(e.message)
		}
	}, [newDisplayName, updateSettings])

	return (
		<Modal show={show}>
			<div className={'Settings-root'}>
				<div className={'header'}>
					<h1>Settings</h1>
				</div>
				<div className={'body'}>
					<section>
						<h2>Account</h2>
						<a href={Authentication.createAccountUrl()}>
							Account management
							<FontAwesomeIcon icon={faExternalLink} />
						</a>
					</section>
					<section>
						<h2>Other stuff</h2>
						<p>It'll be here later</p>
						<label htmlFor={'display-name'}>Update display name:</label>
						<input type={'text'} id={'display-name'} value={newDisplayName}
							   onChange={(event) => setNewDisplayName(event.target.value)} />
						{!!message && <p>{message}</p>}
						{!!error && <p>{error}</p>}
					</section>
				</div>
				<div className={'footer'}>
					<button onClick={() => navigate('/')} className={'secondary'}>Cancel</button>
					<button onClick={handleSaveSettings}>Save</button>
				</div>
			</div>
		</Modal>
	)
}
