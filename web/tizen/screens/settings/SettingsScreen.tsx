import { useRef, useState, type ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { sessionsQuery } from '../../../src/api/account'
import { followsQuery } from '../../../src/api/follows'
import { preferencesQuery, rowLayoutQuery } from '../../../src/api/queries'
import { myRequestsQuery, requestOptionsQuery } from '../../../src/api/requests'
import { draftOf } from '../../../src/lib/languagePreferences'
import { useEntry, useFocus, useKeys, useSession } from '../../app/hooks'
import type { ScreenProps, SettingsGroup } from '../../app/routes'
import { TV_APP_VERSION } from '../../session/device'
import { AboutPane } from './AboutPane'
import { AccountPane } from './AccountPane'
import { HomeRowsPane } from './HomeRowsPane'
import { LanguagesPane } from './LanguagesPane'
import { NotificationsPane } from './NotificationsPane'
import { InboxPane, RequestsPane, ServerPane } from './LinkPanes'
import { PlaybackPane } from './PlaybackPane'
import { useDefaultQuality } from './defaultQuality'
import { GROUP_TITLES, groupFocusId, openingGroup, paneGroupId, SERVER_GROUPS, stepInColumn, visibleGroups } from './settingsGroups'
import { aboutLine, accountLine, followsLine, inboxLine, languagesLine, playbackLine, requestsLine, rowsLine } from './settingsWords'
import { PaneScroll } from './SettingRow'
import './settings.css'

export type PaneProps = {
  /** Put the screen's focus on the pane's first row (Settings opened at this group). */
  first: boolean
  /** Home rows: the screen a library's Edit rows asked for. */
  rowsScreen?: 'home' | 'movie' | 'tv'
}

/** Each group's line at the left: what is chosen in it. */
function useGroupLines(canRequest: boolean): Record<SettingsGroup, string> {
  const session = useSession()
  const prefs = useQuery(preferencesQuery)
  const rows = useQuery(rowLayoutQuery('me', 'home'))
  const follows = useQuery(followsQuery)
  const sessions = useQuery(sessionsQuery(false))
  const mine = useQuery({ ...myRequestsQuery, enabled: canRequest })
  const options = useQuery(requestOptionsQuery)
  const quality = useDefaultQuality(session.server.apiLevel)
  const draft = prefs.data && draftOf(prefs.data)
  const shown = rows.data?.rows.filter((r) => !r.hidden).length
  return {
    playback: playbackLine(quality.label, prefs.data?.autoplayNext),
    languages: draft ? languagesLine(draft.audio, draft.choice) : 'Your languages',
    homeRows: rows.data && shown !== undefined ? rowsLine(shown, rows.data.rows.length - shown) : 'The rows on Home',
    notifications: follows.data ? followsLine(follows.data.follows.length) : 'Shows you follow',
    account: accountLine(session.account.name, sessions.data?.items.length),
    requests: mine.data ? requestsLine(mine.data.items) : 'Where each one stands',
    about: aboutLine(TV_APP_VERSION, session.server.name, session.server.version),
    inbox: inboxLine(options.data?.inbox),
    server: 'On your phone or computer',
  }
}

const PANES: Record<SettingsGroup, (p: PaneProps) => ReactNode> = {
  playback: PlaybackPane,
  languages: LanguagesPane,
  homeRows: HomeRowsPane,
  notifications: NotificationsPane,
  account: AccountPane,
  requests: RequestsPane,
  about: AboutPane,
  inbox: InboxPane,
  server: ServerPane,
}

/** What can take focus in a column, in order. */
function stops(column: HTMLElement): HTMLElement[] {
  return [...column.querySelectorAll<HTMLElement>('[data-focus]')].filter((el) => el.getAttribute('aria-disabled') !== 'true' && el.getClientRects().length > 0)
}

/**
 * Settings (SettingsGroups on the Samsung TV canvas, as Android TV's): the
 * groups down the left, each with what is chosen in it, and the focused
 * group's rows on the right. Focus on a group swaps the pane; ▶ or OK goes
 * into it, on the row left last time or its top row; ◀ or Return go back to
 * the group, which stays marked; ▲ ▼ stay in their column. Return from the
 * groups leaves Settings. Choices open the side panel; confirmations start
 * on their safe button.
 */
export function SettingsScreen({ route }: ScreenProps<'settings'>) {
  const session = useSession()
  const entry = useEntry()
  const focus = useFocus()
  const options = useQuery(requestOptionsQuery)
  const canRequest = !!options.data && (options.data.movies || options.data.shows)
  const groups = visibleGroups({ isAdmin: session.isAdmin, canRequest })
  const [remembered] = useState(() => entry?.memory.last)
  const [open, setOpen] = useState(() => openingGroup(route.group, remembered))
  const shown = groups.includes(open) ? open : 'playback'
  const [inPane, setInPane] = useState(false)
  const lines = useGroupLines(canRequest)
  const groupsRef = useRef<HTMLDivElement>(null)
  const paneRef = useRef<HTMLDivElement>(null)
  // Opened at a group (Home's Edit rows): focus starts on that pane's top row.
  const startInPane = !!route.group && !remembered

  /** Into the pane: the row left last time, else its top row; nowhere when it has none. */
  const enter = () => {
    const pane = paneRef.current
    if (!pane) return
    const last = entry?.memory.groups[paneGroupId(shown)]
    if (!(last && focus.focusId(last, pane))) focus.focusFirst(pane)
  }
  const toGroup = () => focus.focusId(groupFocusId(shown))

  useKeys((i) => {
    const active = document.activeElement as HTMLElement | null
    const pane = paneRef.current, list = groupsRef.current
    const where = active && pane?.contains(active) ? pane : active && list?.contains(active) ? list : undefined
    if (!where || !active) return false
    if (where === list && i.action === 'right') { enter(); return true }
    if (where === pane && (i.action === 'left' || i.action === 'back')) { toGroup(); return true }
    if (i.action === 'up' || i.action === 'down') {
      const items = stops(where)
      const next = items[stepInColumn(items.length, items.indexOf(active), i.action === 'down' ? 1 : -1)]
      if (next && next !== active) focus.focus(next)
      return true
    }
    return false
  })

  const Pane = PANES[shown]
  return <div className="settings">
    <header className="settings-head">
      <h1 className="settings-head__title">Settings</h1>
      <p className="settings-head__sub">Signed in as {session.account.name} · {session.server.name}</p>
    </header>
    <div ref={groupsRef} className="settings-groups" role="tablist" aria-label="Settings groups" onFocus={() => setInPane(false)}>
      {groups.map((g) => <div key={g} style={{ display: 'contents' }}>
        {g === SERVER_GROUPS.find((s) => groups.includes(s)) && <p className="settings-heading">Server</p>}
        <button type="button" role="tab" aria-selected={g === shown} data-focus={groupFocusId(g)} data-autofocus={(!startInPane && g === shown) || undefined}
          className={`settings-group${g === shown && inPane ? ' settings-group--open' : ''}`}
          onFocus={() => setOpen(g)} onClick={enter}>
          <span className="settings-group__label">{GROUP_TITLES[g]}</span>
          <span className="settings-group__line">{lines[g]}</span>
        </button>
      </div>)}
    </div>
    <div className="settings-divider" aria-hidden="true"/>
    <section key={shown} ref={paneRef} className="settings-pane" role="tabpanel" aria-label={GROUP_TITLES[shown]} data-group={paneGroupId(shown)} onFocus={() => setInPane(true)}>
      <PaneScroll>
        <h2 className="settings-pane__title">{GROUP_TITLES[shown]}</h2>
        <Pane first={startInPane && shown === route.group} rowsScreen={route.rowsScreen}/>
      </PaneScroll>
    </section>
    <p className="settings-hints" aria-hidden="true">
      {inPane ? <><span><b>▲ ▼</b> Move</span><span><b>OK</b> Choose</span><span><b>◀</b> or <b>Return</b> Back to the groups</span></>
        : <><span><b>▲ ▼</b> Choose a group</span><span><b>▶</b> or <b>OK</b> Open it</span><span><b>Return</b> Back to Home</span></>}
    </p>
  </div>
}
