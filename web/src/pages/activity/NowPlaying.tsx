import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import {
  autoRows,
  connectionRows,
  episodeLink,
  formatPlayed,
  formatRate,
  itemLink,
  itemTitle,
  liveActivityQuery,
  MODE_LABELS,
  qualityLabel,
  streamQuality,
  useStopPlay,
  type LiveActivity,
  type LivePlay,
} from '../../api/activity'
import { LiveActivity as LiveTVActivity } from '../../components/liveTV/LiveActivity'
import { Poster } from '../../components/Poster'
import { ModeChip, NetworkChip, StreamDetail } from '../../components/activity/StreamDetail'
import { formatBytes, formatDuration } from '../../lib/format'
import { uploadMbps } from '../../lib/uploadSettings'
import { useActivityAdmin, useActivityManage } from './shared'

/** ffmpeg's speed: "1.4×", "38×". */
const speed = (x: number) => `${x < 10 ? x.toFixed(1) : Math.round(x)}×`

/** A clock that ticks, so positions move between refetches. */
function useNow(ms: number): number {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const t = window.setInterval(() => setNow(Date.now()), ms)
    return () => window.clearInterval(t)
  }, [ms])
  return now
}

export function NowPlaying() {
  const admin = useActivityAdmin()
  const manage = useActivityManage()
  const live = useQuery(liveActivityQuery)
  const now = useNow(1000)
  if (live.isPending) return <p className="text-neutral-400">Loading…</p>
  if (live.isError) return <p className="text-red-400">{live.error.message}</p>
  const plays = live.data.plays
  // Positions advance from when the server answered.
  const since = Math.max(0, now - live.dataUpdatedAt)
  return (
    <div className="space-y-4">
      <LiveTVActivity canStop={manage} />
      <Summary data={live.data} admin={admin} />
      {admin && <Capacity data={live.data} />}
      {plays.length === 0 ? (
        <div className="rounded-lg border border-dashed border-line-2 px-6 py-12 text-center text-neutral-400">
          No library videos are playing right now.{' '}
          <Link to="/activity/history" className="text-neutral-200 underline">
            See what was played
          </Link>
          .
        </div>
      ) : (
        <div className="grid gap-4 xl:grid-cols-2">
          {plays.map((p) => (
            <PlayCard key={p.id} play={p} admin={admin} manage={manage} since={since} now={now} />
          ))}
        </div>
      )}
    </div>
  )
}

function Capacity({ data }: { data: LiveActivity }) {
  const upload = data.upload
  const cache = data.transcoder.cache
  if (!upload && !cache) return null
  return <section aria-label="Streaming capacity" className="rounded-lg border border-line bg-surface p-4">
    <h2 className="text-sm font-semibold">Streaming capacity</h2>
    <dl className="mt-3 grid grid-cols-2 gap-4 text-sm sm:grid-cols-4">
      {upload && <>
        <div><dt className="text-xs uppercase tracking-wider text-neutral-500">Remote ceiling</dt><dd className="mt-1 tabular-nums">{upload.configuredBps ? `${uploadMbps(upload.configuredBps)} Mbps` : 'Unlimited'} <span className="text-neutral-400">· {upload.enforcing ? 'enforced' : 'observe only'}</span></dd></div>
        <div><dt className="text-xs uppercase tracking-wider text-neutral-500">Reserved</dt><dd className="mt-1 tabular-nums">{uploadMbps(upload.reservedBps)} Mbps <span className="text-neutral-400">· {upload.activeViews} active remote requests</span></dd></div>
        <div><dt className="text-xs uppercase tracking-wider text-neutral-500">Delivered recently</dt><dd className="mt-1 tabular-nums">{uploadMbps(upload.deliveredBps)} Mbps</dd></div>
      </>}
      {cache && <div><dt className="text-xs uppercase tracking-wider text-neutral-500">Temporary cache</dt><dd className="mt-1 tabular-nums">{formatBytes(cache.temporaryBytes)} of {formatBytes(cache.maxCacheBytes)}</dd></div>}
    </dl>
    {upload && <p className="mt-3 text-xs text-neutral-400">The ceiling is configured, not a measurement of internet speed. Delivered rate is a recent average; reserved rate is planned capacity.</p>}
    {upload && upload.enforcing && upload.configuredBps > 0 && upload.reservedBps >= upload.configuredBps && <p role="status" className="mt-2 text-sm text-warn">The configured upload budget is fully reserved. A new stream may need a lower quality or more capacity.</p>}
    {cache?.freeBytes !== undefined && cache.freeBytes < cache.minFreeBytes && <p role="status" className="mt-2 text-sm text-warn">Temporary storage is below its free-space floor. New streams may be refused until space is available.</p>}
    {cache && !cache.scanComplete && <p role="status" className="mt-2 text-sm text-warn">Temporary storage could not be measured completely. Check the server logs and cache volume.</p>}
    {data.transcoder.max > 0 && data.transcoder.sessions >= data.transcoder.max && <p role="status" className="mt-2 text-sm text-warn">All transcoder slots are in use. A new conversion may need to wait or use direct play.</p>}
  </section>
}

function Summary({ data, admin }: { data: LiveActivity; admin: boolean }) {
  const s = data.summary
  const modes = (['direct', 'remux', 'audio', 'transcode'] as const).filter((m) => s[m] > 0).map((m) => `${s[m]} ${MODE_LABELS[m].toLowerCase()}`)
  const bw = s.bandwidth
  const item = (label: string, value: React.ReactNode) => (
    <div className="min-w-0">
      <dt className="text-xs uppercase tracking-wider text-neutral-500">{label}</dt>
      <dd className="mt-0.5 truncate text-sm text-neutral-100">{value}</dd>
    </div>
  )
  return (
    <dl className="grid grid-cols-2 gap-4 rounded-lg border border-line bg-surface p-4 sm:grid-cols-4">
      {item('Streams', s.streams === 0 ? 'None' : `${s.streams}${modes.length ? ` · ${modes.join(', ')}` : ''}`)}
      {item('Home network', formatRate(bw.local))}
      {item('Away', `${formatRate(bw.tailnet + bw.remote)}${bw.tailnet > 0 && bw.remote > 0 ? ` (${formatRate(bw.tailnet)} tailnet)` : bw.tailnet > 0 ? ' tailnet' : ''}`)}
      {admin && data.transcoder.max > 0
        ? item('Transcoder', transcoderLine(data.transcoder))
        : item('Transcoding', s.audio + s.transcode > 0 ? `${s.audio + s.transcode} stream${s.audio + s.transcode === 1 ? '' : 's'}` : 'None')}
    </dl>
  )
}

/** "2 of 8 sessions · 1 download · VideoToolbox": downloads' transcodes explain a full server. */
function transcoderLine(t: LiveActivity['transcoder']): string {
  const downloads = t.downloads ?? 0
  return `${t.sessions} of ${t.max} sessions${downloads > 0 ? ` · ${downloads} download${downloads === 1 ? '' : 's'}` : ''} · ${t.encoder}`
}

const STATE_LABEL: Record<string, string> = { playing: 'Playing', paused: 'Paused', buffering: 'Buffering' }

function PlayCard({ play: p, admin, manage, since, now }: { play: LivePlay; admin: boolean; manage: boolean; since: number; now: number }) {
  const { title, subtitle } = itemTitle(p.item)
  const link = itemLink(p.item)
  const episode = episodeLink(p.item)
  const dur = p.durationMs
  const pos = Math.min(dur || Infinity, p.positionMs + (p.state === 'playing' ? since : 0))
  const pct = (x: number) => (dur ? `${Math.min(100, (x / dur) * 100)}%` : '0%')
  const buffered = pos + p.bufferedMs
  const transcoded = p.transcode?.positionMs ?? 0
  const endsAt = p.state === 'playing' && dur ? new Date(now + (dur - pos)).toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' }) : ''
  const art = p.item.posterPath
  return (
    <article className="overflow-hidden rounded-lg border border-line bg-surface">
      <div className="flex gap-4 p-4">
        <div className="w-16 shrink-0 sm:w-20">
          <Poster path={art} size="w185" alt={title} />
        </div>
        <div className="min-w-0 flex-1 space-y-2">
          <div className="flex items-start justify-between gap-3">
            <div className="min-w-0">
              <h3 className="truncate font-semibold">
                {link ? (
                  <Link {...link} className="hover:underline">
                    {title}
                  </Link>
                ) : (
                  title
                )}
              </h3>
              <p className="truncate text-sm text-neutral-400">
                {episode ? (
                  <Link {...episode} className="hover:text-neutral-100 hover:underline">
                    {subtitle}
                  </Link>
                ) : (
                  subtitle
                )}
              </p>
            </div>
            <span className={`shrink-0 text-xs font-medium ${p.state === 'playing' ? 'text-accent' : 'text-neutral-400'}`}>
              {p.state === 'playing' ? '▶ ' : p.state === 'paused' ? '❚❚ ' : '… '}
              {STATE_LABEL[p.state]}
            </span>
          </div>
          <p className="truncate text-sm text-neutral-300">
            {admin ? (
              <Link to="/activity/users/$userId" params={{ userId: String(p.user.id) }} className="font-medium hover:underline">
                {p.user.name}
              </Link>
            ) : (
              <span className="font-medium">{p.user.name}</span>
            )}
            <span className="text-neutral-500"> on </span>
            {p.device.name}
          </p>
          <div className="flex flex-wrap items-center gap-2 text-xs text-neutral-400">
            <ModeChip mode={p.stream.mode} reasons={p.stream.reasons} />
            <NetworkChip network={p.network} />
            {p.ip && <span className="font-mono">{p.ip}</span>}
            <span>{formatRate(p.bandwidth)}</span>
            {p.auto?.on && <span>Auto · {qualityLabel(p.auto.step).split(' · ')[0]}</span>}
          </div>
          <div
            className="relative h-1.5 w-full overflow-hidden rounded bg-white/10"
            role="progressbar"
            aria-label="Position"
            aria-valuemin={0}
            aria-valuemax={Math.round(dur / 1000)}
            aria-valuenow={Math.round(pos / 1000)}
          >
            {transcoded > pos && <div className="absolute inset-y-0 left-0 bg-white/15" style={{ width: pct(transcoded) }} title="Transcoded" />}
            <div className="absolute inset-y-0 left-0 bg-white/30" style={{ width: pct(buffered) }} />
            <div className="absolute inset-y-0 left-0 bg-accent" style={{ width: pct(pos) }} />
          </div>
          <div className="flex justify-between gap-2 text-xs tabular-nums text-neutral-400">
            <span>
              {formatDuration(pos) || '0:00'} / {formatDuration(dur)}
            </span>
            <span>{endsAt ? `ends ${endsAt}` : `played ${formatPlayed(p.playedMs)}`}</span>
          </div>
        </div>
      </div>
      <details className="group border-t border-line">
        <summary className="flex cursor-pointer list-none items-center justify-between gap-3 px-4 py-2.5 text-sm text-neutral-300 hover:text-neutral-100">
          <span>
            Stream details
            {p.transcode && p.transcode.speed > 0 && <span className="ml-2 text-neutral-500">ffmpeg at {speed(p.transcode.speed)}</span>}
          </span>
          <span className="text-neutral-500 transition-transform group-open:rotate-90">›</span>
        </summary>
        <div className="px-4 pb-4">
          <StreamDetail
            source={p.stream.source}
            stream={p.stream.stream}
            reasons={p.stream.reasons}
            extra={[
              ['Quality', streamQuality(p.stream.quality, p.auto)],
              ...autoRows(p.auto),
              ...connectionRows(p),
              ...(p.serverPaced ? [['Delivery', 'Server paced by the configured remote upload ceiling; this rate does not show spare internet capacity'] as [string, string]] : []),
              ...(p.stream.encoder ? [['Encoder', p.stream.encoder] as [string, string]] : []),
              ...(p.transcode
                ? [['Transcoder', `${p.transcode.speed > 0 ? `${speed(p.transcode.speed)} real time` : 'starting'} · ${transcoded > pos ? `${formatDuration(transcoded - pos)} ahead` : 'catching up'}`] as [string, string]]
                : []),
              ['Sent', `${formatBytes(p.bytesSent)} · ${formatRate(p.bandwidth)} now`],
              ['Started', new Date(p.startedAt).toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' })],
            ]}
          />
        </div>
      </details>
      {manage && <StopControl playId={p.id} who={p.user.name} />}
    </article>
  )
}

function StopControl({ playId, who }: { playId: string; who: string }) {
  const [open, setOpen] = useState(false)
  const [message, setMessage] = useState('')
  const stop = useStopPlay()
  if (!open)
    return (
      <div className="border-t border-line px-4 py-2.5">
        <button type="button" onClick={() => setOpen(true)} className="text-sm text-neutral-400 hover:text-red-400">
          Stop this play…
        </button>
      </div>
    )
  return (
    <form
      className="flex flex-wrap items-center gap-2 border-t border-line px-4 py-3"
      onSubmit={(e) => {
        e.preventDefault()
        stop.mutate({ playId, message: message.trim() }, { onSuccess: () => setOpen(false) })
      }}
    >
      <input
        value={message}
        onChange={(e) => setMessage(e.target.value)}
        maxLength={200}
        autoFocus
        placeholder={`Message for ${who} (optional)`}
        aria-label="Message for the viewer"
        className="min-h-[36px] min-w-0 flex-1 rounded-md border border-line-2 bg-ground px-3 text-sm"
      />
      <button type="submit" disabled={stop.isPending} className="min-h-[36px] rounded-md border border-red-800 bg-red-950 px-3 text-sm font-medium text-red-200 hover:bg-red-900 disabled:opacity-50">
        Stop play
      </button>
      <button type="button" onClick={() => setOpen(false)} className="min-h-[36px] px-2 text-sm text-neutral-400 hover:text-neutral-100">
        Cancel
      </button>
      {stop.isError && <span className="w-full text-sm text-red-400">{stop.error.message}</span>}
    </form>
  )
}
