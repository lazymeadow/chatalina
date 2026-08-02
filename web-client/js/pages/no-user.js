import { initializeKeycloak, keycloak } from '../auth/keycloak'

async function checkAuth(authenticated) {
    try {
        if (!authenticated) {
            await keycloak.login()
        }
    } catch (error) {
        console.error('Failed to initialize adapter:', error)
    }
}

window.logout = async () => await keycloak.logout({ redirectUri: `${location.origin}/login` })

$(() => {
    initializeKeycloak(checkAuth)
})
