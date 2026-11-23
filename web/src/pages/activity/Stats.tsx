import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { activityUsersQuery, cardsQuery, heatmapQuery, itemLink, seriesQuery, type Series, type StatItem } from '../../api/activity'
import { ChartCard, DataTable } from '../../components/charts/ChartCard'
import { Heatmap } from '../../components/charts/Heatmap'
import { hourLabel, weekday } from '../../components/charts/format'
import { ON_SERIES, SERIES_COLOR } from '../../components/charts/palette'
import { StackedColumns, type ColumnSeries } from '../../components/charts/StackedColumns'
import { Avatar } from '../../components/activity/StreamDetail'
import { tile } from '../../components/activity/styles'
import { Poster } from '../../components/Poster'
import { perDayWords, playShares, watchedWords } from '../../lib/activityWords'
import { useActivityAdmin, type StatsSearch } from './shared'

const PERIODS: [number, string][] = [
  [7, '7 days'],
  [30, '30 days'],
  [90, '90 days'],
  [365, 'Year'],
  [0, 'All'],
]

const HOUR = 3_600_000

function dayTick(b: string): string {
  const [y, m, d] = b.split('-').map(Number)
  return new Date(y, m - 1, d ?? 1).toLocaleDateString(undefined, d ? { day: 'numeric', month: 'short' } : { month: 'short', year: '2-digit' })
}

function dayTitle(b: string): string {
  const [y, m, d] = b.split('-').map(Number)
  return new Date(y, m - 1, d ?? 1).toLocaleDateString(undefined, d ? { weekday: 'short', day: 'numeric', month: 'long', year: 'numeric' } : { month: 'long', year: 'numeric' })
}

/** Lines from the server as chart series, in hours when showing time. */
function toSeries(s: Series | undefined, byTime: boolean): ColumnSeries[] {
  if (!s) return []
  const labels: Record<string, string> = { episode: 'Episodes', movie: 'Movies', file: 'Unmatched files' }
  const order = ['episode', 'movie', 'file']
  return s.lines
    .map((line) => ({ key: line.key, label: labels[line.key] ?? line.label, color: SERIES_COLOR[line.key] ?? SERIES_COLOR.file, values: line.values.map((v) => (byTime ? v / HOUR : v)) }))
    .sort((a, b) => order.indexOf(a.key) - order.indexOf(b.key))
}

/**
 * Stats: the period in four figures, each day's watching, how it played,
 * when people watch, the most watched, and who and on what. Every chart
 * has its numbers as a table, all at once from one button.
 */
