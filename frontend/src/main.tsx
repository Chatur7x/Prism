import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'

import { App } from './App'
import { ErrorBoundary } from './components/ErrorBoundary'
import './styles/tokens.css'
import './styles/components.css'
import './styles/app.css'
import './styles/motion.css'
import './styles/premium.css'

const container = document.getElementById('root')
if (!container) {
  // A missing mount point is a build error, not a runtime condition to handle.
  throw new Error('#root element is missing from index.html')
}

createRoot(container).render(
  <StrictMode>
    <ErrorBoundary>
      <BrowserRouter>
        <App />
      </BrowserRouter>
    </ErrorBoundary>
  </StrictMode>,
)
