import { useCallback, useEffect, useRef, useState, useSyncExternalStore, type ReactNode } from 'react'
import { AppModel, PAGE_SIZE, type AppState } from './app-model'
import { focusControl, installRemote } from './remote'
import { PROFILES } from './profile'
import { clockTime, fileMeta, fileTitle } from './presentation'

type Dialog = 'options' | 'signout' | 'exit' | undefined

function Brand() {
  return <div className="brand"><svg viewBox="0 0 512 512" aria-hidden="true"><rect x="216" y="401" width="274" height="48" rx="10" fill="currentColor" stroke="var(--brand)" strokeWidth="14"/><path d="M343 416h18v18h-18zM391 416h18v18h-18zM439 416h18v18h-18z" fill="var(--brand)"/><g transform="translate(6.16 46.16) scale(0.8197)"><circle cx="256" cy="256" r="244" fill="currentColor"/><circle cx="256" cy="256" r="233" fill="none" stroke="var(--brand)" strokeWidth="22"/><g fill="var(--brand)"><circle cx="256" cy="130" r="56"/><circle cx="375.8" cy="217.1" r="56"/><circle cx="330.1" cy="357.9" r="56"/><circle cx="181.9" cy="357.9" r="56"/><circle cx="136.2" cy="217.1" r="56"/><path d="M232 214v84l68-42z" stroke="var(--brand)" strokeWidth="18" strokeLinejoin="round"/></g></g></svg><span>WatchMy<span className="muted">Media</span></span></div>
}
function Steps({ items }: { items: [string, string][] }) {
  return <aside className="steps">{items.map(([title, text], i) => <div className="step" key={title}><h2><span>0{i + 1}</span>{title}</h2><p>{text}</p></div>)}</aside>
}
function Message({ children, error = false }: { children?: ReactNode; error?: boolean }) {
  return children ? <p className={`message ${error ? 'error' : ''}`} role={error ? 'alert' : 'status'}>{children}</p> : null
}
function PairingCode({ code, expiresAt }: { code: string; expiresAt: string }) {
  const [now, setNow] = useState(Date.now)
  useEffect(() => { const timer = setInterval(() => setNow(Date.now()), 1000); return () => clearInterval(timer) }, [])
  const remaining = Date.parse(expiresAt) - now
  return <>{remaining > 0 && <div className="pair-code" aria-label={`Pairing code: ${code}`}><span>{code}</span></div>}<p className="note">{remaining > 0 ? `Waiting for approval · Code expires in ${clockTime(remaining)}` : 'Code expired · Get a new code to continue.'}</p></>
}

export function App({ model, exit }: { model: AppModel; exit(): void }) {
  const s = useSyncExternalStore(model.subscribe, model.snapshot)
  return <AppScreen key={s.screen} model={model} s={s} exit={exit}/>
}

