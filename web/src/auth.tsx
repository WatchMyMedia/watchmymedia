import { useCallback, useEffect, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { api, UNAUTHORIZED_EVENT, unwrap } from './api/client'
import { AuthContext, type AuthState } from './authContext'
import { PasswordConfirmationHost } from './components/ConfirmPasswordDialog'
import { changeAuthScope } from './lib/authScope'

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [state, setState] = useState<AuthState>({ status: 'loading', user: null, adminAllowed: true })
  const qc = useQueryClient()

  const refresh = useCallback(async () => {
    try {
      const s = unwrap(await api.GET('/api/auth/status'))
      const common = { adminAllowed: s.adminAllowed, adminLimit: s.adminLimit, onAdminNetwork: s.onAdminNetwork, turnstileSiteKey: s.turnstileSiteKey }
      if (s.setupRequired) setState({ status: 'setup', user: null, ...common })
      else if (s.user) setState({ status: 'ready', user: s.user, ...common })
      else setState({ status: 'login', user: null, ...common })
    } catch {
      setState((prev) => ({ ...prev, status: 'login', user: null }))
    }
  }, [])

  useEffect(() => {
    const t = window.setTimeout(() => void refresh(), 0)
    const onUnauthorized = () => {
      changeAuthScope()
      setState((s) => (s.status === 'ready' ? { ...s, status: 'login', user: null } : s))
      // Sign-in may need Turnstile now, if it was turned on since.
      void refresh()
    }
    window.addEventListener(UNAUTHORIZED_EVENT, onUnauthorized)
    return () => {
      window.clearTimeout(t)
      window.removeEventListener(UNAUTHORIZED_EVENT, onUnauthorized)
    }
  }, [refresh])

  const login = useCallback(
    async (name: string, password: string, turnstileToken?: string) => {
      const user = unwrap(await api.POST('/api/auth/login', { body: { name, password, turnstileToken } }))
      changeAuthScope()
      qc.clear()
      setState((s) => ({ ...s, status: 'ready', user }))
      await refresh()
    },
    [qc, refresh],
  )
  const setup = useCallback(
    async (name: string, password: string, turnstileToken?: string) => {
      const user = unwrap(await api.POST('/api/setup', { body: { name, password, turnstileToken } }))
      changeAuthScope()
      qc.clear()
      setState((s) => ({ ...s, status: 'ready', user }))
      await refresh()
    },
    [qc, refresh],
  )
  const logout = useCallback(async () => {
    changeAuthScope()
    await api.POST('/api/auth/logout')
    qc.clear()
    setState((s) => ({ ...s, status: 'login', user: null }))
    await refresh()
  }, [qc, refresh])

  return <AuthContext.Provider value={{ ...state, refresh, login, setup, logout }}>{children}{state.status === 'ready' && state.user && <PasswordConfirmationHost key={state.user.id} name={state.user.name} />}</AuthContext.Provider>
}
