import { useEffect, useRef, useState, type RefObject } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { liveTVCapabilitiesQuery, liveTVChannelsQuery, serverQuery } from '../api/queries'
import type { LiveChannel } from '../api/liveTV'
import {
  castChannel, castSnapshot, driveCastChannel, goLiveCast, noteCastInput, playOrPause, send, stepCastChannel, stopCastMedia, stopCasting,
  switchCastChannel, useCast, whenCast,
} from '../cast/sender'
import { CastButton, CastIcon, PlayPauseIcon } from '../components/Cast'
import { QualityMenu } from '../components/QualityMenu'
import { LiveBadge } from '../components/liveTV/LiveBadge'
import { ChannelBanner, MiniGuideRow } from '../components/liveTV/LiveGuide'
import type { LiveTVController } from '../lib/liveTVController'
import { nowAndNext, showListings, timeOfDay } from '../lib/liveGuide'
import { serverRecords } from '../lib/liveRecordings'
import { liveQuality, nextLiveChannel, saveLiveQuality, savedLiveQuality } from '../lib/liveTV'
import {
  AT_LIVE_SECONDS, LIVE_CAST_QUALITIES, behindLive, behindText, liveRemoteAction, livePosition, liveRemoteView, remoteCard, remoteChannel, type RemoteCard,
} from '../lib/liveCast'

const control = 'inline-flex min-h-11 items-center justify-center gap-2 whitespace-nowrap rounded-md border border-white/20 bg-black/60 px-3 text-sm text-white hover:bg-white/10 disabled:opacity-40'
const square = 'grid min-h-11 min-w-11 place-items-center rounded-md bg-white/10 px-3 text-white hover:bg-white/20 disabled:opacity-40'

type Props = { channelId: string; controller: LiveTVController; onLocal: () => void }

let releaseTimer: number | undefined

/**
 * The live player as the TV's remote while this browser casts a channel
 * (PLAN-LIVE-TV.md §4.1; the LiveCast and LiveCastPhone boards). The TV
 * plays the channel on this person's sign-in; this page shows its banner
 * and sends Ch− / Ch+, the channel list, Pause, Go live and Quality.
 * Opening it for a channel the TV isn't on casts that channel, after this
 * browser's own session gives its tuner back; when the TV changes channel
 * the page follows.
 */
