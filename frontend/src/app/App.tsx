import { RouterProvider } from 'react-router-dom'
import {
  AuthProvider,
  BookingDraftProvider,
  ActiveOrderProvider,
  ToastProvider,
  HeaderBackProvider,
  AuthGateProvider,
} from '../shared/hooks'
import { ToastViewport } from '../shared/components'
import { router } from './router'

export default function App() {
  return (
    <AuthProvider>
      <BookingDraftProvider>
        <ActiveOrderProvider>
          <ToastProvider>
            {/* Above the router: `AppLayout`'s header reads the back slot, the routed screen
                below it writes it. `AuthGateProvider` likewise sits above both the screen that
                asks for a session and the auth forms that supply one — see `AuthGateModal`. */}
            <AuthGateProvider>
              <HeaderBackProvider>
                <RouterProvider router={router} />
              </HeaderBackProvider>
            </AuthGateProvider>
            <ToastViewport />
          </ToastProvider>
        </ActiveOrderProvider>
      </BookingDraftProvider>
    </AuthProvider>
  )
}