export function Stats({ search }: { search: StatsSearch }) {
  const admin = useActivityAdmin()
  const navigate = useNavigate({ from: '/activity/stats' })
  const set = (patch: Partial<StatsSearch>) => void navigate({ search: (s) => ({ ...s, ...patch }), replace: true })
  const [tables, setTables] = useState(false)
  const days = search.days ?? 30
  const byTime = search.by !== 'plays'
  const by = byTime ? 'time' : 'plays'
  const user = admin ? search.user : undefined
  const bucket = days === 0 || days > 90 ? 'month' : 'day'
  const users = useQuery({ ...activityUsersQuery, enabled: admin })
  const cards = useQuery(cardsQuery({ days, by, user }))
  const types = useQuery(seriesQuery({ days, by, user, split: 'type', bucket }))
  const modes = useQuery(seriesQuery({ days, by, user, split: 'mode', bucket }))
  const heat = useQuery(heatmapQuery({ days, by, user }))

  const fmt = (v: number) => (byTime ? `${v < 10 ? v.toFixed(1).replace(/\.0$/, '') : Math.round(v)} h` : `${Math.round(v)}`)
  const unit = byTime ? 'Hours watched' : 'Plays'
  const typeSeries = toSeries(types.data, byTime)
  const shares = playShares(modes.data?.lines ?? [])
  const heatFmt = (v: number) => (byTime ? watchedWords(v) : `${v} ${v === 1 ? 'play' : 'plays'}`)
  const period = days === 0 ? 'All time' : days === 365 ? 'The last year' : `The last ${days} days`

  const c = cards.data
  const heatValues = heat.data?.values.map((row) => row ?? new Array<number>(24).fill(0)) ?? []
  const heatMax = heat.data?.max ?? 0
  const busiest = busiestWords(heatValues, heatMax)
  const peak = c?.peak
  const spanDays = days || Math.max(1, (types.data?.buckets.length ?? 1) * (bucket === 'month' ? 30 : 1))
  const people = admin && !user ? (c?.topUsers ?? []) : []
  const devices = c?.topDevices ?? []
  const who = users.data?.items.find((u) => u.user.id === user)?.user.name

  return (
    <div>
      <div className="mt-5 flex flex-wrap items-center gap-x-3.5 gap-y-2.5">
        <div className="flex flex-wrap gap-2" role="group" aria-label="Period">
          {PERIODS.map(([d, l]) => (
            <button key={d} type="button" aria-pressed={days === d} onClick={() => set({ days: d === 30 ? undefined : d })} className="chip">
              {l}
            </button>
          ))}
        </div>
        <div className="seg" role="group" aria-label="Measure">
          <button type="button" aria-pressed={byTime} onClick={() => set({ by: undefined })}>
            Time
          </button>
          <button type="button" aria-pressed={!byTime} onClick={() => set({ by: 'plays' })}>
            Plays
          </button>
        </div>
        {admin && (
          <select aria-label="Person" value={search.user ?? ''} onChange={(e) => set({ user: e.target.value ? Number(e.target.value) : undefined })} className="min-h-[34px] rounded-lg border border-line-2 bg-surface px-2.5 text-[13px] text-neutral-200">
            <option value="">Everyone</option>
            {users.data?.items.map(({ user: u }) => (
              <option key={u.id} value={u.id}>
                {u.name}
              </option>
            ))}
          </select>
        )}
        <button type="button" aria-pressed={tables} onClick={() => setTables((t) => !t)} className="btn btn-sm btn-ghost md:ml-auto">
          {tables ? 'Show the charts' : 'Show the numbers as tables'}
        </button>
      </div>

      {cards.isError && <p className="mt-4 text-red-400">{cards.error.message}</p>}

      <section aria-label={who ? `${period}, ${who}` : period} className={`mt-[18px] grid grid-cols-[repeat(auto-fit,minmax(min(240px,100%),1fr))] gap-3.5 transition-opacity ${cards.isPlaceholderData ? 'opacity-50' : ''}`}>
        <div className={tile}>
          <span className="text-[13px] text-neutral-400">Watched</span>
          <span className="text-[40px] font-semibold leading-[1.05] tracking-[-.02em] md:text-[48px]">{c ? watchedWords(c.playedMs) : '…'}</span>
        </div>
        <div className={tile}>
          <span className="text-[13px] text-neutral-400">Plays</span>
          <span className="text-[26px] font-semibold leading-tight tracking-[-.02em]">{c ? c.plays.toLocaleString() : '…'}</span>
          {c && perDayWords(c.plays, spanDays) && <span className="text-[13px] text-neutral-300">{perDayWords(c.plays, spanDays)}</span>}
        </div>
        <div className={tile}>
          <span className="text-[13px] text-neutral-400">Most at once</span>
          <span className="text-[26px] font-semibold leading-tight tracking-[-.02em]">{peak ? `${peak.all.count} ${peak.all.count === 1 ? 'stream' : 'streams'}` : '…'}</span>
          {peak?.all.at && (
            <span className="text-[13px] text-neutral-300">
              {new Date(peak.all.at).toLocaleDateString(undefined, { weekday: 'long', month: 'short', day: 'numeric' })}
              {peak.transcode.count > 0 ? ` · most converted at once: ${peak.transcode.count}` : ''}
            </span>
          )}
        </div>
        <div className={tile}>
          <span className="text-[13px] text-neutral-400">Busiest time</span>
          <span className="text-[22px] font-semibold leading-tight tracking-[-.02em]">{busiest || '—'}</span>
        </div>
      </section>

      <ChartCard
        className="mt-3.5"
        title={bucket === 'day' ? 'Each day' : 'Each month'}
        subtitle={unit}
        asTable={tables}
        dimmed={types.isPlaceholderData}
        chart={<StackedColumns label={`${unit} each ${bucket}, episodes and movies stacked`} buckets={types.data?.buckets ?? []} tick={dayTick} title={dayTitle} series={typeSeries} format={fmt} height={210} />}
        table={<DataTable head={[bucket === 'day' ? 'Day' : 'Month', ...typeSeries.map((s) => s.label)]} rows={(types.data?.buckets ?? []).map((b, i) => [dayTitle(b), ...typeSeries.map((s) => fmt(s.values[i]))]).reverse()} />}
      />

      <div className="mt-3.5 grid grid-cols-[repeat(auto-fit,minmax(min(480px,100%),1fr))] gap-3.5">
        <section className="panel px-5 py-[18px]" aria-labelledby="h-how">
          <div className="rowh">
            <h2 id="h-how">How it played</h2>
            <span className="note">Share of {byTime ? 'time watched' : 'plays'}</span>
          </div>
          {shares.length === 0 ? (
            <p className="mt-4 text-sm text-neutral-400">Nothing played in this period.</p>
          ) : tables ? (
            <div className="mt-4">
              <DataTable head={['How', 'Share']} rows={shares.map((s) => [s.label, `${s.share}%`])} />
            </div>
          ) : (
            <>
              <div role="img" aria-label={shares.map((s) => `${s.label} ${s.share}%`).join(', ')} className={`mt-4 flex h-7 gap-0.5 ${modes.isPlaceholderData ? 'opacity-50' : ''}`}>
                {shares.map((s) => (
                  <span
                    key={s.key}
                    title={`${s.label} · ${s.share}%`}
                    className="flex min-w-[34px] items-center overflow-hidden rounded px-2 text-[12.5px] font-semibold"
                    style={{ flex: `${s.share} 1 0`, background: SERIES_COLOR[s.key], color: ON_SERIES[s.key] }}
                  >
                    {s.share}%
                  </span>
                ))}
              </div>
              <ul className="mt-3.5 flex flex-col gap-2">
                {shares.map((s) => (
                  <li key={s.key} className="inline-flex items-center gap-[7px] text-[13px] text-neutral-300">
                    <i aria-hidden="true" className="block size-3 rounded-[3px]" style={{ background: SERIES_COLOR[s.key] }} />
                    {s.label}
                    <span className="text-neutral-400">· {s.note}</span>
                  </li>
                ))}
              </ul>
            </>
          )}
        </section>
        <ChartCard
          title="When people watch"
          subtitle="By weekday and hour"
          asTable={tables}
          dimmed={heat.isPlaceholderData}
          chart={
            heat.data ? (
              <div className="overflow-x-auto">
                <div className="min-w-[500px]">
                  <Heatmap label={`${byTime ? 'Time watched' : 'Plays started'} by weekday and hour`} values={heatValues} max={heatMax} format={heatFmt} />
                </div>
              </div>
            ) : null
          }
          table={
            <DataTable
              head={['Hour', ...[1, 2, 3, 4, 5, 6, 0].map((d) => weekday(d))]}
              rows={Array.from({ length: 24 }, (_, h) => [hourLabel(h), ...[1, 2, 3, 4, 5, 6, 0].map((d) => heatFmt(heatValues[d]?.[h] ?? 0))])}
            />
          }
        />
      </div>

      {c && (
        <div className={`transition-opacity ${cards.isPlaceholderData ? 'opacity-50' : ''}`}>
          <RankedPosters title="Most watched shows" items={c.topShows} byTime={byTime} period={period} />
          <RankedPosters title="Most watched movies" items={c.topMovies} byTime={byTime} period={period} />
          {(people.length > 0 || devices.length > 0) && (
            <div className="mt-8 grid grid-cols-[repeat(auto-fit,minmax(min(480px,100%),1fr))] gap-3.5">
              {people.length > 0 && (
                <section className="panel px-5 py-[18px]" aria-labelledby="h-who">
                  <div className="rowh">
                    <h2 id="h-who">Who watched most</h2>
                    <Link to="/activity/users" className="more">
                      People ›
                    </Link>
                  </div>
                  <Bars items={people} byTime={byTime} person />
                </section>
              )}
              {devices.length > 0 && (
                <section className="panel px-5 py-[18px]" aria-labelledby="h-what">
                  <div className="rowh">
                    <h2 id="h-what">On what</h2>
                  </div>
                  <Bars items={devices} byTime={byTime} />
                </section>
              )}
            </div>
          )}
        </div>
      )}
    </div>
  )
}

