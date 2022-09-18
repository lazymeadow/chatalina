import './Settings.css'
import {FontAwesomeIcon} from '@fortawesome/react-fontawesome'
import {faExternalLink} from '@fortawesome/free-solid-svg-icons'
import {useNavigate} from 'react-router-dom'
import {Authentication} from '../../util/authentication'
import {Modal} from '../Modal'
import {useChat} from '../../contexts/chat'
import {useCallback, useState} from 'react'
import {useSettings} from '../../contexts/settings'


export const Settings = ({show}) => {
	const navigate = useNavigate()
	const {updateParasite} = useChat()
	const {displayName} = useSettings()
	const [newDisplayName, setNewDisplayName] = useState(displayName)
	const [error, setError] = useState(null)
	const [message, setMessage] = useState(null)

	const handleSaveParasite = useCallback(()=> {
		try {
			updateParasite({displayName: newDisplayName})
			setError(null)
			setMessage("Updated successfully")
			window.setTimeout(() => {
				setMessage(null)
			}, 3000)
		} catch (e) {
			setMessage(null)
			setError(e.message)
		}
	}, [newDisplayName, updateParasite])
	
	return (
		<Modal show={show}>
			<div className={'Settings-root'}>
				<div className={'header'}>
					<h1>Settings</h1>
				</div>
				<div className={'body'}>
					<section>
						<section>
							<h2>Other stuff</h2>
							<p>It'll be here later</p>
							<label htmlFor={'display-name'}>Update display name:</label>
							<input type={'text'} id={'display-name'} value={newDisplayName} onChange={(event) => setNewDisplayName(event.target.value)} />
							<button onClick={handleSaveParasite}>Save</button>
							{!!message && <p>{message}</p>}
							{!!error && <p>{error}</p>}
						</section>
						<h2>Account</h2>
						<a href={Authentication.createAccountUrl()}>
							Account management
							<FontAwesomeIcon icon={faExternalLink} />
						</a>
					</section>
				</div>
				<div className={'footer'}>
					<button onClick={() => navigate('/')}>Close</button>
				</div>
			</div>
		</Modal>
	)
}