import Keycloak from 'keycloak-js'

export const keycloak = new Keycloak(
    {
        url: process.env.CHAT_KEYCLOAK_AUTH_SEVER,
        realm: process.env.CHAT_KEYCLOAK_AUTH_REALM,
        clientId: process.env.CHAT_KEYCLOAK_AUTH_CLIENT,
        silentCheckSsoFallback: false,
    },
)

export const initializeKeycloak = async (onReady) => {
    console.log('initializing keycloak')
    const authenticated = await keycloak.init({
        onLoad: 'check-sso',
        silentCheckSsoRedirectUri: `${location.origin}/silent-check-sso.html`,
        scope: 'test-shared',
    })

    console.log('keycloak initialized', authenticated)
    onReady(authenticated)
}

window.keycloak = keycloak
