import './Modal.css'

import ReactDOM from 'react-dom'


const ModalComponent = ({children, show}) => {
	if (!show) {
		return null
	}
	return (
		<div className={'Modal-backdrop'}>
			<div className={'Modal-content'}>
				{children}
			</div>
		</div>
	)
}

export const Modal = ({children, ...props}) => ReactDOM.createPortal(
	<ModalComponent {...props}>{children}</ModalComponent>, document.getElementsByTagName('body')[0])
