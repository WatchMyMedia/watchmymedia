import { useEffect, useState } from 'react'
import { clock } from '../../../src/lib/clock'
import { useApp } from '../../app/hooks'
import { displayAddress } from '../../session/address'
import { formatCode } from '../../session/pairing'
import type { StartState } from '../../session/session'
import { Button, KeyHints } from '../../ui/Button'
import { Brand, Icon } from '../../ui/Icon'
import { QrCode } from '../../ui/QrCode'
import { TextField } from '../../ui/TextField'
import { problemWords, signInWords } from '../../ui/problemWords'

type SignIn = Extract<StartState, { screen: 'signIn' }>

/** Seconds left until `at`, ticking once a second while shown. */
function useRemaining(at: number | undefined) {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    if (at === undefined) return
    const timer = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [at])
  return at === undefined ? 0 : Math.max(0, at - now)
}

function CodeView({ state }: { state: SignIn }) {
  const app = useApp()
  const { pairing, server } = state
  const code = pairing.state === 'waiting' ? pairing.code : undefined
  const remaining = useRemaining(code?.expiresAt)
  const stopped = pairing.state === 'expired' || pairing.state === 'failed'
  const chars = formatCode(code?.code ?? '      ').split('-')
  const failed = pairing.state === 'failed' ? problemWords(pairing.error) : undefined
  const link = `${displayAddress(server.baseUrl)}/link`
  return <>
    <div className="start-main start-main--signin">
      <p className="tv-kicker">Sign in</p>
      <h1 className="tv-h1">Link this TV to<br/>{server.name}</h1>
      <p className="signin-lede">Scan the code with your phone, or on your phone or computer open <b>{link}</b> and enter this code.</p>
      <div className="signin-code" role="group" aria-label={code ? `Code ${code.code}` : 'Getting a code'}>
        {chars.map((part, i) => <span key={i} className="signin-code__part">
          {i > 0 && <span className="signin-code__dash" aria-hidden="true"/>}
          {part.split('').map((c, j) => <span key={j} className={`signin-code__tile${stopped ? ' signin-code__tile--old' : ''}`}>{c.trim()}</span>)}
        </span>)}
      </div>
      {pairing.state === 'waiting' && <p role="status" className="start-status start-status--muted"><span className="signin-dot" aria-hidden="true"/>Waiting for the code · expires in {clock(remaining)} · this TV signs in by itself once it is entered</p>}
      {pairing.state === 'starting' && <p role="status" className="start-status start-status--muted"><span className="tv-spinner" aria-hidden="true"/>Getting a code…</p>}
      {pairing.state === 'approved' && <p role="status" className="start-status" style={{ color: 'var(--accent)' }}><Icon name="check"/>Code accepted. Signing in…</p>}
      {pairing.state === 'expired' && <p role="alert" className="start-status tv-warn-text"><Icon name="clock"/>This code expired. Get a new one to sign in.</p>}
      {failed && <p role="alert" className="tv-error"><Icon name="alert"/>{failed.title}. {failed.body}</p>}
      <div className="tv-actions start-actions">
        <Button id="new-code" variant={stopped ? 'primary' : 'ghost'} autoFocus onPress={() => app.newCode()}>{failed ? 'Try again' : 'New code'}</Button>
        {!state.info.turnstile && <Button id="use-password" onPress={() => app.usePassword()}>Sign in with a password instead</Button>}
        <Button id="cancel" onPress={() => app.cancel()}>Cancel</Button>
      </div>
    </div>
    <div className="signin-qr">
      <div className="signin-qr__card">
        {code ? <QrCode text={code.linkUrl} label="QR code that opens the link page with the code filled in"/> : <QrCode text={`${server.baseUrl}/link`} label="QR code for the link page" faded/>}
        {stopped && <span className="signin-qr__expired"><span>Expired</span></span>}
      </div>
      <p className="tv-note" style={{ textAlign: 'center' }}>Scan with your phone’s camera. It opens the link page with the code filled in.</p>
    </div>
  </>
}

function PasswordView({ state }: { state: SignIn }) {
  const app = useApp()
  const [name, setName] = useState('')
  const [password, setPassword] = useState('')
  const { busy, error } = state.password
  const submit = () => { if (name.trim() && password) void app.signInWithPassword(name, password) }
  return <>
    <div className="start-main start-main--password">
      <p className="tv-kicker">Sign in</p>
      <h1 className="tv-h1">Sign in to<br/>{state.server.name}</h1>
      <div className="signin-form">
        <span className="tv-label">Username</span>
        <TextField id="name" label="Username" value={name} onChange={setName} placeholder="Username" autoFocus autoComplete="username"/>
        <span className="tv-label">Password</span>
        <TextField id="password" label="Password" type="password" value={password} onChange={setPassword} placeholder="Password" autoComplete="current-password" onSubmit={submit}/>
      </div>
      {error ? <p role="alert" className="tv-error"><Icon name="alert"/>{signInWords(error)}</p>
        : <p className="start-status"><span className="tv-kbd">OK</span>Press OK on a field to type with the TV’s keyboard</p>}
      <div className="tv-actions start-actions">
        <Button id="sign-in" variant="primary" busy={busy} onPress={submit}>{busy ? 'Signing in…' : 'Sign in'}</Button>
        <Button id="use-code" onPress={() => app.useCode()}>Use a code instead</Button>
        <Button id="cancel" onPress={() => app.cancel()}>Cancel</Button>
      </div>
    </div>
    <aside className="signin-aside">
      <span className="signin-aside__icon"><Icon name="phone"/></span>
      <h2 className="signin-aside__title">A code is quicker</h2>
      <p className="tv-body" style={{ color: 'var(--text-3)' }}>Typing with the remote is slow. With a code, your phone does the typing and this TV signs in by itself.</p>
      <p className="tv-faint" style={{ lineHeight: 1.5 }}>This choice is hidden while the server’s sign-in protection is on; the code works then.</p>
    </aside>
  </>
}

/**
 * Signing in (SignIn on the Samsung TV canvas): a six-character code with a
 * QR code of the link page, a countdown and a new code as each runs out;
 * or a name and password typed with the TV's keyboard, unless the server
 * wants a browser check first (Turnstile), when only the code works.
 */
export function SignInScreen({ state }: { state: SignIn }) {
  const password = state.mode === 'password'
  return <div className="tv-page">
    <div className="tv-glow tv-glow--top"/>
    <div className="start-brand"><Brand/></div>
    {password ? <PasswordView state={state}/> : <CodeView state={state}/>}
    <KeyHints hints={password ? [{ key: 'OK', label: 'Type' }, { key: '▲ ▼', label: 'Move' }, { key: 'Return', label: 'Back' }]
      : [{ key: '◀ ▶', label: 'Move' }, { key: 'OK', label: 'Choose' }, { key: 'Return', label: 'Cancel' }]}
      right={`${state.server.name} · ${displayAddress(state.server.baseUrl)} · This device: Samsung TV`}/>
  </div>
}
