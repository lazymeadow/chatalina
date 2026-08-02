export async function postLogin() {
    const token = keycloak.token
    const response = await fetch('/login', {
        method: 'POST',
        headers: { 'Authorization': 'Bearer ' + token },
    })
    const loginResult = await response.json()
    if (loginResult.deactivated) {
        location.replace('/reactivate')
    } else if (!response.ok) {
        location.replace('/logout')
    }
}
