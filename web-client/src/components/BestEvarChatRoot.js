import {Landing} from './Landing'
import {BrowserRouter, useRoutes} from 'react-router-dom'
import {ChatLayout} from './chat/ChatLayout'
import {Settings} from './settings/Settings'
import {KeycloakProvider} from '../contexts/keycloak'
import {SocketProvider} from '../contexts/socket'


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
			<SocketProvider>
				<BrowserRouter>
					<BestEvarChatRoutes />
				</BrowserRouter>
			</SocketProvider>
		</KeycloakProvider>
	)
}

export default BestEvarChatRoot
