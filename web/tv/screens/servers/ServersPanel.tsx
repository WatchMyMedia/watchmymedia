import { useEffect, useRef } from 'react'
import { useApp, useAppState, useFocus, useSession } from '../../app/hooks'
import type { SwitchView } from '../../session/session'
import { Button } from '../../ui/Button'
import { Icon } from '../../ui/Icon'
import { Modal } from '../../ui/Overlays'
import { showNote } from '../../ui/notes'
import { Glyph } from '../details/Glyph'
import {
  ADD_FOCUS, addLine, cantChooseWords, MANAGE_FOCUS, MANAGE_LINE, panelFocus, panelReturn, RETRY_FOCUS, serverFocus, serverRows, signedInLine, switchFailedWords, WATCHLISTS_FOCUS,
  type ServerRow,
} from './serverRows'
import './servers.css'
import { BACK } from '../../app/keys'

/** A switch that didn't happen: which server, why, that the app is where it was, and Try again. */
export function SwitchFailed({ failed, autoFocus }: { failed: Extract<SwitchView, { state: 'failed' }>; autoFocus?: boolean }) {
  const app = useApp()
  const session = useSession()
  const words = switchFailedWords(failed, app.store.get(failed.id), session.server.name)
  return <div role="alert" className="servers-alert">
    <span className="servers-alert__title">{words.title}</span>
    <span className="servers-alert__body">{words.body}</span>
    <Button id={RETRY_FOCUS} autoFocus={autoFocus} onPress={() => app.retrySwitch()}>Try again</Button>
  </div>
}

/** Who is signed in: their initial on burgundy, or a person when this TV is signed out of the server. */
export function ServerAvatar({ person }: { person?: string }) {
  return <span className="srv-who" aria-hidden="true">{person ? person.slice(0, 1).toUpperCase() : <Icon name="person"/>}</span>
}

function ServerLine({ row, start, onChoose }: { row: ServerRow; start: string; onChoose(): void }) {
  const inner = <>
    <ServerAvatar person={row.person}/>
    <span className="tv-option__text"><span className="tv-option__label srv-name">{row.name}</span><span className="tv-option__detail">{row.detail}</span></span>
  </>
  // The current server is where you are: a tick, and nothing to press.
  if (row.state === 'current') return <div className="tv-option srv-row" aria-current="true">{inner}<span className="tv-option__check"><Icon name="check"/></span></div>
  const why = cantChooseWords(row)
  return <button type="button" data-focus={serverFocus(row.id)} data-autofocus={start === serverFocus(row.id) || undefined} className="tv-option srv-row"
    aria-disabled={!row.canChoose || undefined} aria-busy={row.state === 'switching' || undefined}
    onClick={!row.canChoose ? (why ? () => showNote(why, 'info') : undefined) : row.state !== 'switching' ? onChoose : undefined}>
    {inner}
    {row.state === 'switching' && <span className="tv-option__end" role="status"><span className="tv-spinner" aria-hidden="true"/>Switching…</span>}
  </button>
}

/**
 * Servers, from the account row at the foot of the menu: a panel from the
 * left with the servers this TV has, the current one marked "you are here"
 * with a tick, then Add a server and Manage servers under a line, and
 * Watchlists where the server keeps them. No addresses: they are for
 * whoever looks after a server. OK on another server switches to it at
 * once; one signed out here opens its sign-in. A server that doesn't
 * answer says so above the list, with Try again, and the app stays where
 * it was. Focus opens on the first server that isn't the current one;
 * Return closes the panel and puts focus back on the account row.
 */
export function ServersPanel({ from, onClose, onManage, onWatchlists }: {
  /** What opened it: the menu's account row, or a screen's switch that failed (a search result on another server). */
  from: 'rail' | 'screen'
  onClose(): void
  onManage(): void
  /** The person's watchlists; none where the server doesn't keep them. */
  onWatchlists?(): void
}) {
  const app = useApp()
  const state = useAppState()
  const session = useSession()
  const focus = useFocus()
  const closing = useRef(false)
  const switching = state.screen === 'signedIn' ? state.switching : undefined
  const failed = switching?.state === 'failed' ? switching : undefined
  const full = app.store.full
  const servers = app.store.list()
  const rows = serverRows(servers, session.server.id, switching)
  const start = panelFocus(rows, switching, full)

  // As a switch starts or fails, focus goes to its row or to Try again (which comes and goes).
  const step = `${switching?.state ?? ''}/${switching?.id ?? ''}`
  useEffect(() => { focus.focusId(start) }, [focus, step, start])

  const close = () => {
    closing.current = true
    app.clearSwitch()
    onClose()
  }
  // Closed with Return, focus goes back to the account row; after a switch the new Home takes it.
  const back = () => {
    const id = panelReturn(from, closing.current)
    return !!id && focus.focusId(id)
  }

  return <Modal onClose={close} className="" returnFocus={back}>
    <aside role="dialog" aria-modal="true" aria-label="Servers" className="tv-panel tv-panel--left srv-panel">
      <h2 className="tv-panel__title">Servers</h2>
      <p className="tv-panel__sub">{signedInLine(servers.filter((s) => s.account).length)}</p>
      <div className="tv-panel__body srv-body">
        {failed && <SwitchFailed failed={failed} autoFocus/>}
        <ul className="srv-list" aria-label="Servers this TV is signed in to">
          {rows.map((row) => <li key={row.id}><ServerLine row={row} start={start} onChoose={() => void app.switchTo(row.id)}/></li>)}
        </ul>
        <div className="tv-panel__line" aria-hidden="true"/>
        <button type="button" data-focus={ADD_FOCUS} data-autofocus={start === ADD_FOCUS || undefined} className="tv-option srv-row srv-row--action" aria-disabled={full || undefined}
          onClick={full ? () => showNote(addLine(true), 'info', 6000) : () => app.addServer()}>
          <span className="srv-glyph" aria-hidden="true"><Icon name="plus"/></span>
          <span className="tv-option__text"><span className="tv-option__label">Add a server</span><span className="tv-option__detail">{full ? 'This TV has as many as it keeps' : addLine(false)}</span></span>
        </button>
        <button type="button" data-focus={MANAGE_FOCUS} data-autofocus={start === MANAGE_FOCUS || undefined} className="tv-option srv-row srv-row--action" onClick={onManage}>
          <span className="srv-glyph" aria-hidden="true"><Icon name="settings"/></span>
          <span className="tv-option__text"><span className="tv-option__label">Manage servers</span><span className="tv-option__detail">{MANAGE_LINE}</span></span>
        </button>
        {onWatchlists && <button type="button" data-focus={WATCHLISTS_FOCUS} className="tv-option srv-row srv-row--action" onClick={onWatchlists}>
          <span className="srv-glyph" aria-hidden="true"><Glyph name="bookmark"/></span>
          <span className="tv-option__text"><span className="tv-option__label">Watchlists</span><span className="tv-option__detail">Yours on {session.server.name}</span></span>
        </button>}
      </div>
      <p className="tv-panel__foot srv-hint" aria-hidden="true"><span className="tv-kbd">{BACK}</span>Close</p>
    </aside>
  </Modal>
}
