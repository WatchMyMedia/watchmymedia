import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import type { ReactNode } from 'react'
import { liveTVCapabilitiesQuery, liveTVRecordingsQuery, serverQuery } from '../../api/queries'
import { useAuth } from '../../authContext'
import { homeNotices, type HomeNotice } from '../../lib/homeNotices'
import { serverRecords } from '../../lib/liveRecordings'
import { markArrivalsSeen, useArrivals } from '../useArrivals'
import { useNow } from '../useNow'

/**
 * The strip above Continue watching: what happened on the server that this
 * person would want to know, composed from replies the app already asks
 * for: a recording under way, a clash coming up, a request that arrived, and
 * for admins what waits in the Inbox. Nothing when there is nothing to say.
 */
export function HomeNotices() {
  const auth = useAuth()
  const admin = auth.user?.isAdmin ?? false
  const server = useQuery(serverQuery)
  const caps = useQuery(liveTVCapabilitiesQuery)
  const records = !!caps.data?.enabled && !!caps.data.allowed && serverRecords(server.data?.apiLevel, caps.data)
  const now = useInfiniteQuery({ ...liveTVRecordingsQuery('now'), enabled: records })
  const clashes = useInfiniteQuery({ ...liveTVRecordingsQuery('conflicts'), enabled: records })
  const { arrived, inbox } = useArrivals()
  const at = useNow()
  const notices = homeNotices({
    recording: now.data?.pages.flatMap((p) => p.items) ?? [],
    clashes: clashes.data?.pages.flatMap((p) => p.items) ?? [],
    arrived,
    inbox: admin ? inbox : 0,
    now: at,
  })
  if (!notices.length) return null
  return (
    <ul aria-label="Today on your server" className="scrollbar-hide -mx-4 flex gap-2 overflow-x-auto px-4 md:mx-0 md:flex-wrap md:px-0">
      {notices.map((n) => (
        <li key={n.key} className="shrink-0">
          <NoticeLink notice={n} />
        </li>
      ))}
    </ul>
  )
}

const chipClass = 'inline-flex min-h-9 items-center gap-2 whitespace-nowrap rounded-[10px] border border-line-2 bg-surface px-3 text-[13px] text-neutral-300 hover:border-line-3'

function NoticeLink({ notice: n }: { notice: HomeNotice }) {
  const body: ReactNode = (
    <>
      {n.icon === 'rec' && <span aria-hidden="true" className="size-2 shrink-0 rounded-full bg-red-400" />}
      {n.icon === 'clash' && (
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" className="shrink-0 text-warn">
          <path d="M12 3 2 20h20L12 3z" />
          <path d="M12 10v4M12 17h.01" />
        </svg>
      )}
      {n.icon === 'arrived' && (
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" className="shrink-0 text-accent">
          <path d="M20 6 9 17l-5-5" />
        </svg>
      )}
      <b className="font-semibold text-neutral-100">{n.strong}</b>
      {n.rest}
      {n.action && <span className="font-semibold text-accent">{n.action}</span>}
    </>
  )
  switch (n.to.kind) {
    case 'recordings':
      return <Link to="/live-tv/recordings" className={chipClass}>{body}</Link>
    case 'inbox':
      return <Link to="/settings/inbox" className={chipClass}>{body}</Link>
    case 'movie':
      return (
        <Link to="/movies/$movieId" params={{ movieId: String(n.to.id) }} onClick={() => markArrivalsSeen(n.seen ?? [])} className={chipClass}>
          {body}
        </Link>
      )
    case 'show':
      return (
        <Link to="/shows/$showId" params={{ showId: String(n.to.id) }} onClick={() => markArrivalsSeen(n.seen ?? [])} className={chipClass}>
          {body}
        </Link>
      )
    case 'requests':
      return (
        <Link to="/settings/requests" onClick={() => markArrivalsSeen(n.seen ?? [])} className={chipClass}>
          {body}
        </Link>
      )
  }
}
