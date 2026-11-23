import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { autoRows, connectionRows, episodeLink, itemLink, itemTitle, liveActivityQuery, streamQuality, useStopPlay, type LiveActivity, type LivePlay } from '../../api/activity'
import { liveTVActivityQuery, liveTVCapabilitiesQuery } from '../../api/queries'
import { LiveSessionCard, RecordingNowCard } from '../../components/liveTV/LiveActivity'
import { Poster } from '../../components/Poster'
import { Avatar, StreamFacts, StreamReasons, StreamTable } from '../../components/activity/StreamDetail'
import { cardTech } from '../../components/activity/styles'
import { WarnIcon } from '../../components/icons'
import { useNow } from '../../components/useNow'
import { bandwidthLine, clockWords, kindsLine, liveKind, NETWORK_WORDS, nowSentence, playDecision, playKind, rateWords } from '../../lib/activityWords'
import { endsAt } from '../../lib/endTime'
import { formatBytes } from '../../lib/format'
import { uploadMbps } from '../../lib/uploadSettings'
import { useActivityAdmin, useActivityManage } from './shared'

/** ffmpeg's speed: "1.4×", "38×". */
const speed = (x: number) => `${x < 10 ? x.toFixed(1) : Math.round(x)}×`

/**
 * Now playing: a sentence first (who is watching, whether the server keeps
 * up), then each play and each channel being watched with its decision in
 * words and the stream's numbers closed, then what is recording.
 */
export function NowPlaying() {
  const admin = useActivityAdmin()
  const manage = useActivityManage()
  const live = useQuery(liveActivityQuery)
  const caps = useQuery(liveTVCapabilitiesQuery)
  const liveTV = useQuery({ ...liveTVActivityQuery, enabled: !!caps.data?.enabled && !!caps.data.allowed })
  const now = useNow(1000)
  if (live.isPending) return <p className="mt-6 text-neutral-400">Loading…</p>
  if (live.isError) return <p className="mt-6 text-red-400">{live.error.message}</p>
  const plays = live.data.plays
  const sessions = liveTV.data?.items ?? []
  const recordings = (liveTV.data?.recordings ?? []).filter((r) => r.state === 'recording')
  // Positions advance from when the server answered.
  const since = Math.max(0, now - live.dataUpdatedAt)
  const sentence = nowSentence({ plays, live: sessions, transcoder: admin ? live.data.transcoder : undefined })
  const kinds = kindsLine([...plays.map((p) => playKind(p.stream.mode)), ...sessions.map((s) => liveKind(s.mode))])
  const bandwidth = bandwidthLine(live.data.summary.bandwidth)
  const warnings = admin ? capacityWarnings(live.data) : []
  const count = plays.length + sessions.length
  return (
    <div>
      <section aria-label="Right now" className="panel mt-[22px] px-5 py-[18px]">
        <p className={`text-[19px] font-semibold tracking-[-.01em] ${sentence.warn ? 'text-warn' : ''}`}>{sentence.headline}</p>
        {(kinds || bandwidth || warnings.length > 0) && (
          <ul className="mt-2.5 flex flex-wrap gap-x-8 gap-y-2 text-[13.5px] text-neutral-300">
            {kinds && <li>{kinds}</li>}
            {bandwidth && <li>{bandwidth}</li>}
            {warnings.map((w) => (
              <li key={w} role="status" className="flex items-center gap-1.5 text-warn">
                <WarnIcon size={15} />
                {w}
              </li>
            ))}
          </ul>
        )}
      </section>
      {admin && <ServerLoad data={live.data} />}

      <section aria-labelledby="h-plays" className="mt-8">
        <div className="rowh">
          <h2 id="h-plays">Watching now</h2>
          {count > 0 && <span className="note">{[plays.length ? `${plays.length} from the library` : '', sessions.length ? `${sessions.length} on Live TV` : ''].filter(Boolean).join(' · ')}</span>}
        </div>
        {count === 0 ? (
          <div className="mt-3.5 rounded-xl border border-dashed border-line-2 px-6 py-12 text-center text-neutral-400">
            Plays show here as they start.{' '}
            <Link to="/activity/history" className="text-neutral-100 underline">
              See what was played
            </Link>
            .
          </div>
        ) : (
          <div className="mt-3.5 grid grid-cols-[repeat(auto-fit,minmax(min(400px,100%),1fr))] items-start gap-4">
            {plays.map((p) => (
              <PlayCard key={p.id} play={p} admin={admin} manage={manage} since={since} now={now} />
            ))}
            {sessions.map((s) => (
              <LiveSessionCard key={s.id} session={s} canStop={manage} />
            ))}
          </div>
        )}
        {sessions.length > 0 && (
          <p className="mt-3.5 text-[13px] text-neutral-400">Someone watching Live TV holds a tuner until they stop, close the tab, or stay paused too long. Live TV isn’t kept in History or Stats.</p>
        )}
      </section>

      {recordings.length > 0 && (
        <section aria-labelledby="h-rec" className="mt-8">
          <div className="rowh">
            <h2 id="h-rec">Recording now</h2>
            <span className="note">{recordings.length === 1 ? '1 recording' : `${recordings.length} recordings`}</span>
            <Link to="/live-tv/recordings" className="more">
              Recordings ›
            </Link>
          </div>
          <div className="mt-3.5 flex flex-col gap-3">
            {recordings.map((r) => (
              <RecordingNowCard key={r.id} recording={r} />
            ))}
          </div>
        </section>
      )}
    </div>
  )
}

/** What the admin needs to act on: limits reached and storage running low. */
function capacityWarnings(data: LiveActivity): string[] {
  const out: string[] = []
  const upload = data.upload
  const cache = data.transcoder.cache
  if (upload && upload.enforcing && upload.configuredBps > 0 && upload.reservedBps >= upload.configuredBps) out.push('The away limit is fully used: a new stream away from home may be sent smaller')
  if (cache?.freeBytes !== undefined && cache.freeBytes < cache.minFreeBytes) out.push('Space for converted files is low: new streams may be refused')
  if (cache && !cache.scanComplete) out.push('Space for converted files couldn’t be measured: see the server’s log')
  return out
}

/** The server's load and limits, one step away: the converter, the away limit, what was sent, converted files kept. */
function ServerLoad({ data }: { data: LiveActivity }) {
  const t = data.transcoder
  const upload = data.upload
  const cache = t.cache
  const downloads = t.downloads ?? 0
  const facts: [string, string][] = []
  if (t.max > 0) facts.push(['Converter', `${t.sessions} of ${t.max} sessions${downloads > 0 ? ` · ${downloads} for downloads` : ''}${t.encoder ? ` · ${t.encoder}` : ''}`])
  if (upload)
    facts.push([
      'Away limit',
      upload.configuredBps
        ? `${uploadMbps(upload.configuredBps)} Mb/s · ${uploadMbps(upload.reservedBps)} held for ${upload.activeViews} ${upload.activeViews === 1 ? 'stream' : 'streams'}${upload.enforcing ? '' : ' · watching only, not limiting'}`
        : 'None set',
    ])
  if (upload) facts.push(['Sent lately', `${uploadMbps(upload.deliveredBps)} Mb/s on average`])
  if (cache) facts.push(['Converted files kept', `${formatBytes(cache.temporaryBytes)} of ${formatBytes(cache.maxCacheBytes)}`])
  if (!facts.length) return null
  return (
    <details className="tech mt-3">
      <summary>
        Server load and limits<span className="hint max-sm:hidden">Converter, bandwidth and space</span>
      </summary>
      <div className="tech-body">
        <dl className="grid grid-cols-[repeat(auto-fit,minmax(min(260px,100%),1fr))] gap-x-7 gap-y-3.5 text-[13.5px]">
          {facts.map(([k, v]) => (
            <div key={k}>
              <dt className="text-neutral-400">{k}</dt>
              <dd className="m-0">{v}</dd>
            </div>
          ))}
        </dl>
        {upload && <p className="mt-3 text-xs text-neutral-400">The away limit is what you set, not a measure of your internet speed. What was sent is a recent average; what is held is planned for the streams running.</p>}
      </div>
    </details>
  )
}

const STATE_LABEL: Record<string, string> = { playing: 'Playing', paused: 'Paused', buffering: 'Waiting' }

function PlayCard({ play: p, admin, manage, since, now }: { play: LivePlay; admin: boolean; manage: boolean; since: number; now: number }) {
  const { title, subtitle } = itemTitle(p.item)
  const link = itemLink(p.item)
  const episode = episodeLink(p.item)
  const dur = p.durationMs
  const pos = Math.min(dur || Infinity, p.positionMs + (p.state === 'playing' ? since : 0))
  const pct = dur ? Math.min(100, Math.round((pos / dur) * 100)) : 0
  const transcoded = p.transcode?.positionMs ?? 0
  const decision = playDecision({ mode: p.stream.mode, reasons: p.stream.reasons, step: p.stream.step, quality: p.stream.quality, network: p.network, device: p.device.name })
  const where = [p.device.name, NETWORK_WORDS[p.network] ?? ''].filter(Boolean).join(' · ')
  const line = [dur ? `${clockWords(pos)} of ${clockWords(dur)}` : clockWords(pos), p.state === 'playing' && dur ? `ends ${endsAt(dur - pos, now)}` : ''].filter(Boolean).join(' · ')
  return (
    <article className="panel flex min-w-0 flex-col" aria-label={`${p.user.name} is watching ${title}${subtitle ? `, ${subtitle}` : ''}`}>
      <div className="flex gap-4 p-4">
        <div className="w-[72px] shrink-0 self-start sm:w-[88px]">
          {link ? (
            <Link {...link} tabIndex={-1} aria-hidden="true">
              <Poster path={p.item.posterPath} size="w185" alt="" />
            </Link>
          ) : (
            <Poster path={p.item.posterPath} size="w185" alt="" />
          )}
        </div>
        <div className="flex min-w-0 flex-1 flex-col gap-2.5">
          <div className="flex items-start gap-2.5">
            <div className="min-w-0 flex-1">
              <h3 className="text-base font-semibold leading-snug">
                {link ? (
                  <Link {...link} className="hover:text-accent">
                    {title}
                  </Link>
                ) : (
                  title
                )}
              </h3>
              {subtitle && (
                <p className="text-[12.5px] text-neutral-400">
                  {episode ? (
                    <Link {...episode} className="hover:text-accent">
                      {subtitle}
                    </Link>
                  ) : (
                    subtitle
                  )}
                </p>
              )}
            </div>
            <span className={`pill ${p.state === 'playing' ? 'pill-gold' : ''}`}>{STATE_LABEL[p.state] ?? p.state}</span>
          </div>
          <p className="flex flex-wrap items-center gap-x-2 gap-y-1 text-[13.5px]">
            {admin ? (
              <Link to="/activity/users/$userId" params={{ userId: String(p.user.id) }} className="inline-flex items-center gap-2 font-semibold hover:text-accent">
                <Avatar name={p.user.name} />
                {p.user.name}
              </Link>
            ) : (
              <span className="inline-flex items-center gap-2 font-semibold">
                <Avatar name={p.user.name} />
                {p.user.name}
              </span>
            )}
            {where && <span className="text-neutral-400">· {where}</span>}
          </p>
          <div>
            <span className="meter" role="img" aria-label={`${pct}% watched`}>
              <i style={{ width: `${pct}%` }} />
            </span>
            <p className="mt-1.5 text-[13px] tabular-nums text-neutral-300">{line}</p>
          </div>
          <p className="flex flex-wrap items-center gap-x-2 gap-y-1.5 text-[13px]">
            <span className={`pill ${decision.warn ? 'pill-warn' : ''}`}>{decision.chip}</span>
            <span className="text-neutral-400">{decision.why}</span>
          </p>
        </div>
      </div>
      <details className={cardTech}>
        <summary>
          Stream details
          {p.transcode && p.transcode.speed > 0 && <span className="hint max-sm:hidden">converting at {speed(p.transcode.speed)}</span>}
        </summary>
        <div className="tech-body pt-3">
          <StreamTable source={p.stream.source} stream={p.stream.stream} />
          <StreamFacts
            facts={[
              ['Bitrate', `${rateWords(p.bandwidth)} now`],
              ['Quality', streamQuality(p.stream.quality, p.auto)],
              ...autoRows(p.auto),
              ...connectionRows(p),
              ...(p.serverPaced ? [['Delivery', 'Paced by the away limit; this rate doesn’t show spare internet speed'] as [string, string]] : []),
              ['Encoder', p.stream.encoder || 'Not needed'],
              ...(p.transcode
                ? [['Converter', `${p.transcode.speed > 0 ? `${speed(p.transcode.speed)} real time` : 'starting'} · ${transcoded > pos ? `${clockWords(transcoded - pos)} ahead` : 'catching up'}`] as [string, string]]
                : []),
              ['Sent', formatBytes(p.bytesSent)],
              ...(p.ip ? [['Address', p.ip, 'font-mono text-xs'] as [string, string, string]] : []),
              ['Started', new Date(p.startedAt).toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' })],
            ]}
          />
          <StreamReasons reasons={p.stream.reasons} />
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
      <div className="flex justify-end border-t border-line px-3 pb-3 pt-2">
        <button type="button" onClick={() => setOpen(true)} className="btn btn-sm btn-ghost text-red-400">
          Stop this play…
        </button>
      </div>
    )
  return (
    <form
      className="flex flex-wrap items-center gap-2 border-t border-line px-3 py-3"
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
        aria-label={`Message for ${who}`}
        className="min-h-9 min-w-0 flex-1 basis-48 rounded-lg border border-line-2 bg-ground px-3 text-sm"
      />
      <button type="button" onClick={() => setOpen(false)} className="btn btn-sm btn-ghost">
        Cancel
      </button>
      <button type="submit" disabled={stop.isPending} className="btn btn-sm btn-primary">
        Stop the play
      </button>
      {stop.isError && <span className="w-full text-sm text-red-400">{stop.error.message}</span>}
    </form>
  )
}
