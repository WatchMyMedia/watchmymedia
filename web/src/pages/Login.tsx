import { useState, type ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useRouterState } from '@tanstack/react-router'
import { serverQuery } from '../api/queries'
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

  const server = useQuery(serverQuery)
  const serverName = server.data?.name

  if (setup && !auth.adminAllowed) {
    return (
      <Card serverName={serverName} title="This server isn’t set up yet" sub="For safety, the first account can only be created from the server’s home network or tailnet.">
        {null}
      </Card>
    )
  }

  const title = setup ? 'Create the first admin' : linking ? 'Sign in to link a device' : 'Sign in'
  const sub = setup
    ? 'This server is new. The first account runs it and adds everyone else.'
    : linking
      ? 'Your TV or the WatchMyMedia app is showing a code. Sign in, then check it matches before you approve it.'
      : 'Your movies, shows and live TV, on this server.'
  const foot = setup ? 'Next: choose your Movies and TV folders.' : 'Forgot your password? An admin of this server can set a new one.'
  return (
    <Card serverName={serverName} title={title} sub={sub} foot={foot}>
      <form
        className="flex flex-col gap-[18px]"
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
        <label className={fieldClass}>
          Name
          <input
            value={name}
            onChange={(e) => setName(e.target.value)}
            autoComplete="username"
            autoFocus
            required
            placeholder={setup ? 'What people will see, such as Robin' : undefined}
            className={inputClass}
          />
        </label>
        <label className={fieldClass}>
          Password
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete={setup ? 'new-password' : 'current-password'}
            required
            minLength={setup ? 8 : undefined}
            className={inputClass}
          />
        </label>
        {setup && (
          <label className={fieldClass}>
            Confirm password
            <input type="password" value={confirm} onChange={(e) => setConfirm(e.target.value)} autoComplete="new-password" required className={inputClass} />
          </label>
        )}
        {siteKey && <Turnstile siteKey={siteKey} action={setup ? 'setup' : 'login'} onToken={setToken} resetKey={widget} />}
        {setup && <p className="rounded-lg border border-line-2 px-3.5 py-3 text-[13px] text-neutral-300">The first admin is made from the home network. You’re on it.</p>}
        {error && (
          <p role="alert" className="text-sm text-red-400">
            {error}
          </p>
        )}
        <button type="submit" disabled={busy || (!!siteKey && !token)} className="btn btn-primary btn-lg w-full">
          {busy ? '…' : setup ? 'Create admin and continue' : 'Sign in'}
        </button>
      </form>
    </Card>
  )
}

const fieldClass = 'flex flex-col gap-1.5 text-[13px] font-medium text-neutral-300'
const inputClass = 'min-h-[46px] w-full rounded-lg border border-line-2 bg-ground px-3 text-[15px] font-normal text-neutral-100 placeholder:text-neutral-500 focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-accent'

/** One card in the middle of the window: the mark, the server's name, what this is, then the form. */
function Card({ serverName, title, sub, foot, children }: { serverName?: string; title: string; sub: string; foot?: string; children: ReactNode }) {
  return (
    <div className="grid min-h-screen place-items-center px-4 py-10">
      <main className="flex w-full max-w-[424px] flex-col gap-4">
        <div className="panel flex flex-col gap-[18px] border-line-2 px-7 pb-7 pt-[30px] shadow-[0_30px_70px_rgba(0,0,0,.65)]">
          <BrandLockup size={30} textClass="text-lg" />
          <div className="flex flex-col gap-1.5">
            {serverName && <p className="eyebrow">{serverName}</p>}
            <h1 className="text-2xl font-bold leading-tight tracking-[-.02em]">{title}</h1>
            <p className="text-sm text-neutral-400">{sub}</p>
          </div>
          {children}
        </div>
        {foot && <p className="text-center text-[13px] text-neutral-400">{foot}</p>}
      </main>
    </div>
  )
}
