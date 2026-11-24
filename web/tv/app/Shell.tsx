import { useEffect, useEffectEvent, useLayoutEffect, useMemo, useState, useSyncExternalStore, type ComponentType } from 'react'
import { QueryClientProvider, useQuery } from '@tanstack/react-query'
import { liveTVCapabilitiesQuery } from '../../src/api/queries'
import { requestOptionsQuery } from '../../src/api/requests'
import { EntryContext, NavContext, SessionContext, useApp, useAppState, useInput, useKeys, useSession, type Nav } from './hooks'
import { ROW_STOP_EVENT } from './input'
import { scopeName } from './focus'
import { NavStack } from './nav'
import { chromeOf, isSection, ROUTES, sameRoute, SECTION_ROUTES, sectionOf, type Route, type ScreenProps, type Section } from './routes'
import type { ActiveSession } from '../session/active'
import type { Arrival } from '../session/session'
import { Rail } from '../ui/Rail'
import { Dialog } from '../ui/Overlays'
import { railBadge } from '../ui/railBadge'
import { LeaveDialog } from './LeaveDialog'
import { platform } from '../platform/current'
import { ReminderBanner } from '../screens/live/ReminderBanner'
import { keepSearch, searchScope } from '../screens/search/keptSearch'
import { AddedDialog, NOTE_MS, SwitchNote } from '../screens/servers/Arrival'
import { ServersPanel } from '../screens/servers/ServersPanel'
import { accountWords } from '../screens/servers/serverRows'
import { useWatchlistsOn } from '../screens/watchlists/useWatchlistsOn'

/** Shown after the stream has been down this long: brief drops pass unnoticed. */
const OFFLINE_AFTER_MS = 5000
/** After Try again now, how long before the dialog comes back if the server still doesn't answer. */
const RETRY_GRACE_MS = 10_000

/**
 * Losing the server: a dialog with Try again now focused (Samsung's network
 * popup), and Choose another server. Return is Try again now.
 */
function OfflineDialog({ onServers }: { onServers(): void }) {
  const session = useSession()
  const online = useSyncExternalStore(session.connection.subscribe, session.connection.snapshot)
  const [shown, setShown] = useState(false)
  const [tried, setTried] = useState(0)
  useEffect(() => {
    const timer = setTimeout(() => setShown(!online), online ? 0 : tried ? RETRY_GRACE_MS : OFFLINE_AFTER_MS)
    return () => clearTimeout(timer)
  }, [online, tried])
  if (!shown || online) return null
  const retry = () => { setShown(false); setTried((n) => n + 1); session.connection.retry() }
  return <Dialog title={`Can’t reach ${session.server.name}`} onCancel={retry} actions={[
    { id: 'offline/retry', label: 'Try again now', variant: 'primary', safe: true, onPress: retry },
    { id: 'offline/servers', label: 'Choose another server', onPress: () => { setShown(false); setTried((n) => n + 1); onServers() } },
  ]}>
    <p className="tv-lede">This TV is on the network, but the server isn’t answering. It may be off, restarting or updating.</p>
    <p className="tv-note">This TV keeps trying by itself. What is on screen may be out of date until it is back.</p>
  </Dialog>
}

/** The sessions that have said how the app came to them ("Now on …", the server just added): once each. */
const greeted = new WeakSet<ActiveSession>()

