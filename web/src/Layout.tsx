import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { librariesQuery, serverQuery, liveTVCapabilitiesQuery, liveTVActivityQuery } from './api/queries'
import { liveActivityQuery } from './api/activity'
import { Link, Outlet, useNavigate, useRouterState } from '@tanstack/react-router'
import { useServerEvents, useEventState } from './api/events'
import { useAuth } from './authContext'
import { StatusPanel } from './components/StatusPanel'
import { BrandLockup, BrandMark } from './components/BrandMark'
import { Login } from './pages/Login'
import { startCasting } from './cast/sender'
import { CastBar, CastButton } from './components/Cast'
import { ReminderNotices } from './components/liveTV/LiveReminders'
import { serverCastsLive } from './lib/liveCast'

export function Layout() {
  const auth = useAuth()
  if (auth.status === 'loading') return <div className="grid min-h-screen place-items-center text-neutral-500">…</div>
  if (auth.status === 'setup') return <Login mode="setup" />
  if (auth.status === 'login') return <Login mode="login" />
  return <Shell />
}

function Shell() {
  useServerEvents()
  const auth = useAuth()
  const [status, setStatus] = useState(false)
  const [menu, setMenu] = useState(false)
  const [q, setQ] = useState('')
  const navigate = useNavigate()
  const path = useRouterState({ select: (s) => s.location.pathname })
  const { scans, matches, connected } = useEventState()
  const busy = Object.keys(scans).length + Object.keys(matches).length > 0
  const player = path.startsWith('/play/') || path.startsWith('/live-tv/watch/') || /^\/movies\/[^/]+\/trailer\/?$/.test(path)
  const libraries = useQuery(librariesQuery)
  const liveTV = useQuery(liveTVCapabilitiesQuery)
  const hasLibraries = !!libraries.data?.items.length
  const showLiveTV = !!liveTV.data?.enabled && !!liveTV.data.allowed
  // Admins get Activity in the bar on any network, with a dot while anything plays.
  const activityAdmin = auth.user?.isAdmin ?? false
  const live = useQuery({ ...liveActivityQuery, enabled: activityAdmin })
  const liveSessions = useQuery({ ...liveTVActivityQuery, enabled: activityAdmin && showLiveTV })
  const playing = (live.data?.plays.length ?? 0) + (liveSessions.data?.items.length ?? 0)
  // Casting needs the receiver's App ID from the server (PLAN-CAST.md §5.1).
  const server = useQuery(serverQuery)
  const castAppId = server.data?.castAppId
  // Live TV's pages offer Cast once a channel can be cast (PLAN-LIVE-TV.md §4.1).
  const castsLive = serverCastsLive(server.data?.apiLevel, liveTV.data)
  useEffect(() => {
    if (castAppId) startCasting(castAppId)
  }, [castAppId])
  // A device's code on /link lasts minutes: approve it first, then set up.
  const needsSetup =
    auth.user?.isAdmin && auth.adminAllowed && libraries.data?.items.length === 0 && !!liveTV.data && !liveTV.data.setupComplete && path !== '/setup' && path !== '/link' && !path.startsWith('/settings')
  useEffect(() => {
    if (needsSetup) void navigate({ to: '/setup' })
  }, [needsSetup, navigate])
  useEffect(() => {
    if (path === '/' && libraries.data?.items.length === 0 && liveTV.data?.setupComplete) void navigate({ to: '/live-tv', replace: true })
  }, [path, libraries.data, liveTV.data?.setupComplete, navigate])

  const pill = (active: boolean) =>
    `min-h-[36px] shrink-0 whitespace-nowrap rounded-full px-3 py-1.5 text-sm ${active ? 'bg-neutral-100 text-black' : 'text-neutral-300 hover:text-neutral-100'}`

  // Live TV reminders come on every page; their banner stays off the players.
  if (player) return <><Outlet /><ReminderNotices banner={false} /></>

  return (
    <div className="flex min-h-screen flex-col">
      <header className="sticky top-0 z-30 border-b border-line bg-ground/90 backdrop-blur">
        <div className="relative flex items-center gap-3 px-4 py-2 md:px-12">
          <Link to="/" className="mr-2 shrink-0" aria-label="WatchMyMedia home">
            {/* The mark alone on phones leaves room for the links. */}
            <BrandMark size={26} className="sm:hidden" />
            <span className="hidden sm:inline">
              <BrandLockup size={26} />
            </span>
          </Link>
          {/* On phones the links scroll sideways so the buttons stay on screen. */}
          <nav className="scrollbar-hide -my-1 flex min-w-0 flex-1 items-center gap-1 overflow-x-auto py-1 sm:flex-none">
            {hasLibraries && <Link to="/" className={pill(path === '/')}>
              Home
            </Link>}
            {libraries.data?.items.some((library) => library.type === 'tv') && <Link to="/tv" className={pill(path.startsWith('/tv') || path.startsWith('/shows'))}>
              TV Shows
            </Link>}
            {libraries.data?.items.some((library) => library.type === 'movie') && <Link to="/movies" className={pill(path.startsWith('/movies'))}>
              Movies
            </Link>}
            {showLiveTV && <Link to="/live-tv" className={pill(path.startsWith('/live-tv'))}>Live TV</Link>}
            {activityAdmin && (
              <Link to="/activity" className={`${pill(path.startsWith('/activity'))} relative`} title={playing ? `${playing} playing now` : 'Activity'}>
                Activity
                {playing > 0 && (
                  <span className="absolute right-0.5 top-1 h-2 w-2 rounded-full bg-accent ring-2 ring-ground" aria-label={`${playing} playing now`} />
                )}
              </Link>
            )}
          </nav>
          {hasLibraries && <form
            className="ml-auto hidden min-w-0 flex-1 justify-end sm:flex"
            onSubmit={(e) => {
              e.preventDefault()
              if (q.trim()) void navigate({ to: '/search', search: { q: q.trim() } })
            }}
          >
            <input
              value={q}
              onChange={(e) => setQ(e.target.value)}
              placeholder="Search"
              aria-label="Search titles"
              className="min-h-[36px] w-full max-w-xs rounded-md border border-line-2 bg-surface px-3 text-sm placeholder:text-neutral-500"
            />
          </form>}
          {/* Phones have no room for the search box: a button opens Search, which has its own. */}
          {hasLibraries && <Link
            to="/search"
            search={{}}
            aria-label="Search"
            title="Search"
            className={`grid h-9 w-9 shrink-0 place-items-center rounded-md border bg-surface hover:text-neutral-100 sm:hidden ${path.startsWith('/search') ? 'border-accent text-accent' : 'border-line-2 text-neutral-300'}`}
          >
            <svg viewBox="0 0 20 20" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true">
              <circle cx="8.5" cy="8.5" r="5.5" />
              <path d="m13 13 4.5 4.5" />
            </svg>
          </Link>}
          {(!path.startsWith('/live-tv') || castsLive) && <CastButton />}
          {hasLibraries && <button
            type="button"
            onClick={() => setStatus((s) => !s)}
            aria-label="Library status"
            title={connected ? (busy ? 'Scanning or matching…' : 'Library status') : 'Reconnecting to server events'}
            className="relative grid h-9 w-9 shrink-0 place-items-center rounded-md border border-line-2 bg-surface text-neutral-300 hover:text-neutral-100"
          >
            ⟳
            {busy && <span className="absolute right-1 top-1 h-2 w-2 animate-pulse rounded-full bg-accent" />}
            {!connected && <span className="absolute right-1 top-1 h-2 w-2 rounded-full bg-warn" />}
          </button>}
          <Link
            to="/settings"
            aria-label="Settings"
            title="Settings"
            className={`grid h-9 w-9 shrink-0 place-items-center rounded-md border bg-surface hover:text-neutral-100 ${path.startsWith('/settings') ? 'border-accent text-accent' : 'border-line-2 text-neutral-300'}`}
          >
            ⚙
          </Link>
          <div className="relative shrink-0">
            <button
              type="button"
              onClick={() => setMenu((m) => !m)}
              aria-label="Account"
              title={auth.user?.name}
              className="grid h-9 w-9 place-items-center rounded-full bg-brand text-sm font-semibold text-white ring-1 ring-brand-line"
            >
              {auth.user?.name.charAt(0).toUpperCase()}
            </button>
            {menu && (
              <div className="absolute right-0 top-11 z-40 w-48 rounded-lg border border-line-2 bg-surface p-1 text-sm shadow-xl" onMouseLeave={() => setMenu(false)}>
                <div className="px-3 py-2 text-neutral-400">
                  {auth.user?.name}
                  {auth.user?.isAdmin && <span className="ml-1 text-xs text-neutral-500">admin</span>}
                </div>
                <Link to="/activity/history" onClick={() => setMenu(false)} className="block rounded px-3 py-2 hover:bg-surface-2">
                  {activityAdmin ? 'Activity' : 'My history'}
                </Link>
                <Link to="/settings" onClick={() => setMenu(false)} className="block rounded px-3 py-2 hover:bg-surface-2">
                  Settings
                </Link>
                <button type="button" onClick={() => void auth.logout()} className="block w-full rounded px-3 py-2 text-left hover:bg-surface-2">
                  Sign out
                </button>
              </div>
            )}
          </div>
          {status && <StatusPanel onClose={() => setStatus(false)} />}
        </div>
      </header>
      <main className="flex-1 px-4 py-6 md:px-12">
        <Outlet />
      </main>
      <CastBar />
      <ReminderNotices banner />
    </div>
  )
}
