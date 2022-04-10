import {KeycloakContext} from '../contexts/keycloak'
import {Outlet} from 'react-router-dom'
import {useContext, useEffect, useState} from 'react'
import {Loading} from './Loading'
import {useChat} from '../contexts/chat'


export const Landing = () => {
	const [loading, setLoading] = useState(false)

	const {initialized: authInitialized, isAuthenticated, getToken, keycloak} = useContext(KeycloakContext)
	const {initSocket, ready, initialized: chatInitialized} = useChat()

	useEffect(() => {
		if (authInitialized && isAuthenticated && ready) {
			setLoading(true)
			initSocket(getToken).then(() => setLoading(false))
		}
	}, [authInitialized, isAuthenticated, ready, getToken, initSocket])

	if (authInitialized && !isAuthenticated) {
		keycloak.logout()
	}

	if (!loading && chatInitialized) {
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