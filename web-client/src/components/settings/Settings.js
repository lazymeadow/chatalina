import './Settings.css'
import {FontAwesomeIcon} from '@fortawesome/react-fontawesome'
import {faExternalLink} from '@fortawesome/free-solid-svg-icons'
import {useNavigate} from 'react-router-dom'
import {Authentication} from '../../util/authentication'
import {Modal} from '../Modal'


export const Settings = ({show}) => {
	const navigate = useNavigate()

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