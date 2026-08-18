import { RouterProvider } from 'react-router-dom'
import { AuthProvider, BookingDraftProvider, ActiveOrderProvider } from '../shared/hooks'
import { router } from './router'

export default function App() {
  return (
    <AuthProvider>
      <BookingDraftProvider>
        <ActiveOrderProvider>
          <RouterProvider router={router} />
        </ActiveOrderProvider>
      </BookingDraftProvider>
    </AuthProvider>
  )
}
