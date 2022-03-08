import {Landing} from './Landing'
import {BrowserRouter, useRoutes} from 'react-router-dom'
import {ChatLayout} from './chat/ChatLayout'
import {Settings} from './settings/Settings'
import {KeycloakProvider} from '../contexts/keycloak'


function BestEvarChatRoutes() {
	return useRoutes([
		{
			path: '/',
			element: <Landing />,
			children: [
				{
					index: true,
					element: <ChatLayout />
				},
				{
					path: 'settings',
					element: <Settings />
				}
			]
		},
		{
			path: '/privacy',
			element: <main><h1>Privacy Policy</h1><p>:)</p></main>
		},
		{
			path: '/tos',
			element: <main><h1>Terms of Service</h1><p>B></p></main>
		}
	])
}

function BestEvarChatRoot() {
	return (
		<KeycloakProvider>
			<BrowserRouter>
				<BestEvarChatRoutes />
			</BrowserRouter>
		</KeycloakProvider>
	)
}

export default BestEvarChatRoot
