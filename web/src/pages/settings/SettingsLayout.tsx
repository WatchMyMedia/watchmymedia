import type { ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, Outlet, useRouterState } from '@tanstack/react-router'
import { healthQuery, librariesQuery } from '../../api/queries'
import { settingsQuery } from '../../api/settings'
import { requestOptionsQuery } from '../../api/requests'
import { useEventState } from '../../api/events'
import { adminAwayWording } from '../../lib/adminAway'
import { useSettingsAccess } from './shared'

type SettingsPath =
  | '/settings/playback'
  | '/settings/account'
  | '/settings/general'
  | '/settings/libraries'
  | '/settings/live-tv'
  | '/settings/users'
  | '/settings/integrations'
  | '/settings/plex'
  | '/settings/requests'
  | '/settings/inbox'
  | '/settings/history'
  | '/settings/transcoding'
  | '/settings/subtitles'
  | '/settings/security'
  | '/settings/backups'
  | '/settings/lists'
  | '/settings/about'

type Tab = { to: SettingsPath; label: string; extra?: (current: boolean) => ReactNode }

/**
 * Settings: a tab per page, in a You group everyone has and a Server group
 * for admins. Phones show the tabs as a list on /settings and one page at a
 * time; wider screens keep the tabs beside the page and open on Playback.
 */
export function SettingsLayout() {
  const { admin, adminLimit } = useSettingsAccess()
  const path = useRouterState({ select: (s) => s.location.pathname.replace(/\/$/, '') })
  const index = path === '/settings'
  const libraries = useQuery({ ...librariesQuery, enabled: admin })
  const settings = useQuery({ ...settingsQuery, enabled: admin })
  const unmatched = libraries.data?.items.reduce((n, l) => n + l.unmatchedCount, 0) ?? 0
  const turnstile = settings.data?.turnstile
  const options = useQuery(requestOptionsQuery)
  const canRequest = !!(options.data?.movies || options.data?.shows)
  const inbox = options.data?.inbox ?? 0

  const you: Tab[] = [
    { to: '/settings/playback', label: 'Playback' },
    { to: '/settings/subtitles', label: 'Audio & subtitles' },
    { to: '/settings/account', label: 'Account & devices' },
    ...(canRequest ? [{ to: '/settings/requests' as const, label: 'Your requests' }] : []),
    { to: '/settings/about', label: 'About' },
  ]
  const server: Tab[] = [
    { to: '/settings/general', label: 'General' },
    // Problem reports still need an admin when no request backend is connected.
    // The Server group itself is restricted by useSettingsAccess below.
    {
      to: '/settings/inbox',
      label: 'Inbox',
      extra: () =>
        inbox > 0 && (
          <span title={`${inbox.toLocaleString()} waiting`} className="rounded-full bg-accent px-1.5 text-[11px] font-semibold text-black">
            {inbox.toLocaleString()}
          </span>
        ),
    },
    {
      to: '/settings/libraries',
      label: 'Libraries',
      extra: () =>
        unmatched > 0 && (
          <span title={`${unmatched.toLocaleString()} files need a match`} className="rounded-full bg-warn px-1.5 text-[11px] font-semibold text-black">
            {unmatched.toLocaleString()}
          </span>
        ),
    },
    { to: '/settings/lists', label: 'Lists' },
    { to: '/settings/live-tv', label: 'Live TV' },
    { to: '/settings/users', label: 'Users' },
    { to: '/settings/integrations', label: 'Integrations' },
    { to: '/settings/plex', label: 'Plex' },
    { to: '/settings/history', label: 'Watch history' },
    { to: '/settings/transcoding', label: 'Transcoding' },
    {
      to: '/settings/security',
      label: 'Security',
      extra: (current) => turnstile?.enabled && <span className={`text-xs font-medium ${current ? 'md:text-emerald-700' : ''} text-emerald-400`}>On</span>,
    },
    { to: '/settings/backups', label: 'Backups' },
  ]
  // Wide screens show Playback on /settings itself, so its tab is the current one there.
  const isCurrent = (to: SettingsPath) => path === to || (index && to === '/settings/playback')

  return (
    <div className="mx-auto flex max-w-6xl flex-col gap-8 md:flex-row md:items-start md:gap-12">
      <aside className={`${index ? '' : 'hidden'} md:block md:w-56 md:shrink-0`}>
        <h1 className="mb-5 text-2xl font-semibold tracking-tight md:px-3">Settings</h1>
        <nav aria-label="Settings sections" className="space-y-6">
          <TabGroup label="You" tabs={you} isCurrent={isCurrent} />
          {admin && <TabGroup label="Server" tabs={server} isCurrent={isCurrent} />}
          {adminLimit && (
            <div>
              <GroupLabel>Server</GroupLabel>
              <p className="flex gap-2.5 rounded-lg border border-line-2 p-3 text-sm text-neutral-400">
                <LockIcon />
                {adminAwayWording(adminLimit).short}
              </p>
            </div>
          )}
        </nav>
        <ServerCard name={settings.data ? settings.data.serverName || settings.data.serverNameDefault : undefined} />
      </aside>
      <div className={`${index ? 'hidden md:block' : ''} min-w-0 flex-1`}>
        <Link to="/settings" className="-ml-1.5 mb-2 inline-flex min-h-[44px] items-center gap-1 pr-2 text-sm text-neutral-300 hover:text-neutral-100 md:hidden">
          <Chevron direction="left" />
          Settings
        </Link>
        <Outlet />
      </div>
    </div>
  )
}

