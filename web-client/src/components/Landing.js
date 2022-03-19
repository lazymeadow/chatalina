import {KeycloakContext} from '../contexts/keycloak'
import {Outlet} from 'react-router-dom'
import {useContext} from 'react'
import {Loading} from './Loading'
import {SocketContext} from '../contexts/socket'


export const Landing = () => {
	const {initialized, isAuthenticated, keycloak} = useContext(KeycloakContext)

	const {connectSocket, ready} = useContext(SocketContext)

	if (initialized && isAuthenticated && ready) {
		// if we're here, we can try to connect a socket
		connectSocket(keycloak.token)
		return (
			<main>
				<Outlet />
			</main>
		)
	}

	if (initialized && !isAuthenticated) {
		keycloak.logout()
	}

	return (
		<main>
			<Loading />
		</main>
	)
}