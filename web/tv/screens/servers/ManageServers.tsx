import { useEffect, useState } from 'react'
import { useApp, useAppState, useFocus, useSession } from '../../app/hooks'
import { displayAddress } from '../../session/address'
import { Icon } from '../../ui/Icon'
import { Dialog, Modal } from '../../ui/Overlays'
import { SwitchFailed } from './ServersPanel'
import { ADD_FOCUS, addLine, manageDetail, manageLede, removeWords, RETRY_FOCUS, serverRows, versionLine } from './serverRows'
import './servers.css'
import { BACK } from '../../app/keys'

const rowFocus = (id: string) => `manage/server/${id}`
const SWITCH_FOCUS = 'manage/switch'
const REMOVE_FOCUS = 'manage/remove'

/**
 * Settings › Account & devices › Servers, in the side panel a row opens
 * there: the Servers page (a row per server, then Add a server), a
 * server's page (its address, version and who is signed in, Switch to this
 * server, and Remove this server… apart at the foot) and Remove's
 * question, which opens on Keep. Return goes one step back each time.
 */
export function ManageServers({ onClose }: { onClose(): void }) {
  const app = useApp()
  const state = useAppState()
  const session = useSession()
  const focus = useFocus()
  const [open, setOpen] = useState<string>()
  const [last, setLast] = useState<string>()
  const [asking, setAsking] = useState(false)
  const switching = state.screen === 'signedIn' ? state.switching : undefined
  const servers = app.store.list()
  const rows = serverRows(servers, session.server.id, switching)
  const server = open ? servers.find((s) => s.id === open) : undefined
  const row = rows.find((r) => r.id === open)
  const full = app.store.full

  // Try again comes and goes with a switch that failed, and takes focus with it.
  const failed = switching?.state === 'failed' && switching.id === open ? switching : undefined
  const step = switching && switching.id === open ? switching.state : undefined
  useEffect(() => { if (step) focus.focusId(step === 'failed' ? RETRY_FOCUS : SWITCH_FOCUS) }, [focus, step])

  const toList = () => {
    app.clearSwitch()
    setLast(open)
    setOpen(undefined)
  }

  if (!server || !row) {
    const start = rows.find((r) => r.id === last) ?? rows.find((r) => r.state !== 'current') ?? rows[0]
    return <Modal key="list" onClose={onClose} className="">
      <aside role="dialog" aria-modal="true" aria-label="Servers" className="tv-panel servers-side">
        <h2 className="servers-title">Servers</h2>
        <p className="servers-lede">{manageLede(rows.length)}</p>
        <ul className="servers-list" aria-label="Servers this TV is signed in to">
          {rows.map((r) => {
            const s = servers.find((x) => x.id === r.id)
            return <li key={r.id}>
              <button type="button" data-focus={rowFocus(r.id)} data-autofocus={r.id === start?.id || undefined} className="servers-row" onClick={() => setOpen(r.id)}>
                <span className="servers-row__icon" aria-hidden="true"><Icon name="server"/></span>
                <span className="servers-row__text">
                  <span className="servers-row__label">{r.name}</span>
                  <span className="servers-row__detail">{r.state === 'tooOld' ? r.detail : s ? manageDetail(s) : r.detail}</span>
                </span>
                {r.state === 'current' && <span className="servers-current">Current</span>}
                <span className="servers-row__chevron" aria-hidden="true"><Icon name="down"/></span>
              </button>
            </li>
          })}
        </ul>
        <div className="servers-rule" aria-hidden="true"/>
        <button type="button" data-focus={ADD_FOCUS} className="servers-row" aria-disabled={full || undefined} onClick={full ? undefined : () => app.addServer()}>
          <span className="servers-row__icon servers-row__icon--plain" aria-hidden="true"><Icon name="plus"/></span>
          <span className="servers-row__text"><span className="servers-row__label">Add a server</span><span className={`servers-row__detail${full ? ' servers-row__detail--wrap' : ''}`}>{addLine(full)}</span></span>
        </button>
        <p className="servers-foot">{BACK} closes this panel.</p>
      </aside>
    </Modal>
  }

  const current = row.state === 'current'
  const busy = row.state === 'switching'
  const words = removeWords(server.name)
  const keep = () => {
    // Before the question closes, so focus is back on the row that asked.
    focus.focusId(REMOVE_FOCUS)
    setAsking(false)
  }
  const remove = () => {
    setAsking(false)
    setLast(undefined)
    setOpen(undefined)
    app.remove(server.id)
  }
  return <>
    <Modal key={`server/${server.id}`} onClose={toList} className="">
      <aside role="dialog" aria-modal="true" aria-label={server.name} className="tv-panel servers-side">
        <div><span className="servers-kicker">Servers</span><h2 className="servers-title">{server.name}</h2></div>
        {failed && <SwitchFailed failed={failed}/>}
        <div className="servers-facts">
          <div className="servers-row servers-row--plain"><span className="servers-row__label">Address</span><span className="servers-row__value">{displayAddress(server.baseUrl)}</span></div>
          <div className="servers-row servers-row--plain"><span className="servers-row__label">Version</span><span className="servers-row__value">{versionLine(server.version)}</span></div>
          <div className="servers-row servers-row--plain"><span className="servers-row__label">Signed in as</span><span className="servers-row__value">{server.account?.name ?? 'Signed out here'}</span></div>
        </div>
        {!current && <>
          <div className="servers-rule" aria-hidden="true"/>
          <button type="button" data-focus={SWITCH_FOCUS} data-autofocus className="servers-row servers-row--plain" aria-busy={busy || undefined}
            onClick={busy ? undefined : () => void app.switchTo(server.id)}>
            <Icon name="swap"/>
            <span className="servers-row__label">{server.account ? 'Switch to this server' : 'Sign in again'}</span>
            {busy && <span className="servers-row__value" role="status"><span className="tv-spinner" aria-hidden="true"/>Switching…</span>}
          </button>
        </>}
        <div className="servers-end">
          <div className="servers-rule" aria-hidden="true"/>
          <button type="button" data-focus={REMOVE_FOCUS} data-autofocus={current || undefined} className="servers-row servers-row--plain servers-row--danger" onClick={() => setAsking(true)}>
            <Icon name="trash"/>
            <span className="servers-row__label">Remove this server…</span>
          </button>
          <p className="servers-foot">{BACK} goes back to Servers.</p>
        </div>
      </aside>
    </Modal>
    {asking && <Dialog title={words.title} onCancel={keep} actions={[
      { id: 'dialog/keep', label: 'Keep', safe: true, onPress: keep },
      { id: 'dialog/remove', label: 'Remove', variant: 'danger', onPress: remove },
    ]}>
      <p className="tv-body">{words.lines[0]}</p>
      <p className="tv-body" style={{ color: 'var(--text-3)' }}>{words.lines[1]}</p>
      <p className="tv-faint" style={{ order: 1 }}>{BACK} keeps things as they are.</p>
    </Dialog>}
  </>
}
