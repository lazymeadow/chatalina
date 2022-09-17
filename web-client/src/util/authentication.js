import Keycloak from 'keycloak-js'


const keycloak = new Keycloak({
	realm: process.env.REACT_APP_KEYCLOAK_REALM,
	url: process.env.REACT_APP_KEYCLOAK_URL,
	clientId: process.env.REACT_APP_KEYCLOAK_RESOURCE
})

const refreshCallbacks = []

function doUpdate() {
	keycloak.updateToken(30).then(r => {
		if (r) {
			refreshCallbacks.forEach(cb => !!cb && cb())
		} else {
			console.log('didn\'t need to refresh')
		}
	}).catch(e => console.log('fail', e))
}

keycloak.onTokenExpired = () => {
	console.log('refreshing expired token')
	doUpdate()
}

keycloak.onAuthLogout = () => keycloak.login()
keycloak.onAuthRefreshError = () => keycloak.logout()

let profile, logoutUrl

const initAuth = (callback) => {
	keycloak.init({
		onLoad: 'login-required',  // check-sso is breaking in modern browsers T^T
		checkLoginIframe: false,  // disabled for modern browers
		// enableLogging: true,
		silentCheckSsoRedirectUri: window.location.origin + '/silent-check-sso.html'
	})
		.then(async authenticated => {
			if (authenticated) {
				profile = await keycloak.loadUserProfile()
				logoutUrl = keycloak.createLogoutUrl()
				callback()
			} else {
				keycloak.login()
			}
		})
		.catch(error => {
			console.error(error)
			keycloak.login()
		})
}

export const Authentication = {
	initAuth,
	logout: keycloak.logout,
	getProfile: () => profile,
	getLogoutUrl: () => logoutUrl,
	getToken: () => keycloak.token,
	createAccountUrl: keycloak.createAccountUrl,
	addRefreshCallback: (callback) => refreshCallbacks.push(callback),
	refresh: () => {
		console.log('token update requested')
		doUpdate()
	}
}