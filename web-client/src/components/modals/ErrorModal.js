import {Modal} from './Modal'


export const ErrorModal = ({show, error, showRetry, onRetry}) => {
	return (
		<Modal show={show}>
			<p>
				{error}
			</p>
			<div className={'footer'}>
			{!!showRetry && (
				<button onClick={onRetry}>
					Retry
				</button>
			)}
			</div>
		</Modal>
	)
}