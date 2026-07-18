export async function postLogin() {
    const token = keycloak.token
    await fetch('/login', {
        method: 'POST',
        headers: { 'Authorization': 'Bearer ' + token },
    })
}
