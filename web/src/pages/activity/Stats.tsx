import { useQuery } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { activityUsersQuery, cardsQuery, formatPlayed, heatmapQuery, itemLink, itemTitle, seriesQuery, type Series, type StatItem } from '../../api/activity'
import { ChartCard, DataTable } from '../../components/charts/ChartCard'
import { Heatmap } from '../../components/charts/Heatmap'
import { hourLabel, weekday } from '../../components/charts/format'
import { SERIES_COLOR } from '../../components/charts/palette'
import { StackedColumns, type ColumnSeries } from '../../components/charts/StackedColumns'
import { Poster } from '../../components/Poster'
import { useActivityAdmin, when, type StatsSearch } from './shared'

const PERIODS: [number, string][] = [
  [7, '7 days'],
  [30, '30 days'],
  [90, '90 days'],
  [365, '12 months'],
  [0, 'All time'],
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
function toSeries(s: Series | undefined, byTime: boolean, merge?: Record<string, string>): ColumnSeries[] {
  if (!s) return []
  const out = new Map<string, ColumnSeries>()
  const labels: Record<string, string> = { direct: 'Direct play', remux: 'Direct stream', transcode: 'Transcode' }
  for (const line of s.lines) {
    const key = merge?.[line.key] ?? line.key
    const cur = out.get(key) ?? { key, label: labels[key] ?? line.label, color: SERIES_COLOR[key] ?? '#898781', values: new Array(s.buckets.length).fill(0) }
    line.values.forEach((v, i) => (cur.values[i] += byTime ? v / HOUR : v))
    out.set(key, cur)
  }
  // Fixed order, so a colour and a place belong to the same thing every time.
  const order = ['episode', 'movie', 'file', 'direct', 'remux', 'transcode']
  return [...out.values()].sort((a, b) => order.indexOf(a.key) - order.indexOf(b.key))
}

export function Stats({ search }: { search: StatsSearch }) {
  const admin = useActivityAdmin()
  const navigate = useNavigate({ from: '/activity/stats' })
  const set = (patch: Partial<StatsSearch>) => void navigate({ search: (s) => ({ ...s, ...patch }), replace: true })
  const days = search.days ?? 30
  const byTime = search.by === 'time'
  const by = byTime ? 'time' : 'plays'
  const user = admin ? search.user : undefined
  const bucket = days === 0 || days > 90 ? 'month' : 'day'
  const users = useQuery({ ...activityUsersQuery, enabled: admin })
  const cards = useQuery(cardsQuery({ days, by, user }))
  const types = useQuery(seriesQuery({ days, by, user, split: 'type', bucket }))
  const modes = useQuery(seriesQuery({ days, by, user, split: 'mode', bucket }))
  const months = useQuery(seriesQuery({ days: days === 0 ? 0 : Math.max(days, 365), by, user, split: 'type', bucket: 'month' }))
  const heat = useQuery(heatmapQuery({ days, by, user }))

  const fmt = (v: number) => (byTime ? `${v < 10 ? v.toFixed(1) : Math.round(v)} h` : `${Math.round(v)}`)
  const unit = byTime ? 'Hours watched' : 'Plays'
  const typeSeries = toSeries(types.data, byTime)
  const modeSeries = toSeries(modes.data, byTime, { audio: 'transcode' })
  const monthSeries = toSeries(months.data, byTime)
  const monthTotal: ColumnSeries[] = months.data
    ? [{ key: 'all', label: unit, color: SERIES_COLOR.episode, values: months.data.buckets.map((_, i) => monthSeries.reduce((s, x) => s + x.values[i], 0)) }]
    : []
  const heatFmt = (v: number) => (byTime ? formatPlayed(v) : `${v} play${v === 1 ? '' : 's'}`)

  const c = cards.data
  const heatValues = heat.data?.values.map((row) => row ?? new Array<number>(24).fill(0)) ?? []
  const heatMax = heat.data?.max ?? 0
  let busiest = ''
  heatValues.forEach((row, d) =>
    row.forEach((v, h) => {
      if (heatMax > 0 && v === heatMax && !busiest) busiest = `${weekday(d, 'long')}s, ${hourLabel(h)}`
    }),
  )
  const peak = c?.peak
  const select = 'min-h-[36px] rounded-md border border-line-2 bg-ground px-2 text-sm'

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center gap-2">
        <div className="flex rounded-md border border-line-2 p-0.5" role="radiogroup" aria-label="Period">
          {PERIODS.map(([d, l]) => (
            <button
              key={d}
              type="button"
              role="radio"
              aria-checked={days === d}
              onClick={() => set({ days: d === 30 ? undefined : d })}
              className={`min-h-[32px] rounded px-2.5 text-sm ${days === d ? 'bg-neutral-100 text-black' : 'text-neutral-300 hover:text-neutral-100'}`}
            >
              {l}
            </button>
          ))}
        </div>
        <div className="flex rounded-md border border-line-2 p-0.5" role="radiogroup" aria-label="Measure">
          {(['plays', 'time'] as const).map((b) => (
            <button
              key={b}
              type="button"
              role="radio"
              aria-checked={by === b}
              onClick={() => set({ by: b === 'time' ? 'time' : undefined })}
              className={`min-h-[32px] rounded px-2.5 text-sm ${by === b ? 'bg-neutral-100 text-black' : 'text-neutral-300 hover:text-neutral-100'}`}
            >
              {b === 'plays' ? 'Plays' : 'Time'}
            </button>
          ))}
        </div>
        {admin && (
          <select aria-label="User" value={search.user ?? ''} onChange={(e) => set({ user: e.target.value ? Number(e.target.value) : undefined })} className={select}>
            <option value="">Everyone</option>
            {users.data?.items.map(({ user: u }) => (
              <option key={u.id} value={u.id}>
                {u.name}
              </option>
            ))}
          </select>
        )}
      </div>

      {cards.isError && <p className="text-red-400">{cards.error.message}</p>}

      <dl className={`grid grid-cols-2 gap-3 transition-opacity lg:grid-cols-4 ${cards.isPlaceholderData ? 'opacity-50' : ''}`}>
        <Tile label="Plays" value={c ? c.plays.toLocaleString() : '…'} />
        <Tile label="Watched" value={c ? formatPlayed(c.playedMs) : '…'} />
        <Tile
          label="Most at once"
          value={peak ? String(peak.all.count) : '…'}
          note={peak?.all.at ? `${new Date(peak.all.at).toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' })} · most transcodes at once ${peak.transcode.count}` : undefined}
        />
        <Tile label="Busiest time" value={busiest || '—'} small />
      </dl>

      <div className="grid gap-4 xl:grid-cols-2">
        <ChartCard
          title={`${unit} per ${bucket}`}
          subtitle="By what was played"
          dimmed={types.isPlaceholderData}
          chart={<StackedColumns label={`${unit} per ${bucket} by type`} buckets={types.data?.buckets ?? []} tick={dayTick} title={dayTitle} series={typeSeries} format={fmt} />}
          table={<DataTable head={[bucket === 'day' ? 'Day' : 'Month', ...typeSeries.map((s) => s.label)]} rows={(types.data?.buckets ?? []).map((b, i) => [dayTitle(b), ...typeSeries.map((s) => fmt(s.values[i]))]).reverse()} />}
        />
        <ChartCard
          title="How it streamed"
          subtitle="Direct play costs the server nothing; transcodes cost the most"
          dimmed={modes.isPlaceholderData}
          chart={<StackedColumns label={`${unit} per ${bucket} by stream`} buckets={modes.data?.buckets ?? []} tick={dayTick} title={dayTitle} series={modeSeries} format={fmt} />}
          table={<DataTable head={[bucket === 'day' ? 'Day' : 'Month', ...modeSeries.map((s) => s.label)]} rows={(modes.data?.buckets ?? []).map((b, i) => [dayTitle(b), ...modeSeries.map((s) => fmt(s.values[i]))]).reverse()} />}
        />
        <ChartCard
          title="When people watch"
          subtitle={`${byTime ? 'Time watched' : 'Plays started'} by weekday and hour`}
          dimmed={heat.isPlaceholderData}
          chart={heat.data ? <Heatmap label="Plays by weekday and hour" values={heatValues} max={heatMax} format={heatFmt} /> : null}
          table={
            <DataTable
              head={['Hour', ...[1, 2, 3, 4, 5, 6, 0].map((d) => weekday(d))]}
              rows={Array.from({ length: 24 }, (_, h) => [hourLabel(h), ...[1, 2, 3, 4, 5, 6, 0].map((d) => heatFmt(heatValues[d]?.[h] ?? 0))])}
            />
          }
        />
        <ChartCard
          title={`${unit} per month`}
          subtitle={days === 0 ? 'All time' : 'The last twelve months'}
          dimmed={months.isPlaceholderData}
          chart={<StackedColumns label={`${unit} per month`} buckets={months.data?.buckets ?? []} tick={dayTick} title={dayTitle} series={monthTotal} format={fmt} />}
          table={<DataTable head={['Month', unit]} rows={(months.data?.buckets ?? []).map((b, i) => [dayTitle(b), fmt(monthTotal[0]?.values[i] ?? 0)]).reverse()} />}
        />
      </div>

      {c && (
        <div className={`space-y-6 transition-opacity ${cards.isPlaceholderData ? 'opacity-50' : ''}`}>
          <div className="grid gap-4 lg:grid-cols-2">
            <PosterList title="Most watched shows" items={c.topShows} byTime={byTime} />
            <PosterList title="Most watched movies" items={c.topMovies} byTime={byTime} />
            {!user && <PosterList title="Most popular shows" items={c.popularShows} byTime={byTime} popular />}
            {!user && <PosterList title="Most popular movies" items={c.popularMovies} byTime={byTime} popular />}
          </div>
          <div className="grid gap-4 lg:grid-cols-3">
            {admin && !user && <RankList title="Top people" items={c.topUsers} byTime={byTime} user />}
            <RankList title="Top devices" items={c.topDevices} byTime={byTime} />
            <section className="min-w-0 rounded-lg border border-line bg-surface p-4">
              <h3 className="mb-2 font-semibold">Recently watched</h3>
              {c.recent.length === 0 ? (
                <p className="text-sm text-neutral-500">Nothing yet.</p>
              ) : (
                <ul className="space-y-2 text-sm">
                  {c.recent.map((r) => {
                    const t = itemTitle(r.item)
                    return (
                      <li key={r.id} className="flex justify-between gap-3">
                        <span className="min-w-0 truncate">
                          {t.title}
                          {t.subtitle && <span className="text-neutral-500"> · {t.subtitle}</span>}
                        </span>
                        <span className="shrink-0 text-neutral-500">{admin ? `${r.user.name}, ` : ''}{when(r.startedAt)}</span>
                      </li>
                    )
                  })}
                </ul>
              )}
            </section>
          </div>
        </div>
      )}
    </div>
  )
}

