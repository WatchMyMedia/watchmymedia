import { useEffect, useEffectEvent, useRef, useState } from 'react'
import { useApp, useBack, useFocus, useKeys } from '../../app/hooks'
import { isNotEncrypted } from '../../session/address'
import { AlreadyAddedError } from '../../session/session'
import type { StartState } from '../../session/session'
import { Button } from '../../ui/Button'
import { Brand, Icon } from '../../ui/Icon'
import { TextField } from '../../ui/TextField'
import { showNote } from '../../ui/notes'
import { addLine, alreadyAdded, alreadyAddedLine } from '../servers/serverRows'
import { ServerAvatar } from '../servers/ServersPanel'
import { ADDRESS_HINT, addressFailed, CONNECT_WHY, lastUsed, lookingFor, serverDetail, THIS_TV } from './startWords'

type Servers = Extract<StartState, { screen: 'servers' }>

const ADD_ROW = 'servers/add'

/** The brand top left and what this TV is called top right, on every start screen. */
export function StartTop({ name = true }: { name?: boolean }) {
  return <>
    <div className="start-brand"><Brand/></div>
    {name && <p className="start-name">{THIS_TV}</p>}
  </>
}

/**
 * Add a server: one field with what an address looks like, Connect dimmed
 * until there is one (still focusable, and it says why), and the way back.
 * While an address is tried it says so with Cancel; one that didn't answer
 * says why in our own words, with Try again.
 */
function AddServer({ state, onBack }: { state: Servers; onBack?: () => void }) {
  const app = useApp()
  const focus = useFocus()
  const checking = state.busy === 'address'
  // ▼ from the field goes to the first button, wherever the field's middle is.
  useKeys((i) => i.action === 'down' && (document.activeElement as HTMLElement | null)?.dataset.focus === 'address' ? focus.focusId(checking ? 'cancel' : 'connect') : false)
  const typed = state.address.trim()
  const failed = !checking && state.error !== undefined
  const words = failed ? state.error instanceof AlreadyAddedError ? { title: alreadyAddedLine(state.error.server), body: '' } : addressFailed(state.error) : undefined
  const connect = () => void app.connect()
  return <section className="start-main" aria-label="Add a server">
    <h1 className="tv-title">Add a server</h1>
    <p className="start-lede">Type your server’s address. WatchMyMedia on a computer or phone shows it under Settings, then Server.</p>
    <div className="start-field">
      <Icon name="globe"/>
      <TextField id="address" label="Server address" type="url" value={state.address} autoFocus={!checking}
        placeholder="home.example.net" onChange={(v) => app.editAddress(v)} onSubmit={connect}/>
    </div>
    {checking ? <p role="status" className="start-status"><span className="tv-spinner tv-spinner--big" aria-hidden="true"/>{lookingFor(state.address)}</p>
      : words ? <div role="alert" className="tv-notice tv-notice--bad start-note"><b>{words.title}</b>{words.body && <span>{words.body}</span>}</div>
        : <p className="start-hint">{ADDRESS_HINT}</p>}
    {state.adding && state.adding.registered.length > 0 && <p className="start-hint">{alreadyAdded(state.adding.registered)}</p>}
    <div className="tv-actions start-actions" data-row>
      {checking ? <Button id="cancel" autoFocus onPress={() => app.cancel()}>Cancel</Button>
        : <Button id="connect" variant="primary" disabled={!typed} why={CONNECT_WHY} onPress={connect}>{failed ? 'Try again' : 'Connect'}</Button>}
      {state.adding ? <Button id="back" onPress={() => app.cancel()}>Cancel</Button>
        : onBack && <Button id="back" onPress={onBack}>Back to servers</Button>}
    </div>
  </section>
}

/**
 * Choosing a server, the first screen: the servers this TV has signed in
 * to, each with who is signed in and when it was last used (the address is
 * for whoever looks after the server, so it isn't here), then Add a
 * server. Focus starts on the one last used. A plain http address away
 * from home is warned about under the list. With no servers, or opened as
 * Add a server from the signed-in app, it is Add a server alone. There is
 * no discovery on Samsung TVs. Removing a server is in Settings.
 */
export function ServerScreen({ state }: { state: Servers }) {
  const app = useApp()
  const focus = useFocus()
  const [now] = useState(() => Date.now())
  const has = state.servers.length > 0
  const [adding, setAdding] = useState(false)
  const [focused, setFocused] = useState<string>()
  const showAdd = !has || adding || state.busy === 'address' || (state.error !== undefined && !state.busy)
  const start = lastUsed(state.servers)

  // Return from Add a server goes back to the list, on Add a server.
  const back = () => { app.editAddress(state.address); setAdding(false) }
  useBack(() => { back(); return true }, has && showAdd && !state.busy && !state.adding)
  // Moving between the list and Add a server: focus on the field, or back on Add a server.
  const was = useRef(showAdd)
  const place = useEffectEvent((add: boolean) => {
    if (!has || was.current === add) return
    was.current = add
    if (add) { if (!state.busy) focus.focusId('address') }
    else focus.focusId(ADD_ROW)
  })
  useEffect(() => { place(showAdd) }, [showAdd])

  const warn = state.servers.find((s) => s.id === focused && isNotEncrypted(s.baseUrl))
  return <div className="tv-page start">
    <div className="tv-glow"/>
    <StartTop/>
    {showAdd ? <AddServer state={state} onBack={has ? back : undefined}/> : <section className="start-main" aria-label="Choose a server">
      <h1 className="tv-title">Choose a server</h1>
      <p className="start-sub">Servers this TV has signed in to.</p>
      <ul className="start-list" aria-label="Servers this TV has signed in to">
        {state.servers.map((s) => {
          const busy = state.busy === s.id
          return <li key={s.id}>
            <button type="button" data-focus={`server/${s.id}`} data-autofocus={s.id === start || undefined} className="tv-option start-server" aria-busy={busy || undefined}
              onFocus={() => setFocused(s.id)} onClick={() => { if (!state.busy) void app.choose(s.id) }}>
              <ServerAvatar person={s.account?.name}/>
              <span className="tv-option__text"><span className="tv-option__label start-server__name">{s.name}</span>
                <span className="tv-option__detail">{busy ? 'Connecting…' : serverDetail(s, now)}</span></span>
              {busy ? <span className="tv-spinner" aria-hidden="true"/> : <span className="start-server__go" aria-hidden="true"><Icon name="right"/></span>}
            </button>
          </li>
        })}
      </ul>
      <div className="start-rule" aria-hidden="true"/>
      <button type="button" data-focus={ADD_ROW} className="tv-option start-add" aria-disabled={app.store.full || undefined}
        onClick={app.store.full ? () => showNote(addLine(true), 'info', 6000) : () => setAdding(true)}>
        <span className="start-add__glyph" aria-hidden="true"><Icon name="plus"/></span>
        <span className="tv-option__text"><span className="tv-option__label">Add a server</span><span className="tv-option__detail">By its address</span></span>
      </button>
      {warn && <p className="tv-notice tv-notice--warn start-warn"><Icon name="unlocked"/><span><b>Not encrypted.</b> {warn.name} is away from home over http, so what you watch crosses the internet unencrypted. An https address fixes that.</span></p>}
    </section>}
  </div>
}