function AppScreen({ model, s, exit }: { model: AppModel; s: AppState; exit(): void }) {
  const address = s.address
  const [dialog, setDialog] = useState<Dialog>()
  const dialogOrigin = useRef('options')
  const restoreDialogFocus = useRef<string | undefined>(undefined)
  const dialogRef = useRef(dialog)
  useEffect(() => { dialogRef.current = dialog }, [dialog])
  const profile = PROFILES.find((p) => p.id === s.profile)!
  const active = s.screen === 'player'
  const status = s.playback.state
  const running = status === 'playing' || status === 'paused'
  const busy = s.busy || status === 'buffering'
  const closeDialog = useCallback(() => { restoreDialogFocus.current = dialogOrigin.current; setDialog(undefined) }, [])
  const openDialog = useCallback((kind: Dialog, origin: string) => { dialogOrigin.current = origin; setDialog(kind) }, [])

  useEffect(() => {
    const id = dialog ? (dialog === 'options' ? `profile-${s.profile}` : 'stay')
      : restoreDialogFocus.current ?? (s.screen === 'player' ? busy ? 'stop' : ['idle', 'error'].includes(status) ? 'replay' : s.focus : s.focus)
    restoreDialogFocus.current = undefined
    const frame = requestAnimationFrame(() => focusControl(id))
    return () => cancelAnimationFrame(frame)
  }, [s.screen, s.focus, dialog, s.profile, busy, status])
  useEffect(() => {
    document.documentElement.classList.toggle('playing-surface', active)
    return () => document.documentElement.classList.remove('playing-surface')
  }, [active])
  useEffect(() => installRemote({
    back: () => {
      if (dialogRef.current) { closeDialog(); return }
      const current = model.snapshot()
      if (current.screen === 'player') model.stopToFiles()
      else if (current.screen === 'pair' || (current.screen === 'server' && current.busy)) model.cancelPair()
      else openDialog('exit', document.activeElement instanceof HTMLElement ? document.activeElement.dataset.focus ?? 'connect' : 'connect')
    },
    toggle: () => { if (model.snapshot().screen === 'player') model.pause() },
    play: () => { if (model.snapshot().screen === 'player' && model.snapshot().playback.state === 'paused') model.pause() },
    pause: () => { if (model.snapshot().screen === 'player' && model.snapshot().playback.state === 'playing') model.pause() },
    stop: () => { if (model.snapshot().screen === 'player') model.stopToFiles() },
    seek: (ms) => { if (model.snapshot().screen === 'player') model.seek(ms) },
  }), [model, closeDialog, openDialog])

  return <div className={`app ${active ? 'player-app' : ''}`}>
    <div inert={dialog ? true : undefined}>
    {!active && <header><Brand/><span className="badge">TV prototype</span></header>}
    <main>
      {s.screen === 'server' && <div className="split entrance">
        <section><p className="eyebrow">Start here</p><h1>Your library.<br/>On the big screen.</h1>
          <p className="lede">Connect to your WatchMyMedia server, then use your phone or computer to link this TV.</p>
          <form onSubmit={(event) => { event.preventDefault(); if (!s.busy) void model.connect(address) }}>
            <label htmlFor="server-address">Server address</label>
            <input id="server-address" data-focus="address" type="url" autoComplete="off" autoCapitalize="none" spellCheck={false} placeholder="https://media.example" value={address} onChange={(event) => model.editAddress(event.target.value)} readOnly={s.busy} required/>
            <div className="actions"><button type="submit" className="primary" data-focus="connect" disabled={s.busy}>{s.busy ? 'Connecting…' : s.canRestore ? 'Reconnect to server' : 'Connect to server'}<span aria-hidden="true">→</span></button>
              {s.busy && <button type="button" data-focus="cancel-connect" onClick={() => model.cancelPair()}>Cancel</button>}
              {s.canRestore && !s.busy && <button type="button" onClick={() => void model.connect(address, true)}>Link again</button>}</div>
          </form>
          <Message error>{s.error}</Message><Message>{s.notice}</Message>
          {import.meta.env.DEV && <p className="preview-note">Browser preview uses the local synthetic server through this page’s address. Packaged TV networking is tested separately.</p>}
        </section>
        <Steps items={[
          ['Connect', 'Enter the address you use to open WatchMyMedia.'],
          ['Link this TV', 'Approve a short code on a device where you’re already signed in.'],
          ['Press play', 'Choose a file and try playback with your remote.'],
        ]}/>
      </div>}

      {s.screen === 'pair' && <div className="split entrance">
        <section><p className="eyebrow">Link this TV</p><h1>{s.error ? <>Let’s try<br/>a fresh code.</> : <>One code.<br/>Then you’re in.</>}</h1>
          {!s.error && <><p className="lede">{import.meta.env.DEV ? 'Open the server’s link page in another browser tab:' : 'Open this address on your phone or computer:'}</p><p className="link-address">{import.meta.env.DEV && s.address === location.origin ? 'http://127.0.0.1:27686' : s.address}/link</p></>}
          {s.pairing && <PairingCode code={s.pairing.code} expiresAt={s.pairing.expiresAt}/>}
          {!s.pairing && s.busy && <p className="note" role="status">Getting a code…</p>}
          <Message error>{s.error}</Message>
          <div className="actions"><button className={s.error ? 'primary' : ''} data-focus="new-code" disabled={s.busy && !s.pairing} onClick={() => void model.connect(s.address)}>Get a new code</button><button data-focus="cancel-pair" onClick={() => model.cancelPair()}>Use a different server</button></div>
        </section>
        <Steps items={[
          ['Sign in', 'Use the account you want on this TV.'],
          ['Enter the code', 'Check the device name, then approve the connection.'],
          ['You’re connected', 'This screen will move on by itself.'],
        ]}/>
      </div>}

      {s.screen === 'library' && <section className="library">
        <p className="eyebrow server-name">{s.serverName}</p><h1>Ready when you are.</h1>
        <p className="lede">Choose a file to try playback. Your place is saved as you watch.</p>
        <div className="toolbar"><div className="library-tabs" aria-label="Libraries">{s.libraries.map((library) => <button key={library.id} aria-pressed={s.libraryId === library.id} disabled={s.busy} onClick={() => void model.loadFiles(library.id)}>{library.name}</button>)}</div>
          <button data-focus="options" onClick={() => openDialog('options', 'options')}>Playback options<span className="muted">{profile.label}</span></button>
          <button data-focus="refresh" disabled={s.busy} onClick={() => void model.loadFiles(s.libraryId, s.offset)}>Refresh</button>
          <button data-focus="signout" onClick={() => openDialog('signout', 'signout')}>Sign out</button></div>
        <Message error>{s.error}</Message>
        {s.busy && <p className="note" role="status">Loading files…</p>}
        {!s.files.length && !s.busy && !s.error && <div className="empty"><h2>No files here yet.</h2><p>Add media and scan this library in your server’s web app, then refresh.</p></div>}
        <div className="file-grid">{s.files.map((file, i) => <button key={file.id} className="file-card" data-focus={`file-${file.id}`} disabled={s.busy} onClick={() => void model.play(file)}>
          <span className={`file-art art-${i % 3}`} aria-hidden="true"><span className="file-number">FILE {String(s.offset + i + 1).padStart(2, '0')}</span><span className="geometry"/></span>
          <span className="file-copy"><strong>{fileTitle(file)}</strong><span className="file-meta">{fileMeta(file)}</span><span className="play-label">▶ Play file</span></span>
        </button>)}</div>
        {s.total > PAGE_SIZE && <div className="pagination"><button disabled={s.busy || s.offset === 0} onClick={() => void model.loadFiles(s.libraryId, Math.max(0, s.offset - PAGE_SIZE))}>← Previous</button><span>{s.offset + 1}–{Math.min(s.offset + PAGE_SIZE, s.total)} of {s.total}</span><button disabled={s.busy || s.offset + PAGE_SIZE >= s.total} onClick={() => void model.loadFiles(s.libraryId, s.offset + PAGE_SIZE)}>Next →</button></div>}
      </section>}

      {active && <section className="player-screen">
        <div className="player-top"><div><h1>{s.playback.file ? fileTitle(s.playback.file) : 'Playback'}</h1><p className="note" role="status">{busy ? 'Preparing playback…' : status === 'paused' ? 'Paused' : status === 'playing' ? 'Playing' : s.playback.error ? 'Playback stopped' : 'Playback finished'}</p></div>
          {s.playback.decision && <span className="playback-mode">{{ direct: 'Direct play', remux: 'HLS remux', audio: 'Audio conversion', transcode: 'Video conversion' }[s.playback.decision.mode]} · {profile.label}</span>}</div>
        <div className="player-message"><Message error>{s.playback.error}</Message><Message>{s.notice}</Message><Message error>{s.playback.reportError}</Message></div>
        <div className="player-controls"><div className="time-row"><span>{clockTime(s.playback.positionMs)}</span><span>{clockTime(s.playback.decision?.durationMs ?? s.playback.file?.durationMs ?? 0)}</span></div>
          <progress max={s.playback.decision?.durationMs || s.playback.file?.durationMs || 1} value={s.playback.positionMs} aria-label="Playback progress"/>
          <div className="actions"><button className="round" disabled={!running || s.busy} onClick={() => model.seek(-10_000)}>↶ 10 sec</button>
            {running ? <button className="round primary" data-focus="pause" onClick={() => model.pause()}>{status === 'paused' ? '▶ Resume' : 'Ⅱ Pause'}</button> : !busy && <button className="primary" data-focus="replay" onClick={() => model.replay()}>{s.notice ? 'Resume playback' : s.playback.error ? 'Try again' : 'Play again'}</button>}
            <button className="round" disabled={!running || s.busy} onClick={() => model.seek(10_000)}>10 sec ↷</button><button data-focus="stop" onClick={() => model.stopToFiles()}>{running || busy ? 'Stop playback' : 'Back to files'}</button></div>
        </div>
      </section>}
    </main>
    <footer><span>{active ? 'Return stops playback and returns to your file' : '↑ ↓ ← → Move · Enter Select · Return Back'}</span><span>{model.player.name}</span></footer>
    </div>
    {dialog && <div className="dialog-backdrop"><section role="dialog" aria-modal="true" aria-labelledby="dialog-title" className="dialog">
      {dialog === 'options' ? <><p className="eyebrow">Playback options</p><h2 id="dialog-title">Choose a compatibility profile</h2><p className="note">These profiles help test which formats work on this TV. Use H.264 + AAC to start.</p>
        <div className="option-list">{PROFILES.map((p) => <button key={p.id} data-focus={`profile-${p.id}`} aria-pressed={s.profile === p.id} onClick={() => model.setOptions(p.id, s.playlist)}><strong>{p.label}</strong><span>{p.description}</span><b aria-hidden="true">{s.profile === p.id ? '✓' : ''}</b></button>)}</div>
        <h3>HLS playlist</h3><div className="actions"><button aria-pressed={s.playlist === 'vod'} onClick={() => model.setOptions(s.profile, 'vod')}>VOD · seek within the full file</button><button aria-pressed={s.playlist === 'event'} onClick={() => model.setOptions(s.profile, 'event')}>Event · restart at a new position</button></div>
        <button className="primary dialog-done" onClick={closeDialog}>Done</button></> : <><p className="eyebrow">{dialog === 'exit' ? 'Leave the prototype' : 'This device'}</p><h2 id="dialog-title">{dialog === 'exit' ? 'Exit WatchMyMedia?' : 'Sign out of this server?'}</h2><p>{dialog === 'exit' ? (s.canRestore ? 'Your saved sign-in will be here next time.' : 'You can reopen the prototype whenever you’re ready.') : 'You’ll need a new pairing code to link this TV again.'}</p><div className="actions"><button data-focus="stay" className="primary" onClick={closeDialog}>{dialog === 'exit' ? 'Stay here' : 'Keep me signed in'}</button><button onClick={() => { setDialog(undefined); if (dialog === 'exit') exit(); else void model.signOut() }}>{dialog === 'exit' ? 'Exit app' : 'Sign out'}</button></div></>}
    </section></div>}
  </div>
}
