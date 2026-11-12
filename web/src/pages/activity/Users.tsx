import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { activityUserQuery, activityUsersQuery, formatPlayed, historyQuery, itemTitle, NETWORK_LABELS } from '../../api/activity'
import { Poster } from '../../components/Poster'
import { ModeChip } from '../../components/activity/StreamDetail'
import { relativeTime } from '../../lib/format'
import { when } from './shared'

/** Everyone's activity at a glance (admin). */
export function Users() {
  const users = useQuery(activityUsersQuery)
  if (users.isPending) return <p className="text-neutral-400">Loading…</p>
  if (users.isError) return <p className="text-red-400">{users.error.message}</p>
  return (
    <div className="overflow-x-auto rounded-lg border border-line bg-surface">
      <table className="w-full min-w-[44rem] text-sm">
        <thead>
          <tr className="border-b border-line text-left text-xs uppercase tracking-wider text-neutral-500">
            <th className="px-4 py-2 font-medium">Who</th>
            <th className="px-4 py-2 font-medium">Last seen</th>
            <th className="px-4 py-2 font-medium">Last played</th>
            <th className="px-4 py-2 text-right font-medium">Plays</th>
            <th className="px-4 py-2 text-right font-medium">Watched</th>
            <th className="px-4 py-2 text-right font-medium">Devices</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-line">
          {users.data.items.map((u) => {
            const last = u.lastPlayed ? itemTitle(u.lastPlayed.item) : null
            return (
              <tr key={u.user.id}>
                <td className="px-4 py-2.5">
                  <Link to="/activity/users/$userId" params={{ userId: String(u.user.id) }} className="font-medium hover:underline">
                    {u.user.name}
                  </Link>
                  {u.isAdmin && <span className="ml-2 text-xs text-neutral-500">admin</span>}
                  {!u.recordHistory && <span className="ml-2 text-xs text-warn">history off</span>}
                </td>
                <td className="px-4 py-2.5 text-neutral-300">
                  {u.playing > 0 ? (
                    <span className="text-accent">
                      <span className="mr-1.5 inline-block h-2 w-2 animate-pulse rounded-full bg-accent" />
                      watching now
                    </span>
                  ) : (
                    relativeTime(u.lastSeenAt)
                  )}
                </td>
                <td className="max-w-64 truncate px-4 py-2.5 text-neutral-300">
                  {last ? (
                    <>
                      {last.title}
                      {last.subtitle && <span className="text-neutral-500"> · {last.subtitle}</span>}
                    </>
                  ) : (
                    <span className="text-neutral-500">—</span>
                  )}
                </td>
                <td className="px-4 py-2.5 text-right tabular-nums">{u.plays.toLocaleString()}</td>
                <td className="px-4 py-2.5 text-right tabular-nums">{formatPlayed(u.playedMs)}</td>
                <td className="px-4 py-2.5 text-right tabular-nums">{u.devices}</td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}

/** One person's watch time, devices, favourites and latest plays. */
export function UserDetail({ userId }: { userId: number }) {
  const user = useQuery(activityUserQuery(userId))
  const recent = useInfiniteQuery(historyQuery({ user: userId }, 10))
  if (user.isPending) return <p className="text-neutral-400">Loading…</p>
  if (user.isError) return <p className="text-red-400">{user.error.message}</p>
  const u = user.data
  const rows = recent.data?.pages[0]?.items ?? []
  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-baseline justify-between gap-3">
        <h2 className="text-xl font-semibold">
          {u.user.name}
          {u.playing > 0 && <span className="ml-3 text-sm font-normal text-accent">watching now</span>}
        </h2>
        <div className="flex gap-3 text-sm">
          <Link to="/activity/history" search={{ user: userId }} className="text-neutral-300 hover:text-neutral-100">
            Full history ›
          </Link>
          <Link to="/activity/stats" search={{ user: userId }} className="text-neutral-300 hover:text-neutral-100">
            Stats ›
          </Link>
        </div>
      </div>
      {!u.recordHistory && <p className="rounded-md border border-line-2 bg-surface px-3 py-2 text-sm text-warn">History is off for {u.user.name}: their plays show in Now playing but are not kept.</p>}
      <dl className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        {u.windows.map((w) => (
          <div key={w.label} className="min-w-0 rounded-lg border border-line bg-surface p-4">
            <dt className="text-sm text-neutral-400">{w.label}</dt>
            <dd className="mt-1 text-2xl font-semibold">{formatPlayed(w.playedMs)}</dd>
            <dd className="text-xs text-neutral-500">
              {w.plays} play{w.plays === 1 ? '' : 's'}
            </dd>
          </div>
        ))}
      </dl>
      <div className="grid gap-4 lg:grid-cols-2">
        <section className="min-w-0 rounded-lg border border-line bg-surface p-4">
          <h3 className="mb-2 font-semibold">Latest plays</h3>
          {rows.length === 0 ? (
            <p className="text-sm text-neutral-500">Nothing yet.</p>
          ) : (
            <ul className="divide-y divide-line text-sm">
              {rows.map((r) => {
                const t = itemTitle(r.item)
                return (
                  <li key={r.id} className="flex items-center gap-3 py-2">
                    <div className="w-8 shrink-0">
                      <Poster path={r.item.posterPath} size="w92" alt="" />
                    </div>
                    <div className="min-w-0 flex-1">
                      <p className="truncate">
                        {t.title}
                        {t.subtitle && <span className="text-neutral-500"> · {t.subtitle}</span>}
                      </p>
                      <p className="truncate text-xs text-neutral-500">
                        {when(r.startedAt)} · {r.device?.name ?? 'unknown device'} · {formatPlayed(r.playedMs)}
                      </p>
                    </div>
                    <ModeChip mode={r.mode} />
                  </li>
                )
              })}
            </ul>
          )}
        </section>
        <section className="min-w-0 rounded-lg border border-line bg-surface p-4">
          <h3 className="mb-2 font-semibold">Devices</h3>
          {u.deviceList.length === 0 ? (
            <p className="text-sm text-neutral-500">None yet.</p>
          ) : (
            <ul className="divide-y divide-line text-sm">
              {u.deviceList.map((d) => (
                <li key={d.device.id} className="flex justify-between gap-3 py-2">
                  <div className="min-w-0">
                    <p className="truncate">{d.device.name}</p>
                    <p className="truncate text-xs text-neutral-500">
                      {[d.device.product, d.lastIp].filter(Boolean).join(' · ')} · first seen {relativeTime(d.firstSeenAt)}
                    </p>
                  </div>
                  <div className="shrink-0 text-right text-xs text-neutral-400">
                    <p>last seen {relativeTime(d.lastSeenAt)}</p>
                    <p>
                      {d.plays} play{d.plays === 1 ? '' : 's'}
                    </p>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </section>
      </div>
      <div className="grid gap-4 lg:grid-cols-2">
        {[
          ['Favourite shows', u.topShows],
          ['Favourite movies', u.topMovies],
        ].map(([title, items]) => (
          <section key={title as string} className="min-w-0 rounded-lg border border-line bg-surface p-4">
            <h3 className="mb-3 font-semibold">{title as string}</h3>
            {(items as typeof u.topShows).length === 0 ? (
              <p className="text-sm text-neutral-500">Nothing yet.</p>
            ) : (
              <ol className="grid grid-cols-5 gap-3">
                {(items as typeof u.topShows).map((it) => (
                  <li key={`${it.movieId ?? ''}-${it.showId ?? ''}`} className="min-w-0">
                    <Poster path={it.posterPath} size="w185" alt={it.title} />
                    <p className="mt-1.5 line-clamp-2 text-xs font-medium leading-tight">{it.title}</p>
                    <p className="text-[11px] text-neutral-500">{formatPlayed(it.playedMs)}</p>
                  </li>
                ))}
              </ol>
            )}
          </section>
        ))}
      </div>
      <p className="text-xs text-neutral-500">Addresses are where {u.user.name} last played from; networks are shown as {Object.values(NETWORK_LABELS).join(', ')}.</p>
    </div>
  )
}
