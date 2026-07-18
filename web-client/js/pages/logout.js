import Cookies from 'js-cookie'
import { keycloak } from '../auth/keycloak'

$(async () => {
    if (Cookies.get('parasite')) {
        await fetch('/logout', { method: 'POST' })
    }
    await keycloak.init({})
    await keycloak.logout({ redirectUri: `${location.origin}/login` })
})
