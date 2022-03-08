import './Settings.css'
import {KeycloakContext} from '../../contexts/keycloak'
import {FontAwesomeIcon} from '@fortawesome/react-fontawesome'
import {faExternalLink, faX} from '@fortawesome/free-solid-svg-icons'
import {Link} from 'react-router-dom'
import {Loading} from '../Loading'
import {useContext, useState} from 'react'


export const Settings = () => {
	const [message, setMessage] = useState('')

	const {
		initialized,
		keycloak: {createAccountUrl, idToken, token, refreshToken, updateToken, isTokenExpired, resourceAccess}
	} = useContext(KeycloakContext)

	if (!initialized) {
		return <Loading />
	}

	return (
		<div className={'Settings-root'}>
			<div className={'header'}>
				<h1>Settings</h1>
				<Link to={'/'}><FontAwesomeIcon icon={faX} aria-label={'Close Settings'} /></Link>
			</div>
			<div className={'body'}>
				<section>
					<h2>Authentication details</h2>
					<dl>
						<dt>id token</dt>
						<dd>{idToken}</dd>
						<dt>access token</dt>
						<dd>{token}</dd>
						<dt>refresh token</dt>
						<dd>{refreshToken}</dd>
						<dt>resource access</dt>
						<dd>{JSON.stringify(resourceAccess['bec'])}</dd>
					</dl>
					<p>
						{message}
					</p>
					<div>
						<button disabled={!isTokenExpired} onClick={() => {
							updateToken(5).then(function (refreshed) {
								if (refreshed) {
									setMessage('refreshed')
								}
								else {
									setMessage('')
								}
							}).catch(function () {
								setMessage('failed to refresh')
							})
						}}>refresh tokens
						</button>
					</div>
				</section>
				<section>
					<h2>Links</h2>
					<a href={createAccountUrl()}>
						Account management
						<FontAwesomeIcon icon={faExternalLink} />
					</a>
				</section>
			</div>
		</div>
	)
}