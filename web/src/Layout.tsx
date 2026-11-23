import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { librariesQuery, serverQuery, liveTVCapabilitiesQuery, liveTVActivityQuery } from './api/queries'
import { liveActivityQuery } from './api/activity'
import { Outlet, useNavigate, useRouterState } from '@tanstack/react-router'
import { useServerEvents } from './api/events'
import { useAuth } from './authContext'
import { AppBar, FootBar, type Sections } from './components/AppBar'
import { Login } from './pages/Login'
import { startCasting } from './cast/sender'
import { CastBar } from './components/Cast'
import { ReminderNotices } from './components/liveTV/LiveReminders'
import { NewEpisodeNotices } from './components/NewEpisodeNotices'
import { useNotificationClicks } from './components/newEpisodeHooks'
import { serverCastsLive } from './lib/liveCast'
import { isSettingsFlow } from './lib/settingsRoutes'
import { AppPrompt } from './components/GetApp'
import { appPrompt, showsGetAppMenu } from './lib/getApp'
import { ToastHost } from './components/Toast'

export function Layout() {
  const auth = useAuth()
  if (auth.status === 'loading') return <div className="grid min-h-screen place-items-center text-neutral-500">…</div>
  if (auth.status === 'setup') return <Login mode="setup" />
  if (auth.status === 'login') return <Login mode="login" />
  return <Shell />
}

function Shell() {
  useServerEvents()
  useNotificationClicks()
  const auth = useAuth()
  // Computers get Get the app in the bar; phones and tablets their own app above it.
  const [apps] = useState(() => ({ menu: showsGetAppMenu(navigator.userAgent, navigator.maxTouchPoints), prompt: appPrompt(navigator.userAgent, navigator.maxTouchPoints) }))
  const navigate = useNavigate()
  const path = useRouterState({ select: (s) => s.location.pathname })
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
  // Casting needs the receiver's App ID from the server.
  const server = useQuery(serverQuery)
  const castAppId = server.data?.castAppId
  // Live TV's pages offer Cast once a channel can be cast.
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

  // Reminders and new episodes come on every page, from one place in each so
  // moving between them keeps their timers; their banners stay off the players.
  const notices = <Notices banner={!player} />
  if (player) return <><Outlet />{notices}</>
  // A Settings task is focused: it draws its own bar with Exit.
  if (isSettingsFlow(path)) return <><><Outlet /><CastBar /></>{notices}<ToastHost /></>

  const sections: Sections = {
    home: hasLibraries,
    movies: !!libraries.data?.items.some((library) => library.type === 'movie'),
    tv: !!libraries.data?.items.some((library) => library.type === 'tv'),
    live: showLiveTV,
    activity: activityAdmin,
    playing,
  }
  return <>
    <div className="flex min-h-screen flex-col">
      {apps.prompt && <AppPrompt app={apps.prompt} />}
      <AppBar sections={sections} castHere={!path.startsWith('/live-tv') || castsLive} getApp={apps.menu} search={hasLibraries || showLiveTV} />
      <main className="wrap flex-1 pb-10 pt-5">
        <Outlet />
      </main>
      {/* Casting's bar sits on the foot bar at phone width, and at the foot of the window above that. */}
      <div className="sticky bottom-0 z-30">
        <CastBar docked />
        <FootBar sections={sections} />
      </div>
    </div>
    {notices}
    <ToastHost />
  </>
}

/**
 * Live TV reminders and new episodes of followed shows come on every page:
 * their banners stack under the app bar on the
 * right, a new episode's below a reminder's, and stay off the players. The
 * stack scrolls when the window is too short for both (a phone on its side,
 * a page zoomed in), so every button stays reachable.
 */
function Notices({ banner }: { banner: boolean }) {
  return (
    <div className="pointer-events-none fixed inset-x-4 top-16 z-40 flex max-h-[calc(100dvh-5rem)] flex-col gap-3 overflow-y-auto overscroll-contain *:pointer-events-auto *:shrink-0 sm:left-auto sm:right-12 sm:w-100">
      <ReminderNotices banner={banner} />
      <NewEpisodeNotices banner={banner} />
    </div>
  )
}
