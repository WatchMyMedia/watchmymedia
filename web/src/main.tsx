import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RouterProvider } from '@tanstack/react-router'
import { AuthProvider } from './auth'
import { router } from './router'
// Inter, served with the app: the server's content policy allows its own fonts only.
import '@fontsource-variable/inter/wght.css'
import './index.css'

const queryClient = new QueryClient({
  defaultOptions: { queries: { staleTime: 10_000, retry: 1 } },
})

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <RouterProvider router={router} context={{ queryClient }} />
      </AuthProvider>
    </QueryClientProvider>
  </StrictMode>,
)
