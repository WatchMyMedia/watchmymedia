import { useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { librariesQuery, mediaListsQuery, rowLayoutQuery, useSaveRowLayout } from '../../../src/api/queries'
import { placements, type RowLayoutEntry } from '../../../src/lib/rowLayout'
import { useSession } from '../../app/hooks'
import { Dialog } from '../../ui/Overlays'
import { ChoicePanel } from './Panels'
import type { PaneProps } from './SettingsScreen'
import { paneRowId } from './settingsGroups'
import { PaneNote, SettingRow } from './SettingRow'
import { actionLabel, applyAction, pickScreen, possessive, rowActions, rowLine, rowNote, rowScreens, type RowAction } from './homeRows'

const id = (row: string) => paneRowId('homeRows', row)

/**
 * Settings › Home rows (SettingsHomeRows on the canvas): the Screen it
 * arranges (Home, Movies or TV Shows), then that screen's rows in order,
 * hidden ones grey. OK on a row opens Move up, Move down, Hide or Show and
 * Cancel in the side panel, which acts at once and closes; each change is
 * saved as it is made. Reset asks first, since it changes every device.
 */
export function HomeRowsPane({ first, rowsScreen }: PaneProps) {
  const session = useSession()
  const libraries = useQuery(librariesQuery)
  const screens = rowScreens(libraries.data?.items)
  const [chosen, setChosen] = useState<string>()
  const screen = pickScreen(screens, chosen, rowsScreen)
  const layout = useQuery(rowLayoutQuery('me', screen.id))
  const lists = useQuery(mediaListsQuery)
  const { save, reset } = useSaveRowLayout('me', screen.id)
  const [draft, setDraft] = useState<RowLayoutEntry[] | null>(null)
  const pending = useRef(0)
  const [message, setMessage] = useState<string>()
  const [panel, setPanel] = useState<{ kind: 'screen' } | { kind: 'row'; id: string } | null>(null)
  const [resetting, setResetting] = useState<'asking' | 'working' | 'failed' | null>(null)
  const rows = draft ?? layout.data?.rows ?? []

  const act = (rowId: string, action: RowAction) => {
    const next = applyAction(rows, rowId, action)
    if (!next) return
    setDraft(next)
    setMessage(undefined)
    pending.current++
    save.mutate(placements(next), {
      onError: () => setMessage('Couldn’t save, so your rows are as they were. Check your connection and try again.'),
      onSettled: () => { if (--pending.current === 0) setDraft(null) },
    })
  }
  const doReset = () => {
    setResetting('working')
    reset.mutate(undefined, { onSuccess: () => setResetting(null), onError: () => setResetting('failed') })
  }

  const open = panel?.kind === 'row' ? rows.findIndex((r) => r.id === panel.id) : -1
  const row = open >= 0 ? rows[open] : undefined
  return <>
    <PaneNote>The rows on {screen.name}, in your order. They are the same on every device you sign in on.</PaneNote>
    <SettingRow id={id('screen')} label="Screen" detail="Movies and TV Shows have rows of their own" value={screen.name} chevron autoFocus={first}
      onPress={() => setPanel({ kind: 'screen' })}/>
    {layout.isPending && <PaneNote>Loading…</PaneNote>}
    {layout.isError && <SettingRow id={id('rows-failed')} label="Couldn’t load the rows" detail="Check that the server is on, then try again." value="Try again" onPress={() => void layout.refetch()}/>}
    {rows.map((r, i) => {
      const note = rowNote(rows, i)
      return <SettingRow key={r.id} id={id(`row/${r.id}`)} label={`${i + 1}. ${r.title}`} detail={note ? `${rowLine(r, lists.data?.items)} · ${note}` : rowLine(r, lists.data?.items)}
        stacked={!!note} tone={r.hidden ? 'off' : undefined} value={r.hidden ? 'Hidden' : 'Options'} chevron onPress={() => setPanel({ kind: 'row', id: r.id })}/>
    })}
    {message && <p role="alert" className="settings-note" style={{ color: 'var(--error-text)' }}>{message}</p>}
    {save.isPending && <PaneNote>Saving…</PaneNote>}
    <p className="settings-note settings-note--faint">Lists are made in WatchMyMedia on the web. Lists for everyone, and your own, show up here.</p>
    <SettingRow id={id('reset')} label={`Reset ${screen.name} rows`} detail="Back to the rows everyone on this server starts with" onPress={() => setResetting('asking')}/>

    {panel?.kind === 'screen' && <ChoicePanel idPrefix="choice/screen" title="Screen" context="Home rows · whose rows to arrange" onClose={() => setPanel(null)}
      choices={screens.map((s) => ({ value: s.id, label: s.name, detail: s.line }))} value={screen.id}
      onChoose={(v) => { setChosen(v); setDraft(null); setMessage(undefined) }} foot="OK shows that screen’s rows. Return closes this panel."/>}
    {row && <ChoicePanel idPrefix="choice/row" title={row.title} context={`${screen.name} rows · row ${open + 1} of ${rows.length}${row.hidden ? ' · hidden' : ''}`}
      onClose={() => setPanel(null)} choices={rowActions(open, rows.length, row.hidden).map((a) => ({ value: a, label: actionLabel(a, screen.name) }))}
      onChoose={(a) => act(row.id, a)} foot="OK acts at once and closes. Return closes this panel."/>}
    {resetting && <Dialog title={`Reset ${possessive(screen.name)} rows?`} onCancel={() => setResetting(null)} actions={[
      { id: 'dialog/keep', label: 'Keep my rows', safe: true, onPress: () => setResetting(null) },
      { id: 'dialog/reset', label: resetting === 'working' ? 'Resetting…' : resetting === 'failed' ? 'Try again' : 'Reset rows', variant: 'danger', busy: resetting === 'working', onPress: doReset },
    ]}>
      <p className="tv-body">{screen.name} goes back to the rows everyone on {session.server.name} starts with, on every device you sign in on.</p>
      <div><span className="settings-label">Deleted</span><p className="tv-body">Your order, and which rows you hid.</p></div>
      <div><span className="settings-label">Stays</span><p className="tv-body">Your lists.</p></div>
      {resetting === 'failed' && <p role="alert" className="tv-error">Couldn’t reset. Your rows are as they were.</p>}
      <p className="tv-faint">Return keeps things as they are.</p>
    </Dialog>}
  </>
}
