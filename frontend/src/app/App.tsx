import { RouterProvider } from 'react-router-dom'
import { AuthProvider, BookingDraftProvider, ActiveOrderProvider, ToastProvider } from '../shared/hooks'
import { ToastViewport } from '../shared/components'
import { router } from './router'

export default function App() {
  return (
    <AuthProvider>
      <BookingDraftProvider>
        <ActiveOrderProvider>
          <ToastProvider>
            <RouterProvider router={router} />
            <ToastViewport />
          </ToastProvider>
        </ActiveOrderProvider>
      </BookingDraftProvider>
    </AuthProvider>
  )
}
