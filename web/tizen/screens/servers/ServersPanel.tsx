import { useEffect, useRef } from 'react'
import { useApp, useAppState, useFocus, useSession } from '../../app/hooks'
import type { SwitchView } from '../../session/session'
import { Button } from '../../ui/Button'
import { Icon } from '../../ui/Icon'
import { Modal } from '../../ui/Overlays'
import { ADD_FOCUS, addLine, MANAGE_FOCUS, panelFocus, panelReturn, RETRY_FOCUS, serverFocus, serverRows, switchFailedWords } from './serverRows'
import './servers.css'

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

/**
 * Servers, from the rail's account button: the servers this TV has, the
 * current one marked, then Add a server and Manage servers. OK on another
 * server switches to it at once; one signed out here opens its sign-in. A
 * server that doesn't answer says so above the list, with Try again, and
 * the app stays where it was. Return closes the panel and puts focus back
 * on the button that opened it.
 */
export function ServersPanel({ from, onClose, onManage }: {
  /** What opened it: the rail's button, or a screen's switch that failed (a search result on another server). */
  from: 'rail' | 'screen'
  onClose(): void
  onManage(): void
}) {
  const app = useApp()
  const state = useAppState()
  const session = useSession()
  const focus = useFocus()
  const closing = useRef(false)
  const switching = state.screen === 'signedIn' ? state.switching : undefined
  const failed = switching?.state === 'failed' ? switching : undefined
  const full = app.store.full
  const rows = serverRows(app.store.list(), session.server.id, switching)
  const start = panelFocus(rows, switching, full)

  // As a switch starts or fails, focus goes to its row or to Try again (which comes and goes).
  const step = `${switching?.state ?? ''}/${switching?.id ?? ''}`
  useEffect(() => { focus.focusId(start) }, [focus, step, start])

  const close = () => {
    closing.current = true
    app.clearSwitch()
    onClose()
  }
  // Closed with Return, focus goes back to the account button; after a switch the new Home takes it.
  const back = () => {
    const id = panelReturn(from, closing.current)
    return !!id && focus.focusId(id)
  }

  return <Modal onClose={close} className="" returnFocus={back}>
    <aside role="dialog" aria-modal="true" aria-label="Servers" className="servers-panel">
      <h2 className="servers-title">Servers</h2>
      {failed && <SwitchFailed failed={failed} autoFocus/>}
      <ul className="servers-list" aria-label="Servers this TV is signed in to">
        {rows.map((row) => {
          const inner = <>
            <span className="servers-row__icon" aria-hidden="true"><Icon name="server"/></span>
            <span className="servers-row__text"><span className="servers-row__label">{row.name}</span><span className="servers-row__detail">{row.detail}</span></span>
          </>
          return <li key={row.id}>
            {row.state === 'current'
              ? <div className="servers-row" aria-current="true">{inner}<span className="servers-current">Current</span></div>
              : <button type="button" data-focus={serverFocus(row.id)} data-autofocus={start === serverFocus(row.id) || undefined} className="servers-row"
                aria-disabled={!row.canChoose || undefined} aria-busy={row.state === 'switching' || undefined}
                onClick={row.canChoose && row.state !== 'switching' ? () => void app.switchTo(row.id) : undefined}>
                {inner}
                {row.state === 'switching' && <span className="servers-row__value" role="status"><span className="tv-spinner" aria-hidden="true"/>Switching…</span>}
              </button>}
          </li>
        })}
      </ul>
      <div className="servers-rule" aria-hidden="true"/>
      <div className="servers-list">
        <button type="button" data-focus={ADD_FOCUS} data-autofocus={start === ADD_FOCUS || undefined} className="servers-row" aria-disabled={full || undefined}
          onClick={full ? undefined : () => app.addServer()}>
          <span className="servers-row__icon servers-row__icon--plain" aria-hidden="true"><Icon name="plus"/></span>
          <span className="servers-row__text"><span className="servers-row__label">Add a server</span><span className="servers-row__detail">{addLine(full)}</span></span>
        </button>
        <button type="button" data-focus={MANAGE_FOCUS} data-autofocus={start === MANAGE_FOCUS || undefined} className="servers-row servers-row--short" onClick={onManage}>
          <span className="servers-row__icon servers-row__icon--plain" aria-hidden="true"><Icon name="settings"/></span>
          <span className="servers-row__text"><span className="servers-row__label">Manage servers</span></span>
          <span className="servers-row__chevron" aria-hidden="true"><Icon name="down"/></span>
        </button>
      </div>
      <p className="servers-foot">Return closes this panel.</p>
    </aside>
  </Modal>
}
