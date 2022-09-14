import {Outlet} from 'react-router-dom'
import {Loading} from './Loading'
import {useChat} from '../contexts/chat'


export const Landing = () => {
	const {initialized, initMessage} = useChat()

	if (initialized) {
		return (
			<main>
				<Outlet />
			</main>
		)
	}

	return (
		<main>
			<Loading message={initMessage} />
		</main>
	)
}