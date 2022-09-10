import React from 'react'
import './index.css'
import BestEvarChatRoot from './components/BestEvarChatRoot'
import reportWebVitals from './reportWebVitals'
import {createRoot} from 'react-dom/client'
import {Authentication} from './util/authentication'


const root = createRoot(document.getElementById('root'))
const renderChat = () => root.render(<BestEvarChatRoot />)
Authentication.initAuth(renderChat)

// If you want to start measuring performance in your app, pass a function
// to log results (for example: reportWebVitals(console.log))
// or send to an analytics endpoint. Learn more: https://bit.ly/CRA-vitals
reportWebVitals(console.log)
