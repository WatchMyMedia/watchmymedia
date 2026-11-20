import { useState } from 'react'
import { useApp } from '../../app/hooks'
import { displayAddress, isNotEncrypted } from '../../session/address'
import { MIN_SERVER_VERSION } from '../../session/session'
import type { StartState } from '../../session/session'
import { Button, KeyHints } from '../../ui/Button'
import { Brand, Icon } from '../../ui/Icon'
import { TextField } from '../../ui/TextField'
import { problemWords } from '../../ui/problemWords'
import { alreadyAdded } from '../servers/serverRows'
import { serverDetail } from './startWords'

type Servers = Extract<StartState, { screen: 'servers' }>

function AddressForm({ state, first }: { state: Servers; first: boolean }) {
  const app = useApp()
  const connecting = state.busy === 'address'
  const error = state.error ? problemWords(state.error) : undefined
  return <>
    <span className="tv-label">Server address</span>
    <TextField id="address" label="Server address" type="url" value={state.address} autoFocus={first && !connecting}
      placeholder="192.168.1.20:27587 or https://home.example.net" onChange={(v) => app.editAddress(v)} onSubmit={() => void app.connect()}/>
    {connecting ? <p role="status" className="start-status"><span className="tv-spinner" aria-hidden="true"/>Connecting to {state.address.trim()}…</p>
      : error ? <p role="alert" className="tv-error"><Icon name="alert"/>{error.title}. {error.body}</p>
        : first ? <p className="start-status"><span className="tv-kbd">OK</span>Press OK to type with the TV’s keyboard</p> : null}
    {state.adding && state.adding.registered.length > 0 && <p className="tv-note">{alreadyAdded(state.adding.registered)}</p>}
    <div className="tv-actions start-actions">
      {connecting ? <>
        <Button id="connecting" variant="primary" busy>Connecting…</Button>
        <Button id="cancel" autoFocus onPress={() => app.cancel()}>Cancel</Button>
      </> : <Button id="connect" variant="primary" onPress={() => void app.connect()}>Connect</Button>}
    </div>
  </>
}

/**
 * Choosing a server: the servers this TV remembers and an address typed
 * with the TV's keyboard. There is no discovery on Samsung TVs. Plain http
 * to a server away from home is marked Not encrypted. Opened as Add a
 * server from the signed-in app, it offers none of the servers this TV
 * already has and names them under the field; Return goes back to the app.
 * Removing a server is in Settings › Account & devices › Servers.
 */
export function ServerScreen({ state }: { state: Servers }) {
  const app = useApp()
  const [now] = useState(() => Date.now())
  const [focused, setFocused] = useState<string>()
  const first = state.servers.length === 0
  const adding = !!state.adding
  const warn = state.servers.find((s) => s.id === focused && isNotEncrypted(s.baseUrl)) ?? (focused ? undefined : state.servers.find((s) => isNotEncrypted(s.baseUrl)))
  const hints = state.busy ? [{ key: 'Return', label: 'Cancel' }]
    : first ? [{ key: 'OK', label: 'Type the address' }, { key: '▼', label: 'Go to Connect' }, ...(adding ? [{ key: 'Return', label: 'Back' }] : [])]
      : [{ key: 'OK', label: 'Connect' }, { key: '▲ ▼', label: 'Move' }]
  return <div className="tv-page">
    <div className="tv-glow"/>
    <div className="start-brand"><Brand/></div>
    {first ? <>
      <div className="start-main">
        <p className="tv-kicker">{adding ? 'Add a server' : 'Servers'}</p>
        <h1 className="tv-h1">{adding ? 'Choose a server' : 'Connect to your server'}</h1>
        <p className="tv-lede">{adding ? 'Type the address of another WatchMyMedia server. This TV stays signed in to the ones it has.'
          : 'Type the address of your WatchMyMedia server. This TV remembers it for next time.'}</p>
        <div className="start-form"><AddressForm state={state} first/></div>
      </div>
      <aside className="start-help" aria-labelledby="help-head">
        <h2 id="help-head" className="start-help__title">Addresses that work</h2>
        <div><span className="start-help__head">At home</span><span className="tv-note">The server’s IP address and port, like 192.168.1.20:27587. Plain http is fine on your own network.</span></div>
        <div><span className="start-help__head">From anywhere</span><span className="tv-note">A hostname with https, like https://home.example.net. Tailscale Serve or a reverse proxy gives it one.</span></div>
        <p className="tv-faint">Needs WatchMyMedia {MIN_SERVER_VERSION} or newer on the server</p>
      </aside>
    </> : <>
      <div className="start-main start-main--list">
        <p className="tv-kicker">Servers</p>
        <h1 className="tv-h1">Choose a server</h1>
        <p className="tv-lede">Servers this TV has connected to.</p>
        <ul className="start-servers" aria-label="Remembered servers">
          {state.servers.map((s, i) => {
            const plain = isNotEncrypted(s.baseUrl)
            const busy = state.busy === s.id
            return <li key={s.id} className="start-server-row">
              <button type="button" data-focus={`server/${s.id}`} data-autofocus={i === 0 || undefined} className="start-server" aria-busy={busy || undefined}
                onFocus={() => setFocused(s.id)} onClick={() => { if (!state.busy) void app.choose(s.id) }}>
                <span className="start-server__icon" style={{ background: s.baseUrl.startsWith('https:') || plain ? 'var(--line-2)' : 'var(--brand)' }}><Icon name={s.baseUrl.startsWith('https:') || plain ? 'globe' : 'server'}/></span>
                <span className="start-server__text">
                  <span className="start-server__name">{s.name}</span>
                  <span className="start-server__detail">{busy ? `Connecting to ${displayAddress(s.baseUrl)}…` : serverDetail(s, now)}</span>
                </span>
                {busy && <span className="tv-spinner" aria-hidden="true"/>}
                {plain && <span className="tv-tag tv-tag--warn"><Icon name="unlocked"/>Not encrypted</span>}
              </button>
            </li>
          })}
        </ul>
        {warn && <p className="start-warn"><span className="tv-warn-text">Not encrypted.</span> {warn.name} is away from home over http, so what you watch crosses the internet unencrypted. An https address fixes that.</p>}
      </div>
      <div className="start-side">
        <h2 className="start-side__title">Or enter another address</h2>
        <AddressForm state={state} first={false}/>
        <p className="tv-faint start-side__note">An IP address and port works over http on your own network. A hostname needs https; Tailscale Serve or a reverse proxy gives it one.</p>
      </div>
    </>}
    <KeyHints hints={hints} right="This device: Samsung TV"/>
  </div>
}