export function LiveRemote({ channelId, controller, onLocal }: Props) {
  const cast = useCast()
  const navigate = useNavigate()
  const caps = useQuery(liveTVCapabilitiesQuery)
  const server = useQuery(serverQuery)
  const listings = showListings(server.data?.apiLevel, caps.data)
  const channels = useQuery({ ...liveTVChannelsQuery, enabled: !!caps.data?.allowed && !!caps.data?.enabled })
  const r = cast.receiver
  const connected = cast.status === 'connected'
  const device = cast.deviceName ?? cast.lastDeviceName ?? 'your TV'
  const [now, setNow] = useState(() => Date.now())
  const [list, setList] = useState(false)
  const [leaving, setLeaving] = useState(false)
  const listButton = useRef<HTMLButtonElement>(null)
  const listPanel = useRef<HTMLElement>(null)
  useEffect(() => { const timer = window.setInterval(() => setNow(Date.now()), 15_000); return () => window.clearInterval(timer) }, [])

  const items = channels.data?.items
  const castOf = (id: string) => {
    const c = items?.find((item) => item.id === id)
    const on = c ? nowAndNext(c, Date.now()).now : undefined
    return castChannel({ channelId: id, quality: savedLiveQuality(), title: c ? `${c.number} ${c.name}` : undefined, subtitle: on?.title, stopLocal: () => controller.stop() })
  }

  // Cast the channel in the address, adopt it when the TV is on it already,
  // or follow the TV to the channel it moved to. Read from the store, not
  // this render, and ask once a channel: a refused ask is the card's Try
  // again, never a loop.
  const asked = useRef<string | undefined>(undefined)
  useEffect(() => {
    if (!connected) return
    const s = castSnapshot()
    const action = liveRemoteAction({ route: channelId, receiver: s.receiver, pending: s.castingChannelId, target: s.liveTarget })
    if (action.kind === 'adopt') driveCastChannel(channelId)
    else if (action.kind === 'cast' && asked.current !== channelId) {
      asked.current = channelId
      void castOf(channelId).catch(() => undefined)
    } else if (action.kind === 'follow') {
      // The new address adopts the channel once the page is on it.
      void navigate({ to: '/live-tv/watch/$channelId', params: { channelId: action.channelId }, replace: true })
    }
    // castOf reads the latest channel list; a new list alone needs no action.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [connected, channelId, cast.receiver, cast.castingChannelId, cast.liveTarget, navigate])
  // Another visit to this page is a new ask. The release waits a moment, so
  // a remount (StrictMode, or the address changing) keeps what it drives.
  useEffect(() => {
    window.clearTimeout(releaseTimer)
    return () => { releaseTimer = window.setTimeout(() => driveCastChannel(undefined), 0) }
  }, [])

  const view = liveRemoteView({ connected, route: channelId, receiver: r, pending: cast.castingChannelId, target: cast.liveTarget, error: cast.error })
  const channel = remoteChannel(view.channelId, r?.live, items)
  const { now: onNow } = nowAndNext(channel, now)
  const card = remoteCard(view, { number: channel.number, name: channel.name, device, stopMessage: r?.stopMessage, recording: serverRecords(server.data?.apiLevel, caps.data) })
  const remote = view.kind === 'playing' || view.kind === 'tuning'
  const paused = view.kind === 'playing' && view.paused
  const behind = view.kind === 'playing' ? behindLive(cast.liveRange, cast.currentTime) : 0
  const atLive = view.kind === 'playing' && !paused && behind <= AT_LIVE_SECONDS
  const prev = items ? nextLiveChannel(items, view.channelId, -1) : undefined
  const next = items ? nextLiveChannel(items, view.channelId, 1) : undefined
  const automatic = (server.data?.apiLevel ?? 0) >= 25 && !!caps.data?.automatic
  const qualityOptions = LIVE_CAST_QUALITIES.filter((q) => q.id !== 'auto' || automatic)
  const quality = qualityOptions.find((q) => q.id === liveQuality(r?.live?.quality ?? savedLiveQuality())) ?? qualityOptions[0]

  const step = (by: 1 | -1) => {
    if (!remote) return
    setList(false)
    stepCastChannel(by, (by === 1 ? next : prev)?.id)
  }
  const pick = (item: LiveChannel) => {
    setList(false)
    listButton.current?.focus()
    if (item.id !== view.channelId) switchCastChannel(item.id)
  }
  async function continueHere() {
    setLeaving(true)
    // The TV gives its tuner back before this browser asks for one.
    stopCastMedia()
    await whenCast((s) => s.status !== 'connected' || !s.receiver?.live || s.receiver.phase === 'idle', 3000)
    stopCasting()
    onLocal()
  }

  useEffect(() => { if (list) listPanel.current?.querySelector<HTMLButtonElement>('[aria-current="true"]')?.focus() }, [list])
  useEffect(() => {
    const key = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && list) { setList(false); listButton.current?.focus(); return }
      const target = event.target instanceof HTMLElement ? event.target : null
      if (target && (target.matches('input, select, textarea') || target.isContentEditable || target.closest('[data-player-keys="off"]'))) return
      noteCastInput()
      switch (event.key) {
        case 'ArrowUp': case 'PageUp': event.preventDefault(); step(1); break
        case 'ArrowDown': case 'PageDown': event.preventDefault(); step(-1); break
        case 'g': case 'G': if (remote) { event.preventDefault(); setList((open) => !open) }; break
        case ' ': if (view.kind === 'playing' && !target?.closest('button, a')) { event.preventDefault(); playOrPause() }; break
      }
    }
    window.addEventListener('keydown', key)
    return () => window.removeEventListener('keydown', key)
  })

  return <div className="fixed inset-0 z-50 flex flex-col overflow-hidden bg-ground text-white" onPointerDown={noteCastInput}>
    <header className="relative z-30 flex shrink-0 items-center gap-2.5 bg-gradient-to-b from-black/80 to-transparent p-3 sm:gap-4 sm:p-4">
      <Link to="/live-tv" aria-label="Back to the guide" className={`${control} min-w-11`}><span aria-hidden="true">‹</span><span className="hidden sm:inline">Guide</span></Link>
      <div className="flex min-w-0 flex-1 items-center gap-2 text-[15px] sm:gap-2.5 sm:text-sm">
        <span className="shrink-0 font-bold tabular-nums">{channel.number}</span>
        <span className="truncate">{channel.name}</span>
        {onNow && <span className="hidden min-w-0 truncate text-white/70 sm:inline">· {onNow.title}</span>}
        {view.kind === 'playing' && <span className="shrink-0"><LiveBadge behind={!atLive} /></span>}
      </div>
      <CastButton variant="player" />
    </header>

    {view.kind === 'playing' && paused && !list && <p role="status" className="absolute inset-x-4 top-20 z-20 mx-auto w-fit max-w-lg rounded-xl border border-white/20 bg-ground/90 px-3.5 py-2.5 text-center text-sm leading-snug shadow-xl sm:top-[76px] sm:rounded-full">
      Paused on {device}.{view.pauseLeftMinutes ? ` The tuner goes back in ${view.pauseLeftMinutes} min.` : ''}
    </p>}

    {card ? <div className="grid min-h-0 flex-1 place-items-center overflow-y-auto p-4 sm:p-6"><Card card={card} number={channel.number} busy={leaving} onRetry={() => void castOf(view.channelId).catch(() => undefined)} onLocal={onLocal} /></div>
      : <main className="flex min-h-0 flex-1 flex-col justify-center gap-4 px-3 sm:justify-end sm:px-4">
        <div role="status" aria-live="polite" className="flex flex-col items-center justify-center gap-2.5 py-6 text-center sm:flex-1 sm:gap-3">
          <span aria-hidden="true" className="grid h-14 w-14 place-items-center rounded-full bg-surface text-accent sm:h-16 sm:w-16"><CastIcon connected size={28} /></span>
          {view.kind === 'tuning'
            ? <p className="flex items-center gap-2.5 text-[15px] text-neutral-300 sm:text-base"><span aria-hidden="true" className="h-5 w-5 animate-spin rounded-full border-[3px] border-white/20 border-t-accent motion-reduce:animate-none" />Tuning {channel.number || 'the channel'} on {device}…</p>
            : <><p className="text-sm text-white/60">Playing on {device}</p><p className="hidden text-2xl font-semibold sm:block">{channel.number} {channel.name}</p></>}
        </div>
        {list ? <ChannelList panel={listPanel} channels={items ?? []} current={view.channelId} listings={listings} now={now} device={device} onPick={pick} onClose={() => { setList(false); listButton.current?.focus() }} />
          : listings ? <ChannelBanner channel={channel} now={now}>{null}</ChannelBanner>
            : <div className="mb-4 flex flex-wrap items-center gap-x-4 gap-y-2 rounded-xl border border-white/15 bg-ground/85 px-4 py-3"><strong className="text-2xl tabular-nums text-accent">{channel.number}</strong><div className="min-w-0 flex-1"><p className="truncate font-semibold">{channel.name}</p><p className="mt-0.5 text-xs text-neutral-400">Live broadcast · No programme listings</p></div></div>}
      </main>}

    {remote && <div className="relative z-20 shrink-0 bg-gradient-to-t from-black to-black/60 px-3 pt-2 pb-6 sm:p-4">
      <div role="img" aria-label={atLive || view.kind !== 'playing' ? 'At live' : `${behindText(behind)}, within the last minute kept`} className="relative h-1.5 rounded-full bg-white/20">
        <div className="absolute inset-y-0 left-0 rounded-full bg-accent" style={{ width: `${view.kind === 'playing' ? livePosition(atLive ? 0 : Math.max(behind, 1), caps.data?.bufferSeconds ?? 60) : 100}%` }} />
      </div>
      <div className="mt-3 flex flex-wrap items-center gap-2 sm:gap-3">
        <button type="button" className={square} disabled={view.kind !== 'playing'} onClick={playOrPause} aria-label={paused ? `Play on ${device}` : `Pause on ${device}`}><PlayPauseIcon paused={paused} size={18} /></button>
        {view.kind === 'playing' && (atLive
          ? <span className="inline-flex items-center gap-2 text-sm font-semibold tracking-wide"><span aria-hidden="true" className="h-1.5 w-1.5 rounded-full bg-red-500" />LIVE</span>
          : <span className="flex items-center gap-2 sm:flex-row-reverse sm:gap-3">
            <button type="button" className={control} onClick={goLiveCast}><span aria-hidden="true" className="h-1.5 w-1.5 rounded-full bg-red-500" />Go live</button>
            <span className="text-sm tabular-nums text-white/75">{paused && behind === 0 ? 'Paused' : behindText(behind)}</span>
          </span>)}
        <div className="ml-auto flex items-center gap-1.5 sm:ml-3">
          <button type="button" className={square} onClick={() => step(-1)} aria-label={prev ? `Previous channel, ${prev.number} ${prev.name}` : 'Previous channel'}><Chevron down /></button>
          <span className="hidden text-sm text-white/70 sm:inline">Ch</span>
          <button type="button" className={square} onClick={() => step(1)} aria-label={next ? `Next channel, ${next.number} ${next.name}` : 'Next channel'}><Chevron /></button>
        </div>
        <div className="flex w-full items-center gap-2 sm:ml-auto sm:w-auto">
          <button ref={listButton} type="button" aria-expanded={list} aria-controls="live-cast-channels" onClick={() => setList((open) => !open)} className={list ? control.replace('border-white/20', 'border-accent') : control}><GridIcon />Channels</button>
          <QualityMenu tall value={quality} options={qualityOptions} heading="Quality on the TV" sub={(q) => (q.id === 'auto' ? `Now ${r?.autoStep === 'original' ? 'Original' : r?.autoStep ? `${r.autoStep}p` : 'adapting'}` : q.id === '1080' ? 'Live TV default' : undefined)}
            note="Automatic can change quality without retuning. Choosing another ceiling starts the channel at live."
            onPick={(q) => { saveLiveQuality(liveQuality(q.id)); void send({ type: 'quality', id: q.id }) }} />
        </div>
        <div className="flex w-full gap-2 sm:w-auto">
          <button type="button" className={`${control} flex-1 sm:flex-none`} disabled={leaving} onClick={() => void continueHere()}>Continue on this browser</button>
          <button type="button" className={control} disabled={leaving} onClick={stopCasting}>Stop casting</button>
        </div>
      </div>
    </div>}
  </div>
}

