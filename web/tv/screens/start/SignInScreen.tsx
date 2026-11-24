import { useEffect, useEffectEvent, useRef, useState, type ReactNode } from 'react'
import { clock } from '../../../src/lib/clock'
import { useApp, useBack, useFocus, useKeys } from '../../app/hooks'
import { displayAddress } from '../../session/address'
import { formatCode, type PairingCode } from '../../session/pairing'
import type { StartState } from '../../session/session'
import { Button } from '../../ui/Button'
import { Icon } from '../../ui/Icon'
import { QrCode } from '../../ui/QrCode'
import { TextField } from '../../ui/TextField'
import { problemWords, signInWords } from '../../ui/problemWords'
import { StartTop } from './ServerScreen'
import { SIGN_IN_WHY } from './startWords'

type SignIn = Extract<StartState, { screen: 'signIn' }>

/** Milliseconds left until `at`, ticking once a second while shown. */
function useRemaining(at: number | undefined) {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    if (at === undefined) return
    const timer = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [at])
  return at === undefined ? 0 : Math.max(0, at - now)
}

/** One of the two numbered steps. */
function Step({ n, children }: { n: number; children: ReactNode }) {
  return <li className="signin-step"><span className="signin-step__n" aria-hidden="true">{n}</span><span className="signin-step__text">{children}</span></li>
}

/**
 * Signing in with a phone: two numbered steps, the QR code, and the code in
 * large tiles. Focus opens on Use a password instead, so an OK pressed out
 * of habit never throws away the code someone is typing on their phone; a
 * new code is offered only once the old one has run out, and then takes
 * focus. Where the server wants a browser check first, only the code works.
 */
function CodeView({ state }: { state: SignIn }) {
  const app = useApp()
  const { pairing, server } = state
  const live = pairing.state === 'waiting' ? pairing.code : undefined
  // The code just used stays on screen, dimmed, once it has run out.
  const [last, setLast] = useState<PairingCode>()
  if (live && live !== last) setLast(live)
  const code = live ?? last
  const remaining = useRemaining(live?.expiresAt)
  const ranOut = pairing.state === 'expired' || (pairing.state === 'waiting' && remaining === 0)
  const failed = pairing.state === 'failed' ? problemWords(pairing.error) : undefined
  const old = ranOut || !!failed
  const chars = formatCode(code?.code ?? '      ').split('-')
  const password = !state.info.turnstile
  // Once the code runs out, Get a new code takes focus; once a new one is showing, focus goes back to Use a password instead.
  const focus = useFocus()
  const was = useRef(old)
  const move = useEffectEvent((ranOutNow: boolean) => {
    if (was.current === ranOutNow) return
    was.current = ranOutNow
    focus.focusId(ranOutNow ? 'new-code' : password ? 'use-password' : 'cancel')
  })
  useEffect(() => { move(old) }, [old])
  return <>
    <section className="start-main start-main--signin" aria-label="Sign in with your phone">
      <p className="tv-kicker">{server.name}</p>
      <h1 className="tv-title start-title">Sign in with your phone</h1>
      <ol className="signin-steps">
        <Step n={1}>Point your phone’s camera at the square code.</Step>
        <Step n={2}>Or open <b>{displayAddress(server.baseUrl)}/link</b> on a phone or computer and type this code:</Step>
      </ol>
      <div className={`signin-code${old ? ' signin-code--old' : ''}`} role="group" aria-label={code ? `Code ${code.code.split('').join(' ')}` : 'Getting a code'}>
        {chars.map((part, i) => <span key={i} className="signin-code__part">
          {i > 0 && <span className="signin-code__dash" aria-hidden="true"/>}
          {part.split('').map((c, j) => <span key={j} className="signin-code__tile">{c.trim()}</span>)}
        </span>)}
      </div>
      <div className="signin-under">
        {pairing.state === 'starting' && <p role="status" className="start-status"><span className="tv-spinner tv-spinner--big" aria-hidden="true"/>Getting a code…</p>}
        {pairing.state === 'waiting' && !ranOut && <>
          <p role="status" className="start-status"><span className="tv-spinner tv-spinner--big" aria-hidden="true"/>Waiting for your phone. This TV signs in by itself.</p>
          <p className="signin-left">The code works for another {clock(remaining)}.</p>
        </>}
        {pairing.state === 'approved' && <p role="status" className="start-status start-status--done"><Icon name="check"/>Code accepted. Signing in to {server.name}…</p>}
        {ranOut && <p role="alert" className="tv-notice tv-notice--warn signin-note"><b>That code ran out.</b><span className="tv-muted">Get a new one to sign in.</span></p>}
        {failed && <p role="alert" className="tv-notice tv-notice--bad signin-note"><b>{failed.title}.</b><span className="tv-muted">{failed.body}</span></p>}
        <div className="tv-actions start-actions" data-row>
          {old && <Button id="new-code" variant="primary" autoFocus onPress={() => app.newCode()}>{failed ? 'Try again' : 'Get a new code'}</Button>}
          {password && <Button id="use-password" autoFocus={!old} onPress={() => app.usePassword()}>Use a password instead</Button>}
          <Button id="cancel" autoFocus={!old && !password} onPress={() => app.cancel()}>Choose another server</Button>
        </div>
      </div>
    </section>
    <div className="signin-qr">
      <div className="signin-qr__card">
        {code ? <QrCode text={code.linkUrl} label="A code for your phone’s camera: it opens the link page with the code filled in" faded={old}/>
          : <QrCode text={`${server.baseUrl}/link`} label="A code for your phone’s camera: it opens the link page" faded/>}
      </div>
      <p className="signin-qr__line">Opens the link page with the code filled in.</p>
    </div>
  </>
}

