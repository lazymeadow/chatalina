import Cookies from 'js-cookie'
import { initializeKeycloak, keycloak } from '../auth/keycloak'

async function checkAuth(authenticated) {
    try {
        // const authenticated = await keycloak.init()
        if (!authenticated) {
            console.log('not logged in')
            await keycloak.login()
        } else {
            if (Cookies.get('id')) {
                console.log('session cookie already exists')
                location.replace('/')
            } else {
                console.log('logged in, starting chat session')
                const subject = keycloak.subject
                const token = keycloak.token
                console.log(subject)
                const response = await fetch('/login', {
                    method: 'POST',
                    body: JSON.stringify({ subject }),
                    headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + token },
                })
                console.log('logged in', JSON.stringify(response))
                location.replace('/')
            }
        }
    } catch (error) {
        console.error('Failed to initialize adapter:', error)
    }
}

$(() => {
    initializeKeycloak(checkAuth)
    // checkAuth()
})