/** "Saturdays, 8 PM", from the busiest cell of the weekday-by-hour grid. */
function busiestWords(values: number[][], max: number): string {
  if (max <= 0) return ''
  for (const [d, row] of values.entries()) {
    const h = row.indexOf(max)
    if (h >= 0) return `${weekday(d, 'long')}s, ${hourLabel(h)}`
  }
  return ''
}

function amount(it: StatItem, byTime: boolean): string {
  return byTime ? watchedWords(it.playedMs) : `${it.plays} ${it.plays === 1 ? 'play' : 'plays'}`
}

function RankedPosters({ title, items, byTime, period }: { title: string; items: StatItem[]; byTime: boolean; period: string }) {
  if (!items.length) return null
  const id = `h-${title.toLowerCase().replace(/\W+/g, '-')}`
  return (
    <section aria-labelledby={id} className="mt-8">
      <div className="rowh">
        <h2 id={id}>{title}</h2>
        <span className="note">{period}</span>
      </div>
      <ol className="scrollbar-hide -mx-1 mt-3.5 flex gap-3 overflow-x-auto px-1 pb-1.5 pt-1 md:gap-4">
        {items.map((it, i) => {
          const link = itemLink({ type: it.movieId ? 'movie' : 'episode', title: it.title, movieId: it.movieId, showId: it.showId })
          const body = (
            <>
              <span className="relative block">
                <Poster path={it.posterPath} size="w185" alt="" />
                <span className="absolute left-2 top-2 rounded-[5px] bg-ground/80 px-[7px] py-0.5 text-[11px] font-bold text-neutral-100 ring-1 ring-inset ring-white/15">{i + 1}</span>
              </span>
              <span className="mt-2 block truncate text-sm font-medium">{it.title}</span>
              <span className="block truncate text-[12.5px] text-neutral-400">{amount(it, byTime)}</span>
            </>
          )
          return (
            <li key={`${it.movieId ?? ''}-${it.showId ?? ''}`} className="w-[112px] shrink-0 md:w-[132px]">
              {link ? (
                <Link {...link} aria-label={`${i + 1}. ${it.title}, ${amount(it, byTime)}`} className="card block rounded-lg">
                  {body}
                </Link>
              ) : (
                body
              )}
            </li>
          )
        })}
      </ol>
    </section>
  )
}

