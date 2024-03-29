import {createContext, useContext, useState} from 'react'
import {Authentication} from '../util/authentication'


const SettingsContext = createContext()
SettingsContext.displayName = 'SettingsContext'

export const useSettings = () => useContext(SettingsContext)

export const SettingsProvider = ({children}) => {
	// we'll use the actual user id for storing our settings
	const userId = Authentication.getProfile().id

	const [currentDest, setCurrentDest] = useState(localStorage.getItem(`${userId}-d`) || null)
	const [displayName, setDisplayName] = useState('')
	const [myJID, setMyJID] = useState('')

	function handleSetDest(dest) {
		setCurrentDest(dest)
		localStorage.setItem(`${userId}-d`, dest)
	}

	function handleSetSettings({displayName: newDisplayName, jid}) {
		setDisplayName(newDisplayName)
		setMyJID(jid)
	}

	return (
		<SettingsContext.Provider value={{
			currentDest,
			displayName,
			myJID,
			setDest: handleSetDest,
			setSettings: handleSetSettings
		}}>
			{children}
		</SettingsContext.Provider>
	)
}