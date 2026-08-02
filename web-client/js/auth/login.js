export async function postLogin() {
    const token = keycloak.token
    const response = await fetch('/login', {
        method: 'POST',
        headers: { 'Authorization': 'Bearer ' + token },
    })
    if (response.status === 401) {
        throw new Error('No user')
    } else {
        const loginResult = await response.json()
        if (loginResult.deactivated) {
            location.replace('/reactivate')
        }
    }
}