/**
 * Signing in with a name and password typed with the TV's keyboard, with a
 * note that a code is quicker. Sign in is dimmed until both are typed and
 * says what is missing. Return goes back to the code.
 */
function PasswordView({ state }: { state: SignIn }) {
  const app = useApp()
  const [name, setName] = useState('')
  const [password, setPassword] = useState('')
  const { busy, error } = state.password
  const ready = !!name.trim() && !!password
  const submit = () => { if (ready) void app.signInWithPassword(name, password) }
  const focus = useFocus()
  useBack(() => { if (busy) return false; app.useCode(); return true })
  // ▼ from the password goes to Sign in, the first button, wherever the field's middle is.
  useKeys((i) => i.action === 'down' && (document.activeElement as HTMLElement | null)?.dataset.focus === 'password' ? focus.focusId('sign-in') : false)
  return <>
    <section className="start-main start-main--password" aria-label="Sign in with a password">
      <p className="tv-kicker">{state.server.name}</p>
      <h1 className="tv-title start-title">Sign in with a password</h1>
      <div className="signin-form">
        <span className="tv-label">Name</span>
        <TextField id="name" label="Name" value={name} onChange={setName} placeholder="Not typed yet" autoFocus autoComplete="username"/>
        <span className="tv-label">Password</span>
        <TextField id="password" label="Password" type="password" value={password} onChange={setPassword} placeholder="Not typed yet" autoComplete="current-password" onSubmit={submit}/>
      </div>
      {error ? <p role="alert" className="tv-notice tv-notice--bad signin-note"><Icon name="alert"/>{signInWords(error)}</p>
        : <p className="start-hint">OK on a box opens the TV’s keyboard.</p>}
      <div className="tv-actions start-actions" data-row>
        <Button id="sign-in" variant="primary" busy={busy} disabled={!ready && !busy} why={SIGN_IN_WHY} onPress={submit}>{busy ? 'Signing in…' : 'Sign in'}</Button>
        <Button id="use-code" onPress={() => app.useCode()}>Use a code instead</Button>
      </div>
    </section>
    <aside className="signin-aside">
      <p className="signin-aside__title">Typing on a TV is slow.</p>
      <p className="tv-lede tv-muted">A code from your phone signs this TV in without a password.</p>
    </aside>
  </>
}

/** Signing in to a server: by a code from a phone, or by a name and password. */
export function SignInScreen({ state }: { state: SignIn }) {
  return <div className="tv-page start">
    <div className="tv-glow"/>
    <StartTop/>
    {state.mode === 'password' ? <PasswordView state={state}/> : <CodeView state={state}/>}
  </div>
}
