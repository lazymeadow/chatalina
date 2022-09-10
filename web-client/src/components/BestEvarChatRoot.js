import {Landing} from './Landing'
import {BrowserRouter, Navigate, useRoutes} from 'react-router-dom'
import {ChatLayout} from './chat/ChatLayout'
import {Settings} from './settings/Settings'
import {ChatProvider} from '../contexts/chat'


const Login = () => {
	return <Navigate to={'/'} replace={true} />
}

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
			path: '/login',
			element: <Login />
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
		<ChatProvider>
			<BrowserRouter>
				<BestEvarChatRoutes />
			</BrowserRouter>
		</ChatProvider>
	)
}

export default BestEvarChatRoot
