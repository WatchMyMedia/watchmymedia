import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from 'react'
import { useNavigate, useRouterState } from '@tanstack/react-router'
import { useAuth } from '../../authContext'
import { GRANT_MS, grantUntil, minutesLeft, subscribeGrant } from '../../lib/passwordGrant'
import { parseSettingsPath, type SettingsPage } from '../../lib/settingsRoutes'
import { visibleConfirmationError } from '../../api/withConfirm'
import { ApiError } from '../../api/client'

/** The Settings page the address names. */
export function useSettingsPage(): SettingsPage | null {
  const path = useRouterState({ select: (s) => s.location.pathname })
  return parseSettingsPath(path)
}

/** Goes to a Settings address. Goes through the leave check like any link. */
export function useGo() {
  const navigate = useNavigate()
  return useCallback((href: string, options?: { replace?: boolean }) => void navigate({ href, replace: options?.replace }), [navigate])
}

/**
 * Where this browser is, for the password step in place: away is an
 * admin allowed in from outside the home network or tailnet. `minutes` is how
 * long a confirmed password still lasts, 0 for none.
 */
export function useAway() {
  const auth = useAuth()
  const isAdmin = auth.user?.isAdmin ?? false
  const away = isAdmin && auth.adminAllowed && auth.onAdminNetwork === false
  const end = useSyncExternalStore(subscribeGrant, grantUntil)
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    if (!away) return
    const tick = () => setNow(Date.now())
    const first = window.setTimeout(tick, 0)
    const timer = window.setInterval(tick, 15000)
    return () => { window.clearTimeout(first); window.clearInterval(timer) }
  }, [away, end])
  // Never more than the grant's ten minutes, even before the clock catches up.
  const minutes = away ? Math.min(GRANT_MS / 60000, minutesLeft(end, now)) : 0
  return { away, minutes, confirmed: minutes > 0 }
}

export type SaveState = { status: 'idle' | 'saving' | 'saved' | 'failed'; message?: string }

/**
 * A choice that saves at once and reports in its own row: Saving…,
 * then ✓ Saved with what it did, or why it failed and that the control is
 * back where it was. Cancelling the password dialog says nothing.
 */
export function useInstantSave() {
  const [state, setState] = useState<SaveState>({ status: 'idle' })
  const timer = useRef(0)
  useEffect(() => () => window.clearTimeout(timer.current), [])
  const run = useCallback(async (save: () => Promise<unknown>, saved?: string) => {
    window.clearTimeout(timer.current)
    setState({ status: 'saving' })
    try {
      await save()
      setState({ status: 'saved', message: saved })
      return true
    } catch (error) {
      setState(visibleConfirmationError(error) ? { status: 'failed', message: errorText(error) } : { status: 'idle' })
      return false
    }
  }, [])
  const reset = useCallback(() => setState({ status: 'idle' }), [])
  return { state, run, reset }
}

/** The server's words for a refusal, or a fallback that says nothing changed. */
export function errorText(error: unknown, fallback = 'Something went wrong, and nothing was changed. Try again.') {
  // fetch rejects with a TypeError when the server can't be reached at all.
  if (error instanceof TypeError) return 'The server didn’t answer, so nothing was changed. Check the connection and try again.'
  if (error instanceof ApiError) {
    if (error.status === 429) return `Too many tries. Wait ${error.retryAfter} seconds and try again.`
    return error.message && !error.message.startsWith('Request failed with status') ? sentence(error.message) : fallback
  }
  return error instanceof Error && error.message ? sentence(error.message) : fallback
}

function sentence(text: string) {
  const t = text.trim()
  if (!t) return t
  const capital = t[0].toUpperCase() + t.slice(1)
  return /[.!?…]$/.test(capital) ? capital : `${capital}.`
}

/** Moves focus to an element when it appears: a step's heading when the step
 * changes, a refusal when a step is refused. */
export function useFocusOnChange<T extends HTMLElement>(key: unknown) {
  const ref = useRef<T>(null)
  const first = useRef(true)
  useEffect(() => {
    if (first.current) { first.current = false; return }
    ref.current?.focus()
  }, [key])
  return ref
}

/** People who ask their system for reduced motion get no slides or fades. */
export function usePrefersReducedMotion() {
  return useSyncExternalStore(
    (cb) => {
      const q = window.matchMedia?.('(prefers-reduced-motion: reduce)')
      q?.addEventListener('change', cb)
      return () => q?.removeEventListener('change', cb)
    },
    () => window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false,
  )
}
