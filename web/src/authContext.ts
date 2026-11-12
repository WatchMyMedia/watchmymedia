import { createContext, useContext } from 'react'
import type { components } from './api/schema'

export type User = components['schemas']['User']

export type AuthState = {
  status: 'loading' | 'setup' | 'login' | 'ready'
  user: User | null
  /** Admin access here, or the trusted-network check before sign-in. */
  adminAllowed: boolean
  adminLimit?: 'network' | 'https'
  onAdminNetwork?: boolean
  /** Set while sign-in and setup need Cloudflare Turnstile. */
  turnstileSiteKey?: string
}

export type AuthContextValue = AuthState & {
  refresh: () => Promise<void>
  login: (name: string, password: string, turnstileToken?: string) => Promise<void>
  setup: (name: string, password: string, turnstileToken?: string) => Promise<void>
  logout: () => Promise<void>
}

export const AuthContext = createContext<AuthContextValue | null>(null)

export function useAuth(): AuthContextValue {
  const v = useContext(AuthContext)
  if (!v) throw new Error('useAuth outside AuthProvider')
  return v
}
