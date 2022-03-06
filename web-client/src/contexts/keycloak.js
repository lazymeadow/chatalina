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
			keycloak.init({onLoad: 'check-sso', logging: true})
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

	return (
		<KeycloakContext.Provider value={{initialized, isAuthenticated, keycloak, profile}}>
			{children}
		</KeycloakContext.Provider>
	)
}