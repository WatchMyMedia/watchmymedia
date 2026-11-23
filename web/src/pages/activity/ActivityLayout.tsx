import { useQuery } from '@tanstack/react-query'
import { Link, Outlet, useRouterState } from '@tanstack/react-router'
import { liveActivityQuery } from '../../api/activity'
import { liveTVActivityQuery, liveTVCapabilitiesQuery } from '../../api/queries'
import { useSettingsAccess } from '../settings/shared'
import { adminAwayWording } from '../../lib/adminAway'
import { useActivityAdmin, useActivityManage } from './shared'

/**
 * Activity: Now playing, History, Stats, and for admins People and
 * Downloads, each its own route. Plex's imported viewings are History's
 * other source, so their address stays under History's tab.
 */
export function ActivityLayout() {
  const admin = useActivityAdmin()
  const manage = useActivityManage()
  const { adminLimit } = useSettingsAccess()
  const path = useRouterState({ select: (s) => s.location.pathname })
  const live = useQuery(liveActivityQuery)
  const caps = useQuery(liveTVCapabilitiesQuery)
  const liveTV = useQuery({ ...liveTVActivityQuery, enabled: !!caps.data?.enabled && !!caps.data.allowed })
  const playing = (live.data?.plays.length ?? 0) + (liveTV.data?.items.length ?? 0)
  const tabs: { to: '/activity' | '/activity/history' | '/activity/stats' | '/activity/users' | '/activity/downloads'; label: string; on: boolean; n?: number }[] = [
    { to: '/activity', label: 'Now playing', on: path === '/activity' || path === '/activity/', n: playing || undefined },
    { to: '/activity/history', label: 'History', on: path.startsWith('/activity/history') || path.startsWith('/activity/plex-history') },
    { to: '/activity/stats', label: 'Stats', on: path.startsWith('/activity/stats') },
    ...(admin
      ? [
          { to: '/activity/users' as const, label: 'People', on: path.startsWith('/activity/users') },
          { to: '/activity/downloads' as const, label: 'Downloads', on: path.startsWith('/activity/downloads') },
        ]
      : []),
  ]
  return (
    <div className="pt-2">
      <h1 className="text-[28px] font-bold tracking-[-.02em]">{admin ? 'Activity' : 'Your activity'}</h1>
      <p className="mt-1 text-neutral-400">
        {admin ? 'Who is watching now, everything played, and the numbers behind it.' : 'What you are watching and have watched here. Only you and the server’s admins can see it.'}
      </p>
      {/* Admins away from home read everything; the actions need home. */}
      {admin && !manage && <p className="mt-1 text-sm text-dim">{adminAwayWording(adminLimit ?? 'network').long}</p>}
      <nav className="tabs mt-[18px]" aria-label="Activity">
        {tabs.map((t) => (
          // The router marks a link to a parent path active on its children; the tab's own rule decides here.
          <Link key={t.to} to={t.to} activeProps={{}} activeOptions={{ exact: true }} aria-current={t.on ? 'page' : undefined} className="tab">
            {t.label}
            {t.n !== undefined && <span className="n">{t.n}</span>}
          </Link>
        ))}
      </nav>
      <Outlet />
    </div>
  )
}
