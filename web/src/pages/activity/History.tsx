import { useEffect, useRef, useState } from 'react'
import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import {
  activityUsersQuery,
  autoRows,
  episodeLink,
  formatPlayed,
  historyCSV,
  historyGroupQuery,
  historyQuery,
  itemLink,
  itemTitle,
  NETWORK_LABELS,
  streamQuality,
  useDeleteHistory,
  type HistoryRow,
} from '../../api/activity'
import { Poster } from '../../components/Poster'
import { ModeChip, NetworkChip, StreamDetail } from '../../components/activity/StreamDetail'
import { formatBytes, formatDuration } from '../../lib/format'
import { day, num, oneOf, toFilters, useActivityAdmin, useActivityManage, when, type HistorySearch } from './shared'

export function History({ search }: { search: HistorySearch }) {
  const admin = useActivityAdmin()
  const manage = useActivityManage()
  const navigate = useNavigate({ from: '/activity/history' })
  const set = (patch: Partial<HistorySearch>) => void navigate({ search: (s) => ({ ...s, ...patch }), replace: true })
  const [text, setText] = useState(search.q ?? '')
  // 150 ms after typing stops, the search goes into the URL and the query
  // runs; a newer search aborts the one in flight.
  useEffect(() => {
    const t = window.setTimeout(() => {
      if ((search.q ?? '') !== text.trim()) set({ q: text.trim() || undefined })
    }, 150)
    return () => window.clearTimeout(t)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [text])
  const filters = toFilters(search)
  const history = useInfiniteQuery(historyQuery(filters))
  // The activity list of people, which admins read from any network.
  const users = useQuery({ ...activityUsersQuery, enabled: admin })
  const [open, setOpen] = useState<Set<number>>(() => new Set())
  const [picked, setPicked] = useState<Set<number>>(() => new Set())
  const remove = useDeleteHistory()
  const rows = history.data?.pages.flatMap((p) => p.items) ?? []
  const first = history.data?.pages[0]

  // Fetch the next page well before the end comes into view.
  const sentinel = useRef<HTMLDivElement>(null)
  const { hasNextPage, isFetchingNextPage, fetchNextPage } = history
  useEffect(() => {
    const el = sentinel.current
    if (!el || !hasNextPage) return
    const io = new IntersectionObserver((es) => {
      if (es.some((e) => e.isIntersecting) && !isFetchingNextPage) void fetchNextPage()
    }, { rootMargin: '1200px 0px' })
    io.observe(el)
    return () => io.disconnect()
  }, [hasNextPage, isFetchingNextPage, fetchNextPage, rows.length])

  const toggle = (set: Set<number>, id: number) => {
    const next = new Set(set)
    if (next.has(id)) next.delete(id)
    else next.add(id)
    return next
  }
  const select = 'min-h-[36px] rounded-md border border-line-2 bg-ground px-2 text-sm'
  const grouped = search.grouped !== false
  const scoped = !!(search.movie || search.show || search.episode)

  return (
    <div className="space-y-4">
      <div className="sticky top-[53px] z-20 -mx-4 space-y-2 border-b border-line bg-ground/95 px-4 py-3 backdrop-blur md:-mx-12 md:px-12">
        <div className="flex flex-wrap items-center gap-2">
          <input
            type="search"
            value={text}
            onChange={(e) => setText(e.target.value)}
            placeholder={admin ? 'Search titles, people, devices or addresses' : 'Search titles or devices'}
            aria-label="Search history"
            className="min-h-[36px] min-w-0 flex-1 basis-64 rounded-md border border-line-2 bg-surface px-3 text-sm placeholder:text-neutral-500"
          />
          {admin && (
            <select aria-label="User" value={search.user ?? ''} onChange={(e) => set({ user: num(e.target.value) })} className={select}>
              <option value="">Everyone</option>
              {users.data?.items.map(({ user: u }) => (
                <option key={u.id} value={u.id}>
                  {u.name}
                </option>
              ))}
            </select>
          )}
          <select aria-label="Period" value={search.days ?? (search.from || search.to ? 'custom' : '')} onChange={(e) => {
            const v = e.target.value
            if (v === 'custom') set({ days: undefined, from: search.from ?? day(-6), to: search.to ?? day(0) })
            else set({ days: num(v), from: undefined, to: undefined })
          }} className={select}>
            <option value="">Any time</option>
            <option value="1">Today</option>
            <option value="7">Last 7 days</option>
            <option value="30">Last 30 days</option>
            <option value="365">Last year</option>
            <option value="custom">Dates…</option>
          </select>
          {!search.days && (search.from || search.to) && (
            <span className="flex items-center gap-1 text-sm text-neutral-400">
              <input type="date" aria-label="From" value={search.from ?? ''} onChange={(e) => set({ from: e.target.value || undefined })} className={select} />
              –
              <input type="date" aria-label="To" value={search.to ?? ''} onChange={(e) => set({ to: e.target.value || undefined })} className={select} />
            </span>
          )}
        </div>
        <div className="flex flex-wrap items-center gap-2 text-sm">
          <select aria-label="Type" value={search.type ?? ''} onChange={(e) => set({ type: oneOf(e.target.value, ['movie', 'episode', 'file'] as const) })} className={select}>
            <option value="">Movies and episodes</option>
            <option value="movie">Movies</option>
            <option value="episode">Episodes</option>
            <option value="file">Unmatched files</option>
          </select>
          <select aria-label="Stream" value={search.mode ?? ''} onChange={(e) => set({ mode: oneOf(e.target.value, ['direct', 'remux', 'audio', 'transcode'] as const) })} className={select}>
            <option value="">Any stream</option>
            <option value="direct">Direct play</option>
            <option value="remux">Direct stream</option>
            <option value="audio">Audio transcode</option>
            <option value="transcode">Transcode</option>
          </select>
          <select aria-label="Network" value={search.network ?? ''} onChange={(e) => set({ network: oneOf(e.target.value, ['local', 'tailnet', 'remote'] as const) })} className={select}>
            <option value="">Any network</option>
            {Object.entries(NETWORK_LABELS).map(([k, v]) => (
              <option key={k} value={k}>
                {v}
              </option>
            ))}
          </select>
          <select aria-label="Finished" value={search.completed ?? ''} onChange={(e) => set({ completed: oneOf(e.target.value, ['true', 'false'] as const) })} className={select}>
            <option value="">Finished or not</option>
            <option value="true">Finished</option>
            <option value="false">Not finished</option>
          </select>
          <label className="flex min-h-[36px] items-center gap-2 text-neutral-300">
            <input type="checkbox" checked={grouped} onChange={(e) => set({ grouped: e.target.checked ? undefined : false })} />
            Group resumed plays
          </label>
          {scoped && (
            <button type="button" onClick={() => set({ movie: undefined, show: undefined, episode: undefined })} className="rounded-full border border-line-2 px-3 py-1 text-neutral-300 hover:text-neutral-100">
              One title only ✕
            </button>
          )}
        </div>
      </div>

      <div className="flex flex-wrap items-center justify-between gap-3 text-sm">
        <p className="text-neutral-400" aria-live="polite">
          {history.isPending
            ? 'Loading…'
            : first
              ? `${first.total.toLocaleString()}${first.totalCapped ? '+' : ''} ${grouped ? 'viewing' : 'play'}${first.total === 1 && !first.totalCapped ? '' : 's'}${first.totalCapped || !first.playedMs ? '' : ` · ${formatPlayed(first.playedMs)} watched`}`
              : ''}
          {history.isFetching && !history.isPending && !isFetchingNextPage && <span className="ml-2 text-neutral-600">updating…</span>}
        </p>
        <div className="flex items-center gap-3">
          {manage && picked.size > 0 && (
            <button
              type="button"
              disabled={remove.isPending}
              onClick={() => {
                if (window.confirm(`Delete ${picked.size} row${picked.size === 1 ? '' : 's'} from history? Daily totals are recomputed.`))
                  remove.mutate([...picked], { onSuccess: () => setPicked(new Set()) })
              }}
              className="min-h-[36px] rounded-md border border-red-800 px-3 text-red-300 hover:bg-red-950 disabled:opacity-50"
            >
              Delete {picked.size}
            </button>
          )}
          <a href={historyCSV(filters)} className="min-h-[36px] rounded-md border border-line-2 px-3 leading-[36px] text-neutral-300 hover:text-neutral-100">
            Export CSV
          </a>
        </div>
      </div>

      {history.isError && <p className="text-red-400">{history.error.message}</p>}
      {!history.isPending && rows.length === 0 && (
        <div className="rounded-lg border border-dashed border-line-2 px-6 py-12 text-center text-neutral-400">
          {search.q || Object.values(filters).some((v) => v !== undefined) ? 'Nothing matches.' : 'Nothing played yet. Plays show here once they last two minutes or reach the credits.'}
        </div>
      )}

      {rows.length > 0 && (
        <div className="overflow-hidden rounded-lg border border-line bg-surface">
          <div className="hidden grid-cols-[1.5rem_10.5rem_7rem_minmax(0,1fr)_10rem_6rem_7rem_6rem_4.5rem] gap-3 border-b border-line px-4 py-2 text-xs uppercase tracking-wider text-neutral-500 lg:grid">
            <span />
            <span>When</span>
            <span>Who</span>
            <span>Title</span>
            <span>Device</span>
            <span>Network</span>
            <span>Stream</span>
            <span>Played</span>
            <span className="text-right">Done</span>
          </div>
          <ul className={`divide-y divide-line ${history.isPlaceholderData ? 'opacity-60' : ''}`}>
            {rows.map((r) => (
              <Row
                key={`${r.groupId}-${r.id}`}
                row={r}
                admin={admin}
                manage={manage}
                grouped={grouped}
                open={open.has(r.groupId)}
                onToggle={() => setOpen((s) => toggle(s, r.groupId))}
                picked={picked.has(r.groupId)}
                onPick={() => setPicked((s) => toggle(s, r.groupId))}
              />
            ))}
          </ul>
        </div>
      )}
      <div ref={sentinel} className="h-px" />
      {isFetchingNextPage && <p className="text-center text-sm text-neutral-500">Loading more…</p>}
    </div>
  )
}

function Row({
  row: r,
  admin,
  manage,
  grouped,
  open,
  onToggle,
  picked,
  onPick,
}: {
  row: HistoryRow
  admin: boolean
  manage: boolean
  grouped: boolean
  open: boolean
  onToggle: () => void
  picked: boolean
  onPick: () => void
}) {
  const { title, subtitle } = itemTitle(r.item)
  const link = itemLink(r.item)
  const episode = episodeLink(r.item)
  return (
    <li>
      <div className="grid grid-cols-[1.5rem_minmax(0,1fr)_auto] items-center gap-x-3 gap-y-1 px-4 py-2.5 text-sm lg:grid-cols-[1.5rem_10.5rem_7rem_minmax(0,1fr)_10rem_6rem_7rem_6rem_4.5rem]">
        {manage ? (
          <input type="checkbox" checked={picked} onChange={onPick} aria-label={`Select ${title}`} className="row-span-3 lg:row-span-1" />
        ) : (
          <span className="row-span-3 lg:row-span-1" />
        )}
        <button type="button" onClick={onToggle} aria-expanded={open} className="col-start-2 text-left text-neutral-300 lg:col-start-auto">
          <span className="tabular-nums">{when(r.startedAt)}</span>
          {grouped && r.groupCount > 1 && <span className="ml-1.5 rounded bg-surface-2 px-1 text-xs text-neutral-400" title={`${r.groupCount} plays, from ${when(r.groupStartedAt)}`}>×{r.groupCount}</span>}
        </button>
        <span className="hidden truncate text-neutral-200 lg:block">{r.user.id ? r.user.name : `${r.user.name} (deleted)`}</span>
        <div className="col-span-2 flex min-w-0 items-center gap-3 lg:col-span-1">
          <div className="hidden w-8 shrink-0 sm:block">
            <Poster path={r.item.posterPath} size="w92" alt="" />
          </div>
          <div className="min-w-0">
            <p className="truncate font-medium">
              {link ? (
                <Link {...link} className="hover:underline">
                  {title}
                </Link>
              ) : (
                title
              )}
            </p>
            <p className="truncate text-xs text-neutral-400">
              {episode ? (
                <Link {...episode} className="hover:text-neutral-100 hover:underline">
                  {subtitle}
                </Link>
              ) : (
                subtitle
              )}
              <span className="lg:hidden">
                {subtitle ? ' · ' : ''}
                {r.user.name} · {r.device?.name ?? 'unknown device'} · {formatPlayed(r.playedMs)}
              </span>
            </p>
          </div>
        </div>
        <span className="hidden truncate text-neutral-300 lg:block" title={r.device?.platform}>
          {r.device?.name ?? '—'}
        </span>
        <span className="hidden lg:block">
          <NetworkChip network={r.network} />
        </span>
        <span className="hidden lg:block">
          <ModeChip mode={r.mode} />
        </span>
        <span className="hidden tabular-nums text-neutral-300 lg:block">{formatPlayed(r.playedMs)}</span>
        <span className="col-start-3 row-start-1 text-right tabular-nums lg:col-start-auto lg:row-start-auto" title={r.completed ? 'Finished' : `${r.percent}% of the way through`}>
          {r.completed ? <span className="text-accent">✓</span> : <span className="text-neutral-400">{r.percent}%</span>}
        </span>
      </div>
      {open && <GroupDetail groupId={r.groupId} admin={admin} />}
    </li>
  )
}

function GroupDetail({ groupId, admin }: { groupId: number; admin: boolean }) {
  const group = useQuery(historyGroupQuery(groupId))
  if (group.isPending) return <p className="px-12 pb-4 text-sm text-neutral-500">Loading…</p>
  if (group.isError) return <p className="px-12 pb-4 text-sm text-red-400">{group.error.message}</p>
  return (
    <ol className="space-y-3 bg-ground/50 px-4 pb-4 pt-1 lg:pl-12">
      {group.data.items.map((p, i) => (
        <li key={p.id} className="rounded-md border border-line bg-surface p-3">
          <p className="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm">
            <span className="font-medium">{group.data.items.length > 1 ? `Play ${i + 1}` : 'Play'}</span>
            <span className="tabular-nums text-neutral-300">
              {when(p.startedAt)} – {new Date(p.stoppedAt).toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' })}
            </span>
            <span className="text-neutral-400">
              {formatDuration(p.detail?.startPositionMs ?? 0) || '0:00'} → {formatDuration(p.positionMs) || '0:00'} of {formatDuration(p.durationMs)}
            </span>
            <span className="text-neutral-400">
              played {formatPlayed(p.playedMs)}
              {p.pausedMs > 60_000 ? `, paused ${formatPlayed(p.pausedMs)}` : ''}
            </span>
            <span className="text-neutral-500">{ENDED[p.endReason] ?? p.endReason}</span>
          </p>
          <p className="mt-1 text-xs text-neutral-400">
            {p.user.name} on {p.device?.name ?? 'an unknown device'}
            {p.device?.platform ? ` (${p.device.platform})` : ''} · {NETWORK_LABELS[p.network]}
            {admin || p.ip ? ` · ${p.ip}` : ''}
          </p>
          {p.detail && (
            <details className="mt-2">
              <summary className="cursor-pointer text-sm text-neutral-300 hover:text-neutral-100">
                <ModeChip mode={p.mode} reasons={p.detail.stream.reasons} /> <span className="ml-1">stream details</span>
              </summary>
              <div className="mt-2">
                <StreamDetail
                  source={p.detail.stream.source}
                  stream={p.detail.stream.stream}
                  reasons={p.detail.stream.reasons}
                  extra={[
                    ['Quality', streamQuality(p.detail.stream.quality, p.detail.auto)],
                    ...autoRows(p.detail.auto),
                    ...(p.detail.stream.encoder ? [['Encoder', p.detail.stream.encoder] as [string, string]] : []),
                    ['Sent', formatBytes(p.detail.bytesSent)],
                    ...(p.detail.bufferingMs > 2000 ? [['Buffering', formatPlayed(p.detail.bufferingMs)] as [string, string]] : []),
                  ]}
                />
              </div>
            </details>
          )}
        </li>
      ))}
    </ol>
  )
}

const ENDED: Record<string, string> = {
  stopped: 'stopped',
  ended: 'reached the end',
  closed: 'page closed',
  timeout: 'stopped reporting',
  terminated: 'stopped by an admin',
}
