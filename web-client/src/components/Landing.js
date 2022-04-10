import {KeycloakContext} from '../contexts/keycloak'
import {Outlet} from 'react-router-dom'
import {useContext} from 'react'
import {Loading} from './Loading'
import {useChat} from '../contexts/chat'


export const Landing = () => {
	const {keycloak} = useContext(KeycloakContext)
	const {initialized, authInitialized, isAuthenticated} = useChat()

	if (authInitialized && !isAuthenticated) {
		keycloak.logout()
	}

	if (initialized) {
		return (
			<main>
				<Outlet />
			</main>
		)
	}

	return (
		<main>
			<Loading />
		</main>
	)
}