import Keycloak from 'keycloak-js'

export const keycloak = new Keycloak(
    {
        url: process.env.CHAT_KEYCLOAK_AUTH_SEVER,
        realm: process.env.CHAT_KEYCLOAK_AUTH_REALM,
        clientId: process.env.CHAT_KEYCLOAK_AUTH_CLIENT,
        audience: 'chatalina-test',
        silentCheckSsoFallback: false,
        checkLoginIframe: false,
        enableLogging: true,  // TODO
    },
)

export const initializeKeycloak = async (onReady) => {
    console.log('setting keycloak onReady')
    keycloak.onReady = onReady
    console.log('initializing keycloak')
    const authenticated = await keycloak.init({ onLoad: 'login-required' })
    console.log('keycloak initialized', authenticated)
}

window.keycloak = keycloak