function Frame({ nav, entries, arrival, exit }: { nav: NavStack<Route>; entries: readonly ReturnType<NavStack<Route>['snapshot']>[number][]; arrival?: Arrival; exit(): void }) {
  const app = useApp()
  const state = useAppState()
  const session = useSession()
  const input = useInput()
  const [leaving, setLeaving] = useState(false)
  const [servers, setServers] = useState<'rail' | 'screen'>()
  const [arrived, setArrived] = useState(() => greeted.has(session) ? undefined : arrival?.how)
  const [pulse, setPulse] = useState(0)
  const top = entries[entries.length - 1]
  const chrome = chromeOf(top.route)
  const capabilities = useQuery(liveTVCapabilitiesQuery)
  const liveTV = !!capabilities.data?.enabled && !!capabilities.data.allowed
  const options = useQuery(requestOptionsQuery)
  const current = sectionOf(nav.section())
  const watchlists = useWatchlistsOn()

  const navApi: Nav = useMemo(() => ({
    push: (route) => nav.push(route),
    replace: (route) => nav.replace(route),
    openSection: (route) => { if (!nav.openSection(route)) input.focus.focusContent() },
    back: () => { if (!nav.back() && !platform.backAtHome()) setLeaving(true) },
    play: (play) => nav.push({ name: 'player', play }),
    servers: () => setServers('screen'),
  }), [nav, input])

  // A search result on a server that couldn't be switched to: Servers says so, with Try again.
  const switching = state.screen === 'signedIn' ? state.switching : undefined
  if (!servers && switching?.state === 'failed' && switching.open) setServers('screen')

  // "Now on …" goes by itself; a session says how the app came to it once.
  useEffect(() => { greeted.add(session) }, [session])
  useEffect(() => {
    if (arrived !== 'switched') return
    const timer = setTimeout(() => setArrived(undefined), NOTE_MS)
    return () => clearTimeout(timer)
  }, [arrived])

  // The top screen's memory takes focus changes, and coming back to a screen
  // puts focus where it was once the screen has drawn it. Under a dialog or
  // panel that opened with the screen, focus stays there; closing it comes
  // back to the screen.
  useLayoutEffect(() => {
    input.focus.setMemory(top.memory)
    if (!input.focus.scope('modal')) input.focus.restore([top.memory.last])
  }, [input, top])

  useEffect(() => {
    input.focus.setHooks({
      openRail: () => chromeOf(nav.top.route) === 'rail' ? input.focus.focusRail() || true : true,
      rowStopped: (row) => { row.dispatchEvent(new CustomEvent(ROW_STOP_EVENT)); setPulse((p) => p + 1) },
    })
    return () => input.focus.setHooks({})
  }, [input, nav])

  // Return with the rail open closes it first, back to where focus was.
  // Focus parked on the closed rail while a screen loads lets Return go on.
  useKeys((i) => {
    if (i.action !== 'back' || scopeName(document.activeElement) !== 'rail' || input.focus.parked) return false
    input.focus.focusContent()
    return true
  }, true, 'overlay')

  // What no screen took: Back walks back the stack, and at Home the TV leaves its own way (LG) or the app asks first; Guide opens Live TV.
  const onApp = useEffectEvent((action: string) => {
    if (action === 'back') { if (!nav.back() && !platform.backAtHome()) setLeaving(true); return true }
    if (action === 'guide' && liveTV) { nav.openSection(SECTION_ROUTES.live); return true }
    return false
  })
  useEffect(() => {
    input.setAppHandler((i) => onApp(i.action))
    return () => input.setAppHandler(undefined)
  }, [input])

  const open = (section: Section) => navApi.openSection(SECTION_ROUTES[section])
  const Screen = ROUTES[top.route.name].screen as ComponentType<ScreenProps>
  const words = accountWords(session.account.name, session.server.name, app.store.list().length, watchlists)
  const account = { initial: session.account.name.slice(0, 1).toUpperCase(), name: session.account.name, server: session.server.name, label: words.label }
  return <NavContext.Provider value={navApi}>
    <div className="tv-shell">
      {chrome === 'rail' && <Rail current={current} liveTV={liveTV} badge={railBadge(options.data, session.isAdmin)} account={account}
        onAccount={() => setServers('rail')} accountOpen={!!servers} onOpen={open} pulse={pulse}/>}
      <main key={top.key} className={`tv-content tv-content--${chrome}`} data-scope="content">
        <EntryContext.Provider value={top}><Screen route={top.route}/></EntryContext.Provider>
      </main>
      {top.route.name !== 'player' && <OfflineDialog onServers={() => setServers('screen')}/>}
      <ReminderBanner player={top.route.name === 'player'}/>
      {arrived === 'switched' && <SwitchNote/>}
      {arrived === 'added' && <AddedDialog onDone={() => setArrived(undefined)}/>}
      {servers && top.route.name !== 'player' && <ServersPanel from={servers} onClose={() => setServers(undefined)}
        onManage={() => { setServers(undefined); nav.openSection({ name: 'settings', group: 'account', servers: true }) }}
        onWatchlists={watchlists ? () => { setServers(undefined); nav.push({ name: 'watchlists' }) } : undefined}/>}
      {leaving && <LeaveDialog onStay={() => setLeaving(false)} onLeave={exit}/>}
    </div>
  </NavContext.Provider>
}

/** Each session's screens, kept while the app steps out to add a server or sign in to another and comes back. */
const stacks = new WeakMap<ActiveSession, NavStack<Route>>()

/**
 * A session's stack: Home, or, for a session a search result on another
 * server switched to, Home, then Search with the same words, then the
 * title, so Return walks back through them on the new server.
 */
function stackFor(session: ActiveSession, arrival: Arrival | undefined): NavStack<Route> {
  const had = stacks.get(session)
  if (had) return had
  const nav = new NavStack<Route>({ name: 'home' }, { isSection, same: sameRoute })
  const opening = arrival?.open
  if (opening) {
    nav.openSection({ name: 'search' })
    keepSearch(searchScope(session.serial, nav.top.key), opening.text)
    nav.push(opening.target.kind === 'movie' ? { name: 'movie', id: opening.target.id }
      : { name: 'show', id: opening.target.id, season: opening.target.season, episodeId: opening.target.episodeId })
  }
  stacks.set(session, nav)
  return nav
}

/**
 * The signed-in app: the rail, the screen on top of the stack, and what
 * sits over every screen (Servers, the leave dialog, the offline banner).
 * One shell per session, with the session's own query cache; `arrival` is
 * how the app came to this session from another, which it says once.
 */
export function Shell({ session, arrival, exit }: { session: ActiveSession; arrival?: Arrival; exit(): void }) {
  const nav = useMemo(() => stackFor(session, arrival), [session, arrival])
  const entries = useSyncExternalStore(nav.subscribe, nav.snapshot)
  return <QueryClientProvider client={session.queryClient}>
    <SessionContext.Provider value={session}>
      <Frame nav={nav} entries={entries} arrival={arrival} exit={exit}/>
    </SessionContext.Provider>
  </QueryClientProvider>
}