function Tile({ label, value, note, small }: { label: string; value: string; note?: string; small?: boolean }) {
  return (
    <div className="min-w-0 rounded-lg border border-line bg-surface p-4">
      <dt className="text-sm text-neutral-400">{label}</dt>
      <dd className={`mt-1 font-semibold ${small ? 'text-lg' : 'text-2xl'}`}>{value}</dd>
      {note && <dd className="mt-1 text-xs text-neutral-500">{note}</dd>}
    </div>
  )
}

function amount(it: StatItem, byTime: boolean, popular = false): string {
  if (popular) return `${it.users} ${it.users === 1 ? 'person' : 'people'} · ${it.plays} play${it.plays === 1 ? '' : 's'}`
  return byTime ? `${formatPlayed(it.playedMs)} · ${it.plays} play${it.plays === 1 ? '' : 's'}` : `${it.plays} play${it.plays === 1 ? '' : 's'} · ${formatPlayed(it.playedMs)}`
}

function PosterList({ title, items, byTime, popular }: { title: string; items: StatItem[]; byTime: boolean; popular?: boolean }) {
  return (
    <section className="min-w-0 rounded-lg border border-line bg-surface p-4">
      <h3 className="mb-3 font-semibold">{title}</h3>
      {items.length === 0 ? (
        <p className="text-sm text-neutral-500">Nothing in this period.</p>
      ) : (
        <ol className="grid grid-cols-5 gap-3">
          {items.map((it, i) => {
            const link = itemLink({ type: it.movieId ? 'movie' : 'episode', title: it.title, movieId: it.movieId, showId: it.showId })
            const body = (
              <>
                <Poster path={it.posterPath} size="w185" alt={it.title} />
                <p className="mt-1.5 line-clamp-2 text-xs font-medium leading-tight">
                  <span className="text-neutral-500">{i + 1}. </span>
                  {it.title}
                </p>
                <p className="mt-0.5 text-[11px] leading-tight text-neutral-500">{amount(it, byTime, popular)}</p>
              </>
            )
            return (
              <li key={`${it.movieId ?? ''}-${it.showId ?? ''}`} className="min-w-0">
                {link ? (
                  <Link {...link} className="card block">
                    {body}
                  </Link>
                ) : (
                  body
                )}
              </li>
            )
          })}
        </ol>
      )}
    </section>
  )
}

function RankList({ title, items, byTime, user }: { title: string; items: StatItem[]; byTime: boolean; user?: boolean }) {
  return (
    <section className="min-w-0 rounded-lg border border-line bg-surface p-4">
      <h3 className="mb-2 font-semibold">{title}</h3>
      {items.length === 0 ? (
        <p className="text-sm text-neutral-500">Nothing in this period.</p>
      ) : (
        <ol className="space-y-2 text-sm">
          {items.map((it, i) => (
            <li key={`${it.userId ?? ''}-${it.deviceId ?? ''}-${i}`} className="flex justify-between gap-3">
              <span className="min-w-0 truncate">
                <span className="text-neutral-500">{i + 1}. </span>
                {user && it.userId ? (
                  <Link to="/activity/users/$userId" params={{ userId: String(it.userId) }} className="hover:underline">
                    {it.title}
                  </Link>
                ) : (
                  it.title
                )}
                {it.subtitle && <span className="text-neutral-500"> · {it.subtitle}</span>}
              </span>
              <span className="shrink-0 tabular-nums text-neutral-400">{amount(it, byTime)}</span>
            </li>
          ))}
        </ol>
      )}
    </section>
  )
}
