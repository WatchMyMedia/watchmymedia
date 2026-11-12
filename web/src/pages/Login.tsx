import { useState } from 'react'
import { useRouterState } from '@tanstack/react-router'
import { useAuth } from '../authContext'
import { BrandLockup } from '../components/BrandMark'
import { Turnstile } from '../components/Turnstile'

/** Sign-in, or first-run admin creation when no account exists yet. */
export function Login({ mode }: { mode: 'login' | 'setup' }) {
  const auth = useAuth()
  const [name, setName] = useState('')
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  // Cloudflare Turnstile, while the server asks for it. A token works once,
  // so every attempt gets a fresh widget.
  const [token, setToken] = useState<string | null>(null)
  const [widget, setWidget] = useState(0)
  const siteKey = auth.turnstileSiteKey
  const setup = mode === 'setup'
  // Reached from a TV's QR code or the app's link page: say why a sign-in comes first.
  const linking = useRouterState({ select: (s) => s.location.pathname }) === '/link'

  if (setup && !auth.adminAllowed) {
    return (
      <div className="flex min-h-screen items-center justify-center px-4">
        <div className="w-full max-w-sm space-y-3 rounded-xl border border-line bg-surface p-6">
          <h1>
            <BrandLockup size={32} textClass="text-xl" />
          </h1>
          <p className="text-sm text-neutral-300">
            This server has not been set up yet. For safety, the first account can only be created from the server's home network or tailnet.
          </p>
        </div>
      </div>
    )
  }

  return (
    <div className="flex min-h-screen items-center justify-center px-4">
      <form
        className="w-full max-w-sm space-y-4 rounded-xl border border-line bg-surface p-6"
        onSubmit={async (e) => {
          e.preventDefault()
          setError(null)
          if (setup && password !== confirm) {
            setError('Passwords do not match.')
            return
          }
          setBusy(true)
          try {
            if (setup) await auth.setup(name, password, token ?? undefined)
            else await auth.login(name, password, token ?? undefined)
          } catch (err) {
            setError(err instanceof Error ? err.message : String(err))
            if (siteKey) {
              setToken(null)
              setWidget((n) => n + 1)
            } else {
              // Turnstile may have been turned on since this page loaded.
              void auth.refresh()
            }
          } finally {
            setBusy(false)
          }
        }}
      >
        <div>
          <h1>
            <BrandLockup size={32} textClass="text-xl" />
          </h1>
          <p className="mt-1 text-sm text-neutral-400">
            {setup ? 'Create the first account. It will be the admin.' : linking ? 'Sign in to approve the code your TV or the WatchMyMedia app shows.' : 'Sign in to your server.'}
          </p>
        </div>
        <label className="block text-sm">
          <span className="text-neutral-400">Name</span>
          <input
            value={name}
            onChange={(e) => setName(e.target.value)}
            autoComplete="username"
            autoFocus
            required
            className="mt-1 w-full rounded-md border border-line-2 bg-ground px-3 py-2"
          />
        </label>
        <label className="block text-sm">
          <span className="text-neutral-400">Password</span>
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete={setup ? 'new-password' : 'current-password'}
            required
            minLength={setup ? 8 : undefined}
            className="mt-1 w-full rounded-md border border-line-2 bg-ground px-3 py-2"
          />
        </label>
        {setup && (
          <label className="block text-sm">
            <span className="text-neutral-400">Confirm password</span>
            <input type="password" value={confirm} onChange={(e) => setConfirm(e.target.value)} autoComplete="new-password" required className="mt-1 w-full rounded-md border border-line-2 bg-ground px-3 py-2" />
          </label>
        )}
        {siteKey && <Turnstile siteKey={siteKey} action={setup ? 'setup' : 'login'} onToken={setToken} resetKey={widget} />}
        {error && <p className="text-sm text-red-400">{error}</p>}
        <button type="submit" disabled={busy || (!!siteKey && !token)} className="min-h-[40px] w-full rounded-md btn-primary font-semibold disabled:opacity-50">
          {busy ? '…' : setup ? 'Create account' : 'Sign in'}
        </button>
      </form>
    </div>
  )
}