/** A ranked list as gold bars: the person or device, the bar, the amount. */
function Bars({ items, byTime, person }: { items: StatItem[]; byTime: boolean; person?: boolean }) {
  const top = Math.max(1, ...items.map((it) => (byTime ? it.playedMs : it.plays)))
  return (
    <ol className="mt-2.5">
      {items.map((it, i) => {
        const v = byTime ? it.playedMs : it.plays
        const name =
          person && it.userId ? (
            <Link to="/activity/users/$userId" params={{ userId: String(it.userId) }} className="flex min-w-0 items-center gap-2 hover:text-accent">
              <Avatar name={it.title} />
              <span className="truncate">{it.title}</span>
            </Link>
          ) : (
            <span className="block min-w-0 truncate" title={it.subtitle || undefined}>
              {it.title}
            </span>
          )
        return (
          <li key={`${it.userId ?? ''}-${it.deviceId ?? ''}-${i}`} className="flex min-h-[38px] items-center gap-3 text-sm">
            <span className="min-w-0 flex-[0_0_118px] sm:flex-[0_0_150px]">{name}</span>
            <i aria-hidden="true" className="block h-2 shrink-0 rounded-r bg-accent" style={{ width: `${Math.max(2, Math.round((v / top) * 58))}%` }} />
            <span className="whitespace-nowrap text-[13px] text-neutral-300">{amount(it, byTime)}</span>
          </li>
        )
      })}
    </ol>
  )
}
