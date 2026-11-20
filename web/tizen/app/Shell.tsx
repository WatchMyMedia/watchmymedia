import { useEffect, useEffectEvent, useLayoutEffect, useMemo, useState, useSyncExternalStore, type ComponentType } from 'react'
import { QueryClientProvider, useQuery } from '@tanstack/react-query'
import { liveTVCapabilitiesQuery } from '../../src/api/queries'
import { requestOptionsQuery } from '../../src/api/requests'
import { EntryContext, NavContext, SessionContext, useInput, useKeys, useSession, type Nav } from './hooks'
import { ROW_STOP_EVENT } from './input'
import { scopeName } from './focus'
import { NavStack } from './nav'
import { chromeOf, isSection, ROUTES, sameRoute, SECTION_ROUTES, sectionOf, type Route, type ScreenProps, type Section } from './routes'
import type { ActiveSession } from '../session/active'
import { Rail } from '../ui/Rail'
import { Banner } from '../ui/Overlays'
import { railBadge } from '../ui/railBadge'
import { LeaveDialog } from './LeaveDialog'

/** Shown after the stream has been down this long: brief drops pass unnoticed. */
const OFFLINE_AFTER_MS = 5000

function OfflineBanner() {
  const session = useSession()
  const online = useSyncExternalStore(session.connection.subscribe, session.connection.snapshot)
  const [shown, setShown] = useState(false)
  useEffect(() => {
    const timer = setTimeout(() => setShown(!online), online ? 0 : OFFLINE_AFTER_MS)
    return () => clearTimeout(timer)
  }, [online])
  if (!shown || online) return null
  return <Banner icon="offline" title={`Can’t reach ${session.server.name}`}>Trying again. What’s on screen may be out of date until it’s back.</Banner>
}

function Frame({ nav, entries, exit }: { nav: NavStack<Route>; entries: readonly ReturnType<NavStack<Route>['snapshot']>[number][]; exit(): void }) {
  const session = useSession()
  const input = useInput()
  const [leaving, setLeaving] = useState(false)
  const [pulse, setPulse] = useState(0)
  const top = entries[entries.length - 1]
  const chrome = chromeOf(top.route)
  const capabilities = useQuery(liveTVCapabilitiesQuery)
  const liveTV = !!capabilities.data?.enabled && !!capabilities.data.allowed
  const options = useQuery(requestOptionsQuery)
  const current = sectionOf(nav.section())

  const navApi: Nav = useMemo(() => ({
    push: (route) => nav.push(route),
    replace: (route) => nav.replace(route),
    openSection: (route) => { if (!nav.openSection(route)) input.focus.focusContent() },
    back: () => { if (!nav.back()) setLeaving(true) },
    play: (play) => nav.push({ name: 'player', play }),
  }), [nav, input])

  // The top screen's memory takes focus changes, and coming back to a screen
  // puts focus where it was once the screen has drawn it.
  useLayoutEffect(() => {
    input.focus.setMemory(top.memory)
    input.focus.restore([top.memory.last])
  }, [input, top])

  useEffect(() => {
    input.focus.setHooks({
      openRail: () => chromeOf(nav.top.route) === 'rail' ? input.focus.focusRail() || true : true,
      rowStopped: (row) => { row.dispatchEvent(new CustomEvent(ROW_STOP_EVENT)); setPulse((p) => p + 1) },
    })
    return () => input.focus.setHooks({})
  }, [input, nav])

  // Return with the rail open closes it first, back to where focus was.
  useKeys((i) => {
    if (i.action !== 'back' || scopeName(document.activeElement) !== 'rail') return false
    input.focus.focusContent()
    return true
  }, true, 'overlay')

  // What no screen took: Return walks back the stack, and asks before leaving at Home; Guide opens Live TV.
  const onApp = useEffectEvent((action: string) => {
    if (action === 'back') { if (!nav.back()) setLeaving(true); return true }
    if (action === 'guide' && liveTV) { nav.openSection(SECTION_ROUTES.live); return true }
    return false
  })
  useEffect(() => {
    input.setAppHandler((i) => onApp(i.action))
    return () => input.setAppHandler(undefined)
  }, [input])

  const open = (section: Section) => navApi.openSection(SECTION_ROUTES[section])
  const Screen = ROUTES[top.route.name].screen as ComponentType<ScreenProps>
  return <NavContext.Provider value={navApi}>
    <div className="tv-shell">
      {chrome === 'rail' && <Rail current={current} liveTV={liveTV} badge={railBadge(options.data, session.isAdmin)} userName={session.account.name} onOpen={open} pulse={pulse}/>}
      <main key={top.key} className={`tv-content tv-content--${chrome}`} data-scope="content">
        <EntryContext.Provider value={top}><Screen route={top.route}/></EntryContext.Provider>
      </main>
      {chrome === 'rail' && <OfflineBanner/>}
      {leaving && <LeaveDialog onStay={() => setLeaving(false)} onLeave={exit}/>}
    </div>
  </NavContext.Provider>
}

/**
 * The signed-in app: the rail, the screen on top of the stack, and what
 * sits over every screen (the leave dialog, the offline banner). One shell
 * per session, with the session's own query cache.
 */
export function Shell({ session, exit }: { session: ActiveSession; exit(): void }) {
  const nav = useMemo(() => new NavStack<Route>({ name: 'home' }, { isSection, same: sameRoute }), [])
  const entries = useSyncExternalStore(nav.subscribe, nav.snapshot)
  return <QueryClientProvider client={session.queryClient}>
    <SessionContext.Provider value={session}>
      <Frame nav={nav} entries={entries} exit={exit}/>
    </SessionContext.Provider>
  </QueryClientProvider>
}