/** Wraps a Server page: its content for admins on an admin network, else why it is not shown. */
export function ServerOnly({ children }: { children: ReactNode }) {
  const { admin, adminLimit } = useSettingsAccess()
  if (admin) return children
  return (
    <p className="rounded-xl border border-line bg-surface p-5 text-neutral-300">
      {adminLimit
        ? `${adminAwayWording(adminLimit).long} Playback, Audio & subtitles and Account & devices work from anywhere. So does reading Activity.`
        : 'Only the server’s admins can change these settings.'}
    </p>
  )
}

function TabGroup({ label, tabs, isCurrent }: { label: string; tabs: Tab[]; isCurrent: (to: SettingsPath) => boolean }) {
  return (
    <div>
      <GroupLabel>{label}</GroupLabel>
      {/* Phones get rows to open, wider screens a column of tabs. */}
      <ul className="divide-y divide-line overflow-hidden rounded-xl border border-line bg-surface md:space-y-0.5 md:divide-y-0 md:overflow-visible md:rounded-none md:border-0 md:bg-transparent">
        {tabs.map((t) => {
          const current = isCurrent(t.to)
          return (
            <li key={t.to}>
              <Link
                to={t.to}
                aria-current={current ? 'page' : undefined}
                className={`flex min-h-[52px] items-center gap-3 px-4 hover:bg-surface-2 md:min-h-[36px] md:rounded-lg md:px-3 ${
                  current ? 'md:bg-neutral-100 md:font-medium md:text-black md:hover:bg-neutral-100' : 'md:text-neutral-300 md:hover:text-neutral-100'
                }`}
              >
                <span className="flex-1">{t.label}</span>
                {t.extra?.(current)}
                <Chevron direction="right" className="text-neutral-500 md:hidden" />
              </Link>
            </li>
          )
        })}
      </ul>
    </div>
  )
}

function GroupLabel({ children }: { children: ReactNode }) {
  return <div className="px-1 pb-2 text-xs font-semibold uppercase tracking-wider text-neutral-400 md:px-3">{children}</div>
}

/** The server's name, version and live-update connection, under the tabs. */
function ServerCard({ name }: { name?: string }) {
  const health = useQuery(healthQuery)
  const { connected } = useEventState()
  if (!health.data) return null
  return (
    <div className="mt-6 rounded-lg border border-line p-3 text-xs text-neutral-400">
      <div className="flex items-center gap-2 font-medium text-neutral-200">
        <span aria-hidden="true" className="h-2 w-2 rounded-full bg-emerald-400" />
        {name || 'Server running'}
      </div>
      <div className="mt-0.5">
        Version {health.data.version} · <span className={connected ? '' : 'text-warn'}>events {connected ? 'connected' : 'reconnecting'}</span>
      </div>
    </div>
  )
}

function Chevron({ direction, className = '' }: { direction: 'left' | 'right'; className?: string }) {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" className={className}>
      <path d={direction === 'left' ? 'm15 18-6-6 6-6' : 'm9 18 6-6-6-6'} />
    </svg>
  )
}

function LockIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" className="mt-0.5 shrink-0">
      <rect x="4" y="11" width="16" height="10" rx="2" />
      <path d="M8 11V7a4 4 0 0 1 8 0v4" />
    </svg>
  )
}
