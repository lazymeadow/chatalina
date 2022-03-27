import {createContext, useEffect, useState} from 'react'
import Keycloak from 'keycloak-js'


const keycloak = new Keycloak()

export const KeycloakContext = createContext()

export function KeycloakProvider({children}) {
	const [initialized, setInitialized] = useState(false)
	const [initializing, setInitializing] = useState(false)
	const [isAuthenticated, setIsAuthenticated] = useState(false)
	const [profile, setProfile] = useState({})

	useEffect(() => {
		if (!initialized && !initializing) {
			setInitializing(true)
			keycloak.init({onLoad: 'check-sso', enableLogging: true})
				.then(async authenticated => {
					if (authenticated) {
						const userProfile = await keycloak.loadUserProfile()
						setProfile(userProfile)
						setIsAuthenticated(true)
						setInitialized(true)
						setInitializing(false)
					}
					else {
						keycloak.login()
					}
				})
		}
	}, [initialized, initializing])

	useEffect(() => {
		if (initialized && !isAuthenticated) {
			keycloak.updateToken(5)
		}
	}, [initialized, isAuthenticated])

	const getToken = async() => {
		if (keycloak.isTokenExpired(5)) {
			try {
				await keycloak.updateToken(5)
			}
			catch (refreshed) {
				if (refreshed === true) {
					return keycloak.token
				}
				else {
					keycloak.login()

				}
			}
		} else {
			return keycloak.token
		}
	}

	const getLogoutUrl = async() => {
		try {
			return keycloak.createLogoutUrl()
		} catch (e) {
			console.error(e)
			keycloak.login()
		}
	}

	return (
		<KeycloakContext.Provider value={{initialized, isAuthenticated, keycloak, profile, getToken, getLogoutUrl}}>
			{children}
		</KeycloakContext.Provider>
	)
}