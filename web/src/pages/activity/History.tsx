import { useEffect, useRef, useState, type ReactNode } from 'react'
import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { activityUsersQuery, autoRows, episodeLink, historyCSV, historyGroupQuery, historyQuery, itemLink, itemTitle, streamQuality, useDeleteHistory, type HistoryRow } from '../../api/activity'
import { Dialog } from '../../components/Dialog'
import { Poster } from '../../components/Poster'
import { Avatar, StreamFacts, StreamReasons, StreamTable } from '../../components/activity/StreamDetail'
import { CheckIcon, ChevronDownIcon, ChevronUpIcon, CloseIcon, SearchIcon } from '../../components/icons'
import { useNow } from '../../components/useNow'
import { byDay, clockWords, deviceWords, ENDED_WORDS, historyResult, NETWORK_WORDS, playDecision, timeWords, watchedWords } from '../../lib/activityWords'
import { formatBytes } from '../../lib/format'
import { day, num, oneOf, toFilters, useActivityAdmin, useActivityManage, type HistorySearch } from './shared'

const selectClass = 'min-h-[34px] rounded-lg border border-line-2 bg-surface px-2.5 text-[13px] text-neutral-200'

/** History's two sources: what was watched here, and what Plex brought across. */
export function HistorySource({ plex }: { plex: boolean }) {
  return (
    <nav className="seg" aria-label="Source">
      <Link to="/activity/history" aria-current={!plex ? 'page' : undefined} className={!plex ? 'on' : ''}>
        Watched here
      </Link>
      <Link to="/activity/plex-history" aria-current={plex ? 'page' : undefined} className={plex ? 'on' : ''}>
        Brought from Plex
      </Link>
    </nav>
  )
}

