import { createBrowserRouter } from 'react-router-dom'
import HomePage from './HomePage'

/**
 * Root route configuration. Feature routes (auth, issues, booking, etc.)
 * are added here as each milestone lands — see
 * docs/architecture/implementation-plan.md for the milestone sequence.
 */
export const router = createBrowserRouter([
  {
    path: '/',
    element: <HomePage />,
  },
])
