import {KeycloakContext} from '../contexts/keycloak'
import {FontAwesomeIcon} from '@fortawesome/react-fontawesome'
import {faExternalLink, faX} from '@fortawesome/free-solid-svg-icons'
import {Link} from 'react-router-dom'
import {Loading} from './Loading'
import {useContext} from 'react'


export const Settings = () => {
	const {initialized, keycloak: {createAccountUrl}} = useContext(KeycloakContext)

	if (!initialized) {
		console.log('yeah not ready ok')
		return <Loading />
	}

	return (
		<main>
			<h1>Settings</h1>
			<p>=]</p>
			<a href={createAccountUrl()}>
				Account management
				<FontAwesomeIcon icon={faExternalLink} />
			</a>
			<Link to={'/'}><FontAwesomeIcon icon={faX} aria-label={'Close Settings'} /></Link>
		</main>
	)
}