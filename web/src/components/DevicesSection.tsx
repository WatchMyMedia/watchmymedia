import { useState } from 'react'
import { Link } from '@tanstack/react-router'
import { queryOptions, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, unwrap } from '../api/client'
import type { components } from '../api/schema'
import { useAuth } from '../authContext'
import { relativeTime } from '../lib/format'
import { Panel, secondaryButton } from './SettingsPanel'

type Session = components['schemas']['Session']

const sessionsQuery = (all: boolean) =>
  queryOptions({
    queryKey: ['sessions', all],
    queryFn: async () => unwrap(await api.GET('/api/auth/sessions', { params: { query: { all: all || undefined } } })),
  })

/** Where the account is signed in: browsers and TV or phone apps, each of which can be signed out. */
export function DevicesSection() {
  const auth = useAuth()
  const qc = useQueryClient()
  const canSeeAll = (auth.user?.isAdmin ?? false) && auth.adminAllowed
  const [all, setAll] = useState(false)
  const sessions = useQuery(sessionsQuery(all && canSeeAll))
  const revoke = useMutation({
    mutationFn: async (s: Session) => {
      if (s.current) return auth.logout()
      return unwrap(await api.DELETE('/api/auth/sessions/{id}', { params: { path: { id: s.id } } }))
    },
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['sessions'] }),
  })
  return (
    <Panel
      title="Signed-in devices"
      description="Browsers stay signed in for 30 days without use, TV and phone apps for a year."
      actions={
        <>
          {canSeeAll && (
            <label className="flex items-center gap-2 text-sm text-neutral-300">
              <input type="checkbox" checked={all} onChange={(e) => setAll(e.target.checked)} className="h-4 w-4 accent-accent" />
              Everyone&rsquo;s
            </label>
          )}
          <Link to="/link" search={{}} className={`${secondaryButton} inline-flex items-center`}>
            Link a TV
          </Link>
        </>
      }
    >
      {sessions.isError && <p className="px-5 pb-3 text-sm text-red-400">{sessions.error.message}</p>}
      <ul>
        {sessions.data?.items.map((s) => (
          <li key={s.id} className="flex items-center gap-3.5 border-t border-line px-5 py-3.5">
            <span aria-hidden="true" className="grid h-9 w-9 shrink-0 place-items-center rounded-lg bg-surface-2 text-neutral-400">
              <DeviceIcon session={s} />
            </span>
            <div className="min-w-0 flex-1">
              <div className="flex flex-wrap items-center gap-2">
                <span className="font-medium">{s.name || 'Unknown device'}</span>
                <span className="rounded border border-line-2 px-1.5 text-xs text-neutral-400">{s.kind === 'device' ? 'App' : 'Browser'}</span>
                {s.current && <span className="rounded bg-accent px-1.5 text-xs font-medium text-black">This device</span>}
                {all && <span className="text-xs text-neutral-400">{s.userName}</span>}
              </div>
              <div className="mt-0.5 text-xs text-neutral-400">
                {[s.kind === 'device' ? [s.product, s.version].filter(Boolean).join(' ') : '', `signed in ${relativeTime(s.createdAt)}`, `last used ${relativeTime(s.lastSeenAt)}`, s.lastIp].filter(Boolean).join(' · ')}
              </div>
            </div>
            <button
              type="button"
              disabled={revoke.isPending}
              onClick={() => revoke.mutate(s)}
              className="min-h-[36px] shrink-0 rounded-md border border-line-2 px-3 text-sm text-neutral-200 hover:border-red-400 hover:text-red-400"
            >
              Sign out
            </button>
          </li>
        ))}
        {sessions.data?.items.length === 0 && <li className="border-t border-line px-5 py-3 text-sm text-neutral-400">No sessions.</li>}
      </ul>
      {revoke.isError && <p className="border-t border-line px-5 py-3 text-sm text-red-400">{revoke.error.message}</p>}
    </Panel>
  )
}

/** A TV, a phone, or a computer, guessed from the app or browser name. */
function DeviceIcon({ session: s }: { session: Session }) {
  const label = `${s.product ?? ''} ${s.name ?? ''}`
  const kind = /\btv\b/i.test(label) ? 'tv' : s.kind === 'device' || /android|iphone|ipad|ios/i.test(label) ? 'phone' : 'computer'
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      {kind === 'tv' && (
        <>
          <rect x="2" y="5" width="20" height="13" rx="2" />
          <path d="M8 21h8" />
        </>
      )}
      {kind === 'phone' && (
        <>
          <rect x="6" y="2" width="12" height="20" rx="2" />
          <path d="M11 18h2" />
        </>
      )}
      {kind === 'computer' && (
        <>
          <rect x="3" y="4" width="18" height="12" rx="2" />
          <path d="M2 20h20" />
        </>
      )}
    </svg>
  )
}
