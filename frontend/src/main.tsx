import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'

import { App } from './App'
import './styles/tokens.css'
import './styles/global.css'

const rootElement = document.getElementById('root')

if (rootElement === null) {
  throw new Error('마운트 지점 #root를 찾지 못했습니다.')
}

createRoot(rootElement).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
