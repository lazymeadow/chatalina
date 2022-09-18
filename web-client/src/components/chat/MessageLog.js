import {useChat} from '../../contexts/chat'


export const MessageLog = ({messages, currentDest}) => {
	const {getJidDisplayName} = useChat()
	let content
	if (!currentDest) {
		content = (
			<p>
				{`Welcome to ${process.env.REACT_APP_TITLE}! Something something getting started`}
			</p>
		)
	} else if (messages.length === 0) {
		content = (
			<p>
				There's nothing here.
			</p>
		)
	} else {
		content = messages.filter(m => m.destination === currentDest).map((message, index) => (
			<p key={message.id || index}>
			<span style={{fontStyle: 'italic'}}>
				[{message.time.toLocaleString()}]&nbsp;&nbsp;
			</span>
				<span style={{fontWeight: 'bold'}}>
				{`${getJidDisplayName(message.sender)}: `}
			</span>
				{message.message}
			</p>
		))
	}

	return (
		<div className={'log'}>
			{content}
		</div>
	)
}