function Card({ card: c, number, busy, onRetry, onLocal }: { card: RemoteCard; number: string; busy: boolean; onRetry: () => void; onLocal: () => void }) {
  const primary = 'inline-flex min-h-11 items-center justify-center rounded-md btn-primary px-4.5 text-sm font-semibold'
  const secondary = 'inline-flex min-h-11 items-center justify-center rounded-md border border-line-2 bg-surface px-4.5 text-sm font-medium hover:bg-surface-2'
  return <section role="alert" aria-labelledby="live-cast-card" className="flex w-full max-w-lg flex-col gap-3.5 rounded-xl border border-line-2 bg-surface/95 p-5 sm:p-6">
    <div className="flex items-start gap-3.5">
      <span aria-hidden="true" className={`grid h-11 w-11 shrink-0 place-items-center rounded-full ${c.gold ? 'bg-accent/10 text-accent' : 'bg-warn/10 text-warn'}`}>
        {c.castIcon ? <CastIcon size={20} /> : <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M12 3 2 20h20L12 3z" /><path d="M12 10v4M12 17h.01" /></svg>}
      </span>
      <div className="min-w-0 flex-1 space-y-1.5">
        <h2 id="live-cast-card" className="text-xl font-semibold leading-snug">{c.title}</h2>
        <p className="text-[15px] leading-relaxed text-neutral-300">{c.body}</p>
      </div>
    </div>
    {c.message && <p className="rounded-lg border border-line-2 bg-ground px-3.5 py-2.5 text-[15px]">“{c.message}”</p>}
    <div className="mt-1 flex flex-wrap items-center gap-2">
      {c.retry && <button type="button" className={primary} onClick={onRetry}>Try again</button>}
      {c.keep && <button type="button" className={primary} onClick={() => void send({ type: 'stillWatching', keep: true })}>Keep watching</button>}
      {c.watchHere && <button type="button" className={primary} onClick={onLocal}>Watch {number || 'it'} on this browser</button>}
      {c.guideFirst && <Link to="/live-tv" className={primary}>Back to the guide</Link>}
      {c.guide && <Link to="/live-tv" className={secondary}>Back to the guide</Link>}
      {c.stop && <button type="button" className={secondary} disabled={busy} onClick={stopCasting}>Stop casting</button>}
    </div>
  </section>
}

/** The channels to change to on the TV: a panel above the controls, a sheet on a phone. */
function ChannelList({ panel, channels, current, listings, now, device, onPick, onClose }: { panel: RefObject<HTMLElement | null>; channels: LiveChannel[]; current: string; listings: boolean; now: number; device: string; onPick: (c: LiveChannel) => void; onClose: () => void }) {
  return <section ref={panel} id="live-cast-channels" aria-label="Channels" className="fixed inset-x-0 bottom-0 z-40 flex h-[66dvh] flex-col overflow-hidden rounded-t-2xl border-t border-line-2 bg-ground shadow-2xl sm:static sm:mb-4 sm:h-auto sm:max-h-[45dvh] sm:rounded-xl sm:border">
    <header className="flex shrink-0 items-center gap-2 bg-surface py-2 pl-4 pr-2">
      <div className="min-w-0"><h2 className="font-semibold">{listings ? `On now · ${timeOfDay(now)}` : 'Channels'}</h2><p className="text-xs text-neutral-400">Changes the channel on {device}</p></div>
      <Link to="/live-tv" className="ml-auto inline-flex min-h-11 shrink-0 items-center whitespace-nowrap px-2 text-sm text-neutral-300 underline">Full guide</Link>
      <button type="button" onClick={onClose} aria-label="Close channels" className={square}><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" aria-hidden="true"><path d="M6 6l12 12M18 6 6 18" /></svg></button>
    </header>
    <ul className="min-h-0 flex-1 divide-y divide-line overflow-y-auto pb-4 sm:pb-0">{channels.map((item) => <li key={item.id}>
      <button type="button" disabled={!item.available} aria-current={item.id === current ? 'true' : undefined} onClick={() => onPick(item)} className={`flex min-h-16 w-full items-center gap-4 px-4 text-left hover:bg-surface-2 disabled:opacity-40 ${item.id === current ? 'bg-surface-2 ring-2 ring-inset ring-accent' : ''}`}>
        {listings ? <MiniGuideRow channel={item} now={now} /> : <><strong className="w-12 shrink-0 text-lg tabular-nums text-accent">{item.number}</strong><span className="min-w-0 flex-1 truncate text-sm">{item.name}</span><span className="text-xs text-neutral-400">{item.available ? 'No listings' : 'Unavailable'}</span></>}
      </button>
    </li>)}</ul>
  </section>
}

function Chevron({ down }: { down?: boolean }) {
  return <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d={down ? 'm6 9 6 6 6-6' : 'm18 15-6-6-6 6'} /></svg>
}

function GridIcon() {
  return <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true"><path d="M3 5h18v14H3z" /><path d="M3 10h18M9 10v9" /></svg>
}
