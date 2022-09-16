import {createContext, useContext, useState} from 'react'
import {Authentication} from '../util/authentication'


const SettingsContext = createContext()
SettingsContext.displayName = 'SettingsContext'

export const useSettings = () => useContext(SettingsContext)

export const SettingsProvider = ({children}) => {
	// we'll use the actual user id for storing our settings
	const userId = Authentication.getProfile().id

	const [currentDest, setCurrentDest] = useState(localStorage.getItem(`${userId}-d`) || null)

	function handleSetDest(dest) {
		setCurrentDest(dest)
		localStorage.setItem(`${userId}-d`, dest)
	}

	return (
		<SettingsContext.Provider value={{
			currentDest,
			setDest: handleSetDest
		}}>
			{children}
		</SettingsContext.Provider>
	)
}