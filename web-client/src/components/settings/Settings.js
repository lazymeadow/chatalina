import './Settings.css'
import {FontAwesomeIcon} from '@fortawesome/react-fontawesome'
import {faExternalLink, faX} from '@fortawesome/free-solid-svg-icons'
import {Link} from 'react-router-dom'
import {Authentication} from '../../util/authentication'


export const Settings = () => {
	return (
		<div className={'Settings-root'}>
			<div className={'header'}>
				<h1>Settings</h1>
				<Link to={'/'}><FontAwesomeIcon icon={faX} aria-label={'Close Settings'} /></Link>
			</div>
			<div className={'body'}>
				<section>
					<h2>Links</h2>
					<a href={Authentication.createAccountUrl()}>
						Account management
						<FontAwesomeIcon icon={faExternalLink} />
					</a>
				</section>
			</div>
		</div>
	)
}