/**
 * History: everything played, newest first and by day, each viewing saying
 * what came of it in words; its sittings and the stream one step away.
 * Members see their own; admins the household's.
 */
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
  const [selecting, setSelecting] = useState(false)
  const [picked, setPicked] = useState<Set<number>>(() => new Set())
  const [asking, setAsking] = useState(false)
  const remove = useDeleteHistory()
  const rows = history.data?.pages.flatMap((p) => p.items) ?? []
  const first = history.data?.pages[0]
  const now = useNow(60_000)
  const days = byDay(rows, new Date(now))

  // Fetch the next page well before the end comes into view.
  const sentinel = useRef<HTMLDivElement>(null)
  const { hasNextPage, isFetchingNextPage, fetchNextPage } = history
  useEffect(() => {
    const el = sentinel.current
    if (!el || !hasNextPage) return
    const io = new IntersectionObserver(
      (es) => {
        if (es.some((e) => e.isIntersecting) && !isFetchingNextPage) void fetchNextPage()
      },
      { rootMargin: '1200px 0px' },
    )
    io.observe(el)
    return () => io.disconnect()
  }, [hasNextPage, isFetchingNextPage, fetchNextPage, rows.length])

  const toggle = (s: Set<number>, id: number) => {
    const next = new Set(s)
    if (next.has(id)) next.delete(id)
    else next.add(id)
    return next
  }
  const grouped = search.grouped !== false
  const scoped = !!(search.movie || search.show || search.episode)
  const filtered = !!(search.q || search.user || search.device || search.type || search.mode || search.network || search.completed || search.days || search.from || search.to || scoped)
  const word = grouped ? 'viewing' : 'play'
  const total = first ? `${first.total.toLocaleString()}${first.totalCapped ? '+' : ''} ${word}${first.total === 1 && !first.totalCapped ? '' : 's'}` : ''

  return (
    <div>
      <div className="mt-5 flex flex-wrap items-center gap-2.5">
        <label className="flex min-h-9 max-w-[320px] flex-[1_1_220px] items-center gap-2 rounded-[10px] border border-line-2 bg-surface pl-3 pr-2.5 text-neutral-400 focus-within:border-line-3">
          <SearchIcon size={16} />
          <span className="sr-only">Search history</span>
          <input
            type="search"
            value={text}
            onChange={(e) => setText(e.target.value)}
            placeholder={admin ? 'Search titles or people' : 'Search by title'}
            className="min-w-0 flex-1 border-0 bg-transparent text-sm text-neutral-100 outline-none placeholder:text-neutral-400"
          />
        </label>
        {admin && (
          <select aria-label="Person" value={search.user ?? ''} onChange={(e) => set({ user: num(e.target.value) })} className={selectClass}>
            <option value="">Everyone</option>
            {users.data?.items.map(({ user: u }) => (
              <option key={u.id} value={u.id}>
                {u.name}
              </option>
            ))}
          </select>
        )}
        <select
          aria-label="Period"
          value={search.days ?? (search.from || search.to ? 'custom' : '')}
          onChange={(e) => {
            const v = e.target.value
            if (v === 'custom') set({ days: undefined, from: search.from ?? day(-6), to: search.to ?? day(0) })
            else set({ days: num(v), from: undefined, to: undefined })
          }}
          className={selectClass}
        >
          <option value="">Any time</option>
          <option value="1">Today</option>
          <option value="7">Last 7 days</option>
          <option value="30">Last 30 days</option>
          <option value="365">Last year</option>
          <option value="custom">Dates…</option>
        </select>
        {!search.days && (search.from || search.to) && (
          <span className="flex items-center gap-1 text-sm text-neutral-400">
            <input type="date" aria-label="From" value={search.from ?? ''} onChange={(e) => set({ from: e.target.value || undefined })} className={selectClass} />–
            <input type="date" aria-label="To" value={search.to ?? ''} onChange={(e) => set({ to: e.target.value || undefined })} className={selectClass} />
          </span>
        )}
        <div className="flex flex-wrap items-center gap-2" role="group" aria-label="What">
          {(
            [
              [undefined, 'All'],
              ['movie', 'Movies'],
              ['episode', 'Episodes'],
            ] as const
          ).map(([v, label]) => (
            <button key={label} type="button" aria-pressed={search.type === v} onClick={() => set({ type: v })} className="chip">
              {label}
            </button>
          ))}
          {search.type === 'file' && (
            <button type="button" aria-pressed onClick={() => set({ type: undefined })} className="chip">
              Unmatched files
            </button>
          )}
        </div>
        <MoreFilters search={search} set={set} />
        <button
          type="button"
          role="switch"
          aria-checked={grouped}
          onClick={() => set({ grouped: grouped ? false : undefined })}
          className="inline-flex min-h-[34px] items-center gap-2 whitespace-nowrap text-[13px] text-neutral-300"
        >
          <span aria-hidden="true" className={`relative block h-5 w-[34px] rounded-full transition-colors ${grouped ? 'bg-accent' : 'bg-line-3'}`}>
            <span className={`absolute top-0.5 size-4 rounded-full bg-ground transition-all ${grouped ? 'left-4' : 'left-0.5'}`} />
          </span>
          Group resumed plays
        </button>
        <div className="flex gap-2 md:ml-auto">
          <a href={historyCSV(filters)} className="btn btn-sm min-h-[34px]">
            Export CSV
          </a>
          {manage && (
            <button
              type="button"
              aria-pressed={selecting}
              onClick={() => {
                setSelecting((s) => !s)
                setPicked(new Set())
              }}
              className="btn btn-sm min-h-[34px] text-red-400"
            >
              {selecting ? 'Done' : 'Delete…'}
            </button>
          )}
        </div>
      </div>

      <div className="mt-[18px] flex flex-wrap items-center gap-x-4 gap-y-2">
        <p className="text-sm text-neutral-300" aria-live="polite">
          {history.isPending ? (
            'Loading…'
          ) : first ? (
            <>
              <b className="font-semibold text-neutral-100">{total}</b>
              {!first.totalCapped && first.playedMs > 0 && ` · ${watchedWords(first.playedMs)} watched`}
            </>
          ) : null}
          {history.isFetching && !history.isPending && !isFetchingNextPage && <span className="ml-2 text-dim">updating…</span>}
        </p>
        {filtered && (
          <button
            type="button"
            onClick={() => {
              setText('')
              void navigate({ search: {}, replace: true })
            }}
            className="inline-flex items-center gap-1.5 text-[13px] text-neutral-400 hover:text-accent"
          >
            <CloseIcon size={14} />
            {scoped ? 'Show every title' : 'Clear filters'}
          </button>
        )}
        <div className="md:ml-auto">
          <HistorySource plex={false} />
        </div>
      </div>

      {selecting && (
        <div role="status" className="sticky top-[62px] z-20 mt-3 flex flex-wrap items-center gap-3 rounded-xl border border-line-2 bg-surface-2 px-4 py-2.5 text-sm">
          <span>{picked.size ? `${picked.size} chosen` : 'Choose the viewings to delete.'}</span>
          <button type="button" disabled={!picked.size || remove.isPending} onClick={() => setAsking(true)} className="btn btn-sm btn-primary ml-auto">
            Delete {picked.size || ''}
          </button>
        </div>
      )}

      {history.isError && <p className="mt-4 text-red-400">{history.error.message}</p>}
      {!history.isPending && rows.length === 0 && (
        <div className="mt-5 rounded-xl border border-dashed border-line-2 px-6 py-12 text-center text-neutral-400">
          {filtered ? 'Nothing matches.' : 'Nothing played yet. A play shows here once it lasts two minutes or reaches the credits.'}
        </div>
      )}

      <div className={history.isPlaceholderData ? 'opacity-60' : ''}>
        {days.map((d) => (
          <section key={d.key} aria-label={`${d.label}, ${d.sub}`}>
            <div className="mb-2.5 mt-[26px] flex items-baseline gap-2.5">
              <h2 className="text-base font-[650]">{d.label}</h2>
              <span className="text-[13px] text-neutral-400">{d.sub}</span>
            </div>
            <ul className="panel divide-y divide-line">
              {d.rows.map((r) => (
                <Row
                  key={`${r.groupId}-${r.id}`}
                  row={r}
                  admin={admin}
                  grouped={grouped}
                  open={open.has(r.groupId)}
                  onToggle={() => setOpen((s) => toggle(s, r.groupId))}
                  picking={selecting}
                  picked={picked.has(r.groupId)}
                  onPick={() => setPicked((s) => toggle(s, r.groupId))}
                />
              ))}
            </ul>
          </section>
        ))}
      </div>
      <div ref={sentinel} className="h-px" />
      {isFetchingNextPage && <p className="mt-4 text-center text-sm text-dim">Loading more…</p>}

      {asking && (
        <Dialog title={`Delete ${picked.size} ${picked.size === 1 ? 'viewing' : 'viewings'} from History?`} onClose={() => setAsking(false)} width="max-w-[30rem]">
          <div className="space-y-4 px-6 pb-6 pt-4">
            <p className="text-[15px] leading-relaxed text-neutral-200">They leave History, Stats and each person’s totals. Where people left off stays as it is.</p>
            {remove.isError && <p className="text-sm text-red-400">{remove.error.message}</p>}
            <div className="flex flex-wrap justify-end gap-2">
              <button type="button" onClick={() => setAsking(false)} className="btn">
                Cancel
              </button>
              <button
                type="button"
                disabled={remove.isPending}
                onClick={() =>
                  remove.mutate([...picked], {
                    onSuccess: () => {
                      setPicked(new Set())
                      setAsking(false)
                      setSelecting(false)
                    },
                  })
                }
                className="btn btn-primary"
              >
                Delete
              </button>
            </div>
          </div>
        </Dialog>
      )}
    </div>
  )
}

type FilterPick = { label: string; value: string | undefined }

/** More filters: how it played, the network, whether it was finished, and unmatched files. */
function MoreFilters({ search, set }: { search: HistorySearch; set: (patch: Partial<HistorySearch>) => void }) {
  const [open, setOpen] = useState(false)
  const root = useRef<HTMLDivElement>(null)
  const button = useRef<HTMLButtonElement>(null)
  useEffect(() => {
    if (!open) return
    const away = (e: PointerEvent) => {
      if (!root.current?.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('pointerdown', away)
    return () => document.removeEventListener('pointerdown', away)
  }, [open])
  const groups: { cap: string; key: 'mode' | 'network' | 'completed' | 'type'; picks: FilterPick[] }[] = [
    {
      cap: 'How it played',
      key: 'mode',
      picks: [
        { label: 'As it is', value: 'direct' },
        { label: 'Repackaged', value: 'remux' },
        { label: 'Sound converted', value: 'audio' },
        { label: 'Video converted', value: 'transcode' },
      ],
    },
    {
      cap: 'Network',
      key: 'network',
      picks: [
        { label: 'At home', value: 'local' },
        { label: 'Away, on the tailnet', value: 'tailnet' },
        { label: 'Away, over the internet', value: 'remote' },
      ],
    },
    {
      cap: 'Finished',
      key: 'completed',
      picks: [
        { label: 'Watched to the end', value: 'true' },
        { label: 'Stopped early', value: 'false' },
      ],
    },
    { cap: 'What', key: 'type', picks: [{ label: 'Unmatched files', value: 'file' }] },
  ]
  const active = (['mode', 'network', 'completed'] as const).filter((k) => search[k] !== undefined).length + (search.type === 'file' ? 1 : 0)
  const items = () => Array.from(root.current?.querySelectorAll<HTMLElement>('[role="menuitemradio"]') ?? [])
  return (
    <div ref={root} className="relative">
      <button
        ref={button}
        type="button"
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => {
          setOpen((o) => !o)
          requestAnimationFrame(() => items()[0]?.focus())
        }}
        className="btn btn-sm min-h-[34px]"
      >
        More filters{active ? <span className="pill pill-gold min-h-5 px-1.5">{active}</span> : null}
      </button>
      {open && (
        <div
          role="menu"
          aria-label="More filters"
          onKeyDown={(e) => {
            const all = items()
            const at = all.indexOf(document.activeElement as HTMLElement)
            if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
              e.preventDefault()
              all[(at + (e.key === 'ArrowDown' ? 1 : -1) + all.length) % all.length]?.focus()
            } else if (e.key === 'Escape') {
              e.preventDefault()
              setOpen(false)
              button.current?.focus()
            } else if (e.key === 'Tab') setOpen(false)
          }}
          className="menu absolute left-0 top-[calc(100%+6px)] w-[260px] max-w-[calc(100vw-2rem)]"
        >
          {groups.map((g) => (
            <div key={g.cap}>
              <span className="menu-cap">{g.cap}</span>
              {g.picks.map((p) => {
                const on = g.key === 'type' ? search.type === p.value : search[g.key] === p.value
                return (
                  <button
                    key={p.label}
                    type="button"
                    role="menuitemradio"
                    aria-checked={on}
                    tabIndex={-1}
                    onClick={() => {
                      const value = on ? undefined : p.value
                      if (g.key === 'mode') set({ mode: oneOf(value, ['direct', 'remux', 'audio', 'transcode'] as const) })
                      else if (g.key === 'network') set({ network: oneOf(value, ['local', 'tailnet', 'remote'] as const) })
                      else if (g.key === 'completed') set({ completed: oneOf(value, ['true', 'false'] as const) })
                      else set({ type: value === 'file' ? 'file' : undefined })
                    }}
                    className="menu-item justify-between"
                  >
                    {p.label}
                    {on && <CheckIcon size={15} className="text-accent" />}
                  </button>
                )
              })}
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

function Row({
  row: r,
  admin,
  grouped,
  open,
  onToggle,
  picking,
  picked,
  onPick,
}: {
  row: HistoryRow
  admin: boolean
  grouped: boolean
  open: boolean
  onToggle: () => void
  picking: boolean
  picked: boolean
  onPick: () => void
}) {
  const { title, subtitle } = itemTitle(r.item)
  const link = itemLink(r.item)
  const episode = episodeLink(r.item)
  const pct = r.completed ? 100 : Math.max(0, Math.min(100, r.percent))
  const name = r.user.id ? r.user.name : `${r.user.name} (deleted)`
  const converted = r.mode === 'transcode'
  return (
    <li className="flex flex-wrap items-center gap-x-4 gap-y-2 px-3.5 py-3">
      {picking && <input type="checkbox" checked={picked} onChange={onPick} aria-label={`Choose ${title}${subtitle ? `, ${subtitle}` : ''}`} className="size-4 accent-[var(--color-accent)]" />}
      <span className="flex-[0_0_62px] whitespace-nowrap text-[13px] text-neutral-400">{timeWords(r.startedAt)}</span>
      {admin &&
        (r.user.id ? (
          <Link to="/activity/users/$userId" params={{ userId: String(r.user.id) }} className="flex min-w-0 flex-[0_0_112px] items-center gap-2 font-medium hover:text-accent">
            <Avatar name={r.user.name} />
            <span className="truncate">{name}</span>
          </Link>
        ) : (
          <span className="flex min-w-0 flex-[0_0_112px] items-center gap-2 font-medium">
            <Avatar name={r.user.name} />
            <span className="truncate">{name}</span>
          </span>
        ))}
      <span className="flex min-w-0 flex-[1_1_240px] items-center gap-3">
        <span className="w-[34px] shrink-0">
          {link ? (
            <Link {...link} tabIndex={-1} aria-hidden="true">
              <Poster path={r.item.posterPath} size="w92" alt="" className="rounded" />
            </Link>
          ) : (
            <Poster path={r.item.posterPath} size="w92" alt="" className="rounded" />
          )}
        </span>
        <span className="min-w-0 flex-1">
          <span className="block truncate text-sm font-medium">
            {link ? (
              <Link {...link} className="hover:text-accent">
                {title}
              </Link>
            ) : (
              title
            )}
          </span>
          <span className="block truncate text-[12.5px] text-neutral-400">
            {episode ? (
              <Link {...episode} className="hover:text-accent">
                {subtitle}
              </Link>
            ) : (
              subtitle || (r.item.type === 'movie' ? 'Movie' : '')
            )}
          </span>
        </span>
      </span>
      <span className="flex flex-[0_1_250px] items-center gap-2.5 text-[13px] text-neutral-300">
        <span className="meter w-12 shrink-0" aria-hidden="true">
          <i style={{ width: `${pct}%` }} />
        </span>
        <span>{historyResult(r, grouped)}</span>
        {r.completed && <CheckIcon size={15} className="shrink-0 text-accent" />}
      </span>
      <span className="flex flex-[0_1_220px] flex-wrap items-center gap-x-2 gap-y-1.5 text-[13px] text-neutral-400">
        <span>{r.device?.name ?? 'An unknown device'}</span>
        {r.network !== 'local' && <span>· Away</span>}
        {converted && <span className="pill pill-warn">Converted</span>}
      </span>
      <button type="button" onClick={onToggle} aria-expanded={open} aria-label={`${open ? 'Hide' : 'Show'} the sittings of ${title}${subtitle ? `, ${subtitle}` : ''}`} className="ib ib-ghost ib-sm ml-auto">
        {open ? <ChevronUpIcon size={16} /> : <ChevronDownIcon size={16} />}
      </button>
      {open && <Sittings groupId={r.groupId} admin={admin} />}
    </li>
  )
}

function Sittings({ groupId, admin }: { groupId: number; admin: boolean }) {
  const group = useQuery(historyGroupQuery(groupId))
  const box = (children: ReactNode) => <div className="my-1 basis-full rounded-[10px] border border-line-2 bg-surface-2 px-4 py-3.5">{children}</div>
  if (group.isPending) return box(<p className="text-sm text-dim">Loading…</p>)
  if (group.isError) return box(<p className="text-sm text-red-400">{group.error.message}</p>)
  const items = group.data.items
  const last = items.at(-1)
  const detail = [...items].reverse().find((p) => p.detail)
  const decision = last ? playDecision({ mode: last.mode, reasons: last.detail?.stream.reasons ?? [], step: last.detail?.stream.step, quality: last.detail?.stream.quality, network: last.network, device: last.device?.name }) : undefined
  const time = (iso: string) => timeWords(iso)
  return box(
    <>
      <div className="overflow-x-auto">
        <table className="tbl min-w-[560px] text-[13px]">
          <thead>
            <tr>
              <th scope="col">Sitting</th>
              <th scope="col">Part watched</th>
              <th scope="col">Played</th>
              <th scope="col">Paused</th>
              <th scope="col">How it ended</th>
            </tr>
          </thead>
          <tbody>
            {items.map((p, i) => {
              const edge = i === items.length - 1 ? 'border-b-0' : ''
              return (
                <tr key={p.id}>
                  <td className={edge}>
                    {time(p.startedAt)} → {time(p.stoppedAt)}
                  </td>
                  <td className={edge}>
                    {clockWords(p.detail?.startPositionMs ?? 0)} → {clockWords(p.positionMs)}
                  </td>
                  <td className={edge}>{watchedWords(p.playedMs)}</td>
                  <td className={edge}>{p.pausedMs >= 60_000 ? watchedWords(p.pausedMs) : 'None'}</td>
                  <td className={edge}>{ENDED_WORDS[p.endReason] ?? p.endReason}</td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
      {last && (
        <p className="meta mt-3 text-[13px]">
          {last.device?.name && <span>{last.device.name}</span>}
          {deviceWords(last.device) && <span>{deviceWords(last.device)}</span>}
          <span>{NETWORK_WORDS[last.network] ?? last.network}</span>
          {decision && <span>{decision.chip}</span>}
        </p>
      )}
      {detail?.detail && (
        <details className="tech mt-3 bg-surface">
          <summary>
            Stream details<span className="hint max-sm:hidden">What was in the file and what was sent</span>
          </summary>
          <div className="tech-body">
            <StreamTable source={detail.detail.stream.source} stream={detail.detail.stream.stream} />
            <StreamFacts
              facts={[
                ['Quality', streamQuality(detail.detail.stream.quality, detail.detail.auto)],
                ...autoRows(detail.detail.auto),
                ['Encoder', detail.detail.stream.encoder || 'Not needed'],
                ['Sent', formatBytes(detail.detail.bytesSent)],
                ...(detail.detail.bufferingMs > 2000 ? [['Waiting for the picture', watchedWords(detail.detail.bufferingMs)] as [string, string]] : []),
                ...(admin || detail.ip ? [['Address', detail.ip, 'font-mono text-xs'] as [string, string, string]] : []),
              ]}
            />
            <StreamReasons reasons={detail.detail.stream.reasons} />
          </div>
        </details>
      )}
    </>,
  )
}
