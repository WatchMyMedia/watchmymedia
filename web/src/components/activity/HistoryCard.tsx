import { useInfiniteQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { historyQuery } from '../../api/activity'
import { useAuth } from '../../authContext'
import { relativeTime } from '../../lib/format'

/**
 * The detail page's History card: who watched this and when (admins see
 * everyone; others their own plays). Hidden until there is something.
 */
export function HistoryCard({ movie, show }: { movie?: number; show?: number }) {
  const auth = useAuth()
  const admin = auth.user?.isAdmin ?? false
  const history = useInfiniteQuery(historyQuery(movie ? { movie } : { show }, 6))
  const page = history.data?.pages[0]
  if (!page || page.items.length === 0) return null
  return (
    <section className="rounded-lg border border-line bg-surface p-4">
      <div className="flex items-baseline justify-between gap-3">
        <h3 className="text-xs font-semibold uppercase tracking-wider text-neutral-500">{admin ? 'History' : 'Your history'}</h3>
        <Link to="/activity/history" search={movie ? { movie } : { show }} className="text-xs text-neutral-400 hover:text-neutral-100">
          {page.total > page.items.length ? `All ${page.total.toLocaleString()}${page.totalCapped ? '+' : ''} ›` : 'Details ›'}
        </Link>
      </div>
      <ul className="mt-2 space-y-1.5 text-sm">
        {page.items.map((r) => {
          const ep =
            r.item.type === 'episode' && r.item.seasonNumber != null && r.item.episodeNumber != null
              ? `S${String(r.item.seasonNumber).padStart(2, '0')}E${String(r.item.episodeNumber).padStart(2, '0')}`
              : ''
          return (
            <li key={r.id} className="flex justify-between gap-3">
              <span className="min-w-0 truncate text-neutral-200">
                {[admin ? r.user.name : '', ep].filter(Boolean).join(' · ') || relativeTime(r.startedAt)}
              </span>
              <span className="shrink-0 text-neutral-500">
                {admin || ep ? `${relativeTime(r.startedAt)} · ` : ''}
                {r.completed ? '✓' : `${r.percent}%`}
              </span>
            </li>
          )
        })}
      </ul>
    </section>
  )
}
