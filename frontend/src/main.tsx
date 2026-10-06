import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'

import { App } from './App'
import { ErrorBoundary } from './components/ErrorBoundary'
import { ToastProvider } from './components/primitives'

/*
 * Layer order is load-bearing: tokens define the vocabulary, components read
 * it, app arranges it, motion decides how it moves, depth makes it finished,
 * and primitives style the shared interaction surfaces. Because the layers
 * cascade in this order, a page never has to know which file owns a value.
 */
import './styles/tokens.css'
import './styles/components.css'
import './styles/app.css'
import './styles/motion.css'
import './styles/premium.css'
import './styles/primitives.css'
import './styles/home.css'

const container = document.getElementById('root')
if (!container) {
  // A missing mount point is a build error, not a runtime condition to handle.
  throw new Error('#root element is missing from index.html')
}

createRoot(container).render(
  <ErrorBoundary>
    <ToastProvider>
      <BrowserRouter>
        <App />
      </BrowserRouter>
    </ToastProvider>
  </ErrorBoundary>,
)