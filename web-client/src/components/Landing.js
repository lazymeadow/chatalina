import {KeycloakContext} from '../contexts/keycloak'
import {Outlet} from 'react-router-dom'
import {useContext} from 'react'
import {Loading} from './Loading'


export const Landing = () => {
	const {initialized, isAuthenticated} = useContext(KeycloakContext)

	if (initialized && isAuthenticated) {
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