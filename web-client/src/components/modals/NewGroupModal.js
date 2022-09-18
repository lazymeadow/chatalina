import {useCallback, useState} from 'react'
import {useNavigate} from 'react-router-dom'
import {useChat} from '../../contexts/chat'
import {Modal} from './Modal'


export const NewGroupModal = ({show}) => {
	const [name, setName] = useState('')

	const navigate = useNavigate()
	const {createGroup} = useChat()

	const handleSave = useCallback(() => {
		createGroup({name})
		navigate('/')
	}, [createGroup, name, navigate])

	return (
		<Modal show={show}>
			<h1>New Group</h1>
			<div>
				<div>
					<label htmlFor={'group-name'}>Name:</label>
					<input type={'text'}
						   id={'group-name'}
						   value={name}
						   onChange={(event) => setName(event.target.value)} />
				</div>
			</div>
			<div className={'footer'}>
				<button onClick={() => navigate('/')} className={'secondary'}>Cancel</button>
				<button onClick={handleSave}>Save</button>
			</div>
		</Modal>
	)
}
