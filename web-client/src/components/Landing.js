import {KeycloakContext} from '../contexts/keycloak'
import {Outlet} from 'react-router-dom'
import {useContext, useEffect, useState} from 'react'
import {Loading} from './Loading'
import {SocketContext} from '../contexts/socket'


export const Landing = () => {
	const [loading, setLoading] = useState(false)

	const {initialized, isAuthenticated, getToken, keycloak} = useContext(KeycloakContext)

	const {initSocket, ready, initialized: socketInitialized} = useContext(SocketContext)

	useEffect(() => {
		if (initialized && isAuthenticated && ready) {
			setLoading(true)
			initSocket(getToken).then(() => setLoading(false))
		}
	}, [initialized, isAuthenticated, ready])

	if (!loading && socketInitialized) {
		return (
			<main>
				<Outlet />
			</main>
		)
	}

	if (initialized && !isAuthenticated) {
		keycloak.logout()
	}

	// debugger
	return (
		<main>
			<Loading />
		</main>
	)
}