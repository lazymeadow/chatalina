import {KeycloakContext} from '../../contexts/keycloak'
import {Link} from 'react-router-dom'
import {useContext} from 'react'


export const ChatLayout = () => {
	const {profile, keycloak: {createLogoutUrl}} = useContext(KeycloakContext)

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
				<div className={'log'}>
					chat log!!!!!!!!
				</div>
				<div className={'bottom-bar'}>bar</div>
			</div>
		</>
	)
}