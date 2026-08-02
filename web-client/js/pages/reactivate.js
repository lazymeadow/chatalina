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

window.reactivate = async () => {
    const token = keycloak.token
    const response = await fetch('/reactivate', {
        method: 'POST',
        headers: { 'Authorization': 'Bearer ' + token },
    })
    const messageElement = $('#message')
    if (!response.ok) {
        const message = await response.text()
        messageElement.text(message || 'There was a problem requesting account reactivation.')
    } else {
        $('#reactivate').remove()
        messageElement.remove()
        $('#finished').show()
    }
}

$(() => {
    initializeKeycloak(checkAuth)
})