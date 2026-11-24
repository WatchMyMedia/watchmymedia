import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { liveTVActivityQuery } from '../../api/queries'
import { stopLiveAsAdmin, type LiveSession } from '../../api/liveTV'
import type { components } from '../../api/schema'
import { clockWords, liveDecision, watchedWords } from '../../lib/activityWords'
import { formatBytes } from '../../lib/format'
import { episodeLine, nowAndNext, progress, timeOfDay, timeRange } from '../../lib/liveGuide'
import { recordingProgress } from '../../lib/liveRecordings'
import { Avatar, StreamFacts } from '../activity/StreamDetail'
import { cardTech } from '../activity/styles'
import { useNow } from '../useNow'

type Recording = components['schemas']['LiveTVRecording']

/** Live TV viewers on their own (the live player's tuner-busy panel); canStop (an admin on an admin network) adds Stop. */
export function LiveActivity({ canStop }: { canStop: boolean }) {
  const activity = useQuery(liveTVActivityQuery)
  if (activity.isPending || !activity.data?.items.length) return activity.isError ? <p role="alert" className="text-warn">Live TV activity: {activity.error.message}</p> : null
  return (
    <section className="grid gap-4" aria-label="Live TV viewers">
      {activity.data.items.map((session) => (
        <LiveSessionCard key={session.id} session={session} canStop={canStop} />
      ))}
    </section>
  )
}

/**
 * Someone watching a channel: the channel and what is on, who and where,
 * how far into the programme, how it is sent in words, and the stream's
 * numbers one step away.
 */
export function LiveSessionCard({ session: s, canStop }: { session: LiveSession; canStop: boolean }) {
  const queryClient = useQueryClient()
  const now = useNow(1000)
  const [stopping, setStopping] = useState(false)
  const [confirm, setConfirm] = useState(false)
  const [error, setError] = useState('')
  const on = nowAndNext(s.channel, now).now
  const decision = liveDecision(s, false)
  const tuning = s.state === 'starting' || s.state === 'tuning'
  const state = s.paused ? 'Paused' : tuning ? 'Tuning' : 'Playing'
  const pct = on ? Math.round(progress(on, now)) : 0
  const channel = [s.channel.number, s.channel.name].filter(Boolean).join(' ')
  const line = s.paused
    ? `Paused${s.pauseRemainingSeconds ? ` · the tuner frees in ${clockWords(s.pauseRemainingSeconds * 1000)}` : ''}`
    : `Watching live for ${watchedWords(Math.max(0, now - Date.parse(s.startedAt)))}`
  async function stop() {
    setStopping(true)
    setError('')
    try {
      await stopLiveAsAdmin(s.id)
      setConfirm(false)
      await queryClient.invalidateQueries({ queryKey: liveTVActivityQuery.queryKey })
    } catch (e) {
      setError(e instanceof Error ? e.message : 'The play could not be stopped.')
    } finally {
      setStopping(false)
    }
  }
  return (
    <article className="panel flex min-w-0 flex-col" aria-label={`${s.userName} is watching ${channel}${on ? `, ${on.title}` : ''}`}>
      <div className="flex gap-4 p-4">
        <span aria-hidden="true" className="grid aspect-square w-[72px] shrink-0 place-items-center self-start rounded-lg bg-surface-2 ring-1 ring-inset ring-white/[.07] sm:w-[88px]">
          <span className="text-[22px] font-bold tracking-[-.02em] sm:text-[26px]">{s.channel.number || 'TV'}</span>
        </span>
        <div className="flex min-w-0 flex-1 flex-col gap-2.5">
          <div className="flex items-start gap-2.5">
            <div className="min-w-0 flex-1">
              <h3 className="text-base font-semibold leading-snug">
                <Link to="/live-tv/watch/$channelId" params={{ channelId: s.channel.id }} className="hover:text-accent">
                  {[s.channel.name || channel, on?.title].filter(Boolean).join(' · ')}
                </Link>
              </h3>
              <p className="text-[12.5px] text-neutral-400">
                Live TV{on ? ` · until ${timeOfDay(Date.parse(on.end))}` : ''}
                {on && episodeLine(on) ? ` · ${episodeLine(on)}` : ''}
              </p>
            </div>
            <span className={`pill ${state === 'Playing' ? 'pill-gold' : ''}`}>{state}</span>
          </div>
          <p className="flex flex-wrap items-center gap-x-2 gap-y-1 text-[13.5px]">
            <span className="inline-flex items-center gap-2 font-semibold">
              <Avatar name={s.userName} />
              {s.userName}
            </span>
            <span className="text-neutral-400">· {s.clientName || 'A player'}</span>
          </p>
          <div>
            <span className="meter" role="img" aria-label={on ? `${pct}% into ${on.title}` : 'No listing for now'}>
              <i style={{ width: `${pct}%` }} />
            </span>
            <p className="mt-1.5 text-[13px] text-neutral-300">{line}</p>
          </div>
          <p className="flex flex-wrap items-center gap-x-2 gap-y-1.5 text-[13px]">
            <span className={`pill ${decision.warn ? 'pill-warn' : ''}`}>{decision.chip}</span>
            <span className="text-neutral-400">{decision.why}</span>
          </p>
        </div>
      </div>
      <details className={cardTech}>
        <summary>Stream details</summary>
        <div className="tech-body pt-1">
          <StreamFacts
            facts={[
              ['Quality', s.automatic ? `Automatic · ${s.actualQuality === 'original' ? 'Original' : s.actualQuality ? `${s.actualQuality}p` : 'starting'}` : `Up to ${s.quality}p`],
              ...(s.renditionHeight ? [['Sent', `${s.renditionHeight}p${s.renditionBitrate ? ` · ${(s.renditionBitrate / 1_000_000).toFixed(1)} Mb/s` : ''}`] as [string, string]] : []),
              ['Buffered', `${formatBytes(s.bufferBytes)}${s.bufferSeconds ? ` · ${s.bufferSeconds} s` : ''}`],
              ['Started', timeOfDay(Date.parse(s.startedAt))],
            ]}
          />
        </div>
      </details>
      {canStop && (
        <div className="flex flex-wrap items-center justify-end gap-2 border-t border-line px-3 pb-3 pt-2">
          {error && (
            <p role="alert" className="mr-auto text-sm text-warn">
              {error}
            </p>
          )}
          {confirm ? (
            <>
              <span className="mr-auto text-sm">Stop this play and free its tuner?</span>
              <button type="button" onClick={() => setConfirm(false)} className="btn btn-sm btn-ghost">
                Cancel
              </button>
              <button type="button" disabled={stopping} onClick={() => void stop()} className="btn btn-sm btn-primary">
                {stopping ? 'Stopping…' : 'Stop this play'}
              </button>
            </>
          ) : (
            <button type="button" onClick={() => setConfirm(true)} className="btn btn-sm btn-ghost text-red-400">
              Stop this play…
            </button>
          )}
        </div>
      )}
    </article>
  )
}

/** A recording under way: what, on which channel and when, how far, and Watch from the start. */
export function RecordingNowCard({ recording: r }: { recording: Recording }) {
  const now = useNow(15_000)
  const start = Date.parse(r.startedAt ?? r.start)
  const end = Date.parse(r.end)
  const pct = Math.round(recordingProgress(r.startedAt ?? r.start, r.end, now))
  const minutes = Math.max(0, Math.round((Math.min(now, end) - start) / 60_000))
  const total = Math.max(1, Math.round((end - start) / 60_000))
  const episode = [r.season != null && r.episode != null ? `S${r.season} E${r.episode}` : '', r.episodeTitle].filter(Boolean).join(' · ')
  const facts = [episode, [r.number, r.channelName].filter(Boolean).join(' '), timeRange(Date.parse(r.start), end)].filter(Boolean).join(' · ')
  return (
    <article className="panel flex max-w-[660px] flex-wrap items-center gap-4 px-4 py-3.5">
      <span aria-hidden="true" className="relative block aspect-video w-[150px] shrink-0 overflow-hidden rounded-lg bg-surface-2 ring-1 ring-inset ring-white/[.07]">
        {r.pictureUrl && <img src={r.pictureUrl} alt="" className="size-full object-cover" />}
        <span className="absolute left-2 top-2 rounded-[5px] bg-red-700 px-[7px] py-0.5 text-[11px] font-bold text-white">Recording</span>
      </span>
      <div className="flex min-w-0 flex-1 basis-60 flex-col gap-1.5">
        <h3 className="text-[15.5px] font-semibold">
          <Link to="/live-tv/recordings" className="hover:text-accent">
            {r.title}
          </Link>
        </h3>
        <p className="text-[12.5px] text-neutral-400">{facts}</p>
        <span className="meter" role="img" aria-label={`${minutes} of ${total} minutes recorded`}>
          <i style={{ width: `${pct}%` }} className="bg-red-400!" />
        </span>
        <p className="text-[13px] text-neutral-300">
          {watchedWords(minutes * 60_000)} so far · ends {timeOfDay(end)}
          {r.userName ? ` · set by ${r.userName}` : ''}
        </p>
      </div>
      <Link to="/live-tv/recordings/$recordingId/watch" params={{ recordingId: r.id }} search={{ title: r.title, back: '/activity' }} className="btn btn-sm">
        Watch from the start
      </Link>
    </article>
  )
}
