import { Link, Outlet, useRouterState } from '@tanstack/react-router'
import { useSettingsAccess } from '../settings/shared'
import { adminAwayWording } from '../../lib/adminAway'
import { useActivityAdmin, useActivityManage } from './shared'

/** Activity: Now playing · History · Stats · Users · Downloads, each its own route. */
export function ActivityLayout() {
  const admin = useActivityAdmin()
  const manage = useActivityManage()
  const { adminLimit } = useSettingsAccess()
  const path = useRouterState({ select: (s) => s.location.pathname })
  const tab = (active: boolean) =>
    `min-h-[36px] whitespace-nowrap rounded-full px-3 py-1.5 text-sm ${active ? 'bg-neutral-100 text-black' : 'text-neutral-300 hover:text-neutral-100'}`
  return (
    <div className="mx-auto max-w-7xl space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold">{admin ? 'Activity' : 'Your activity'}</h1>
          <p className="text-sm text-neutral-400">
            {admin ? 'Who is watching now, everything played, and the numbers behind it.' : 'What you are playing and have played. Only you and the server’s admins see this.'}
          </p>
          {/* Admins away from home read everything; the actions need home. */}
          {admin && !manage && (
            <p className="mt-1 text-sm text-neutral-500">{adminAwayWording(adminLimit ?? 'network').long}</p>
          )}
        </div>
      </div>
      <nav className="scrollbar-hide -mx-1 flex gap-1 overflow-x-auto px-1" aria-label="Activity sections">
        <Link to="/activity" className={tab(path === '/activity' || path === '/activity/')}>
          Now playing
        </Link>
        <Link to="/activity/history" className={tab(path.startsWith('/activity/history'))}>
          History
        </Link>
        <Link to="/activity/plex-history" className={tab(path.startsWith('/activity/plex-history'))}>
          Imported from Plex
        </Link>
        <Link to="/activity/stats" className={tab(path.startsWith('/activity/stats'))}>
          Stats
        </Link>
        {admin && (
          <Link to="/activity/users" className={tab(path.startsWith('/activity/users'))}>
            Users
          </Link>
        )}
        {admin && (
          <Link to="/activity/downloads" className={tab(path.startsWith('/activity/downloads'))}>
            Downloads
          </Link>
        )}
      </nav>
      <Outlet />
    </div>
  )
}
