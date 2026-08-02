import Cookies from 'js-cookie'
import { initializeKeycloak, keycloak } from '../auth/keycloak'
import { postLogin } from '../auth/login'

async function checkAuth(authenticated) {
    try {
        if (!authenticated) {
            await keycloak.login()
        } else {
            if (Cookies.get('parasite')) {
                location.replace('/')
            } else {
                await postLogin()
                location.replace('/')
            }
        }
    } catch (error) {
        if (error.message === 'No user') {
            location.replace('/no-user')
            return
        }
        console.error('Failed to initialize adapter:', error)
    }
}

$(() => {
    initializeKeycloak(checkAuth)
})
