import { cancelRecording } from '../api/recordings'
import { useRecordingMutation } from '../components/liveTV/useRecordings'
import { takeoverWords } from '../lib/liveRecordings'
import { useCallback, useEffect, useRef, useState, useSyncExternalStore, type RefObject } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import Hls from 'hls.js'
import { liveTVCapabilitiesQuery, liveTVChannelsQuery, liveTVGuideQuery, serverQuery } from '../api/queries'
import { liveTransport, type LiveSession } from '../api/liveTV'
import { useAuth } from '../authContext'
import { LiveTVController } from '../lib/liveTVController'
import { LIVE_QUALITIES, liveEdge, liveFailure, liveQuality, liveViewingPrompt, nextLiveChannel, pauseExpired, saveLiveQuality, savedLiveQuality } from '../lib/liveTV'
import { prefersNativeHls } from '../lib/airplay'
import { failureRoute, observedRendition } from '../lib/play/adaptive'
import { TransferMeter } from '../lib/play/recovery'
import { bufferedAhead } from '../lib/play/rules'
import { LiveBadge } from '../components/liveTV/LiveBadge'
import { LiveActivity } from '../components/liveTV/LiveActivity'
import { ChannelBanner, MiniGuideRow } from '../components/liveTV/LiveGuide'
import { FETCH_HOURS, fetchBlock, nowAndNext, shortDayTime, showListings, timeOfDay } from '../lib/liveGuide'
import { RECEIVER_WAIT_MS, liveCastMode, serverCastsLive } from '../lib/liveCast'
import { useCast } from '../cast/sender'
import { CastButton } from '../components/Cast'
import { LiveRemote } from './LiveRemote'

const control = 'inline-flex min-h-11 items-center justify-center gap-2 rounded-md border border-white/20 bg-black/60 px-3 text-sm text-white hover:bg-white/10 disabled:opacity-40'

/**
 * Plays a channel here, or on the TV while this browser casts to one that
 * plays Live TV (PLAN-LIVE-TV.md §4.1): then the page is the TV's remote,
 * as Player is for files. The live session here is stopped before the TV
 * starts its own. When casting ends, the remote stays up and offers to
 * carry on here.
 */
export function LivePlayer({ channelId }: { channelId: string }) {
  const [controller] = useState(() => new LiveTVController(liveTransport))
  const cast = useCast()
  const caps = useQuery(liveTVCapabilitiesQuery)
  const server = useQuery(serverQuery)
  const castsLive = serverCastsLive(server.data?.apiLevel, caps.data)
  const connected = cast.status === 'connected'
  // A connected TV says what it plays in its first state; wait for it a moment before tuning here.
  const [waitedOut, setWaitedOut] = useState(false)
  const mode = liveCastMode({ connected, server: castsLive, receiver: cast.receiver, waitedOut })
  useEffect(() => {
    if (mode !== 'waiting') return
    const timer = window.setTimeout(() => setWaitedOut(true), RECEIVER_WAIT_MS)
    return () => window.clearTimeout(timer)
  }, [mode])
  const [remote, setRemote] = useState(false)
  // Continue on this browser keeps playing here until this connection ends.
  const [declined, setDeclined] = useState(false)
  if (declined && !connected) setDeclined(false)
  if (mode === 'remote' && !remote && !declined) setRemote(true)
  if (remote) return <LiveRemote channelId={channelId} controller={controller} onLocal={() => { setDeclined(true); setRemote(false) }} />
  return <LocalLivePlayer channelId={channelId} controller={controller} hold={mode === 'waiting'} notLive={mode === 'notLive'} castOffered={castsLive} />
}

function LocalLivePlayer({ channelId, controller, hold, notLive, castOffered }: { channelId: string; controller: LiveTVController; hold: boolean; notLive: boolean; castOffered: boolean }) {
  const auth = useAuth()
  const admin = !!auth.user?.isAdmin && auth.adminAllowed
  const cancel = useRecordingMutation(cancelRecording)
  const caps = useQuery(liveTVCapabilitiesQuery)
  const server = useQuery(serverQuery)
  const listings = showListings(server.data?.apiLevel, caps.data)
  const channels = useQuery({ ...liveTVChannelsQuery, enabled: !!caps.data?.allowed && !!caps.data?.enabled })
  const navigate = useNavigate()
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot)
  const [quality, setQuality] = useState(savedLiveQuality)
  const selectedQuality = quality === 'auto' && !caps.data?.automatic ? '1080' : quality
  const [guide, setGuide] = useState(false)
  const [now, setNow] = useState(() => Date.now())
  const [notice, setNotice] = useState('')
  const [dismissed, setDismissed] = useState('')
  const [videoState, setVideoState] = useState<{ paused: boolean; behind: boolean; muted: boolean; height?: number }>({ paused: true, behind: false, muted: false })
  const video = useRef<HTMLVideoElement>(null)
  const container = useRef<HTMLDivElement>(null)
  const guideButton = useRef<HTMLButtonElement>(null)
  const miniGuide = useRef<HTMLElement>(null)
  const channel = channels.data?.items.find((item) => item.id === channelId)
  const canPlay = !hold && !!caps.data?.enabled && !!caps.data.allowed && !!caps.data.playback && !!channel?.available
  const prompt = liveViewingPrompt(state.lastInput, now)
  // The mini guide says when listings are out of date; the reply is the guide page's, so it is often cached.
  const guideState = useQuery({ ...liveTVGuideQuery(fetchBlock(now), FETCH_HOURS), enabled: listings && guide, select: (reply) => reply.state })
  const onNow = listings && channel ? nowAndNext(channel, now).now : undefined

  useEffect(() => {
    if (canPlay) void controller.switchTo(channelId, selectedQuality)
    else void controller.stop()
  }, [canPlay, channelId, selectedQuality, controller])
  useEffect(() => {
    let ticks = 0
    const timer = window.setInterval(() => {
      setNow(Date.now()); ticks++
      if (controller.getSnapshot().phase === 'tuning' || ticks % 10 === 0) void controller.poll(ticks % 10 === 0)
    }, 1000)
    const leave = () => { void controller.stop() }
    const restore = (event: PageTransitionEvent) => { if (event.persisted) window.location.reload() }
    window.addEventListener('pagehide', leave)
    window.addEventListener('pageshow', restore)
    return () => { window.clearInterval(timer); window.removeEventListener('pagehide', leave); window.removeEventListener('pageshow', restore); void controller.stop() }
  }, [controller])
  useEffect(() => { if (prompt === 'expired' && (state.phase === 'playing' || state.phase === 'tuning')) void controller.stop('inactive') }, [prompt, state.phase, controller])
  useEffect(() => { if (guide) miniGuide.current?.querySelector<HTMLButtonElement>('[aria-current="true"]')?.focus() }, [guide])
  const updateVideo = useCallback((paused: boolean, behind: boolean, muted: boolean, height?: number) => setVideoState({ paused, behind, muted, height }), [])
  const changeChannel = useCallback((direction: -1 | 1) => {
    const next = nextLiveChannel(channels.data?.items ?? [], channelId, direction)
    if (next) { setGuide(false); setNotice(''); void navigate({ to: '/live-tv/watch/$channelId', params: { channelId: next.id }, replace: true }) }
  }, [channels.data, channelId, navigate])
  const goLive = useCallback(() => {
    const element = video.current
    if (element?.seekable.length) { element.currentTime = liveEdge(element.seekable.start(0), element.seekable.end(element.seekable.length - 1)); void element.play().catch(() => setNotice('Press Play to resume.')) }
  }, [])
  const togglePlay = useCallback(() => {
    const element = video.current
    if (!element) return
    if (!element.paused) { element.pause(); return }
    if (element.seekable.length && pauseExpired(element.currentTime, element.seekable.start(0), element.seekable.end(element.seekable.length - 1))) {
      element.currentTime = liveEdge(element.seekable.start(0), element.seekable.end(element.seekable.length - 1))
      setNotice('Your pause passed the buffer. Resuming at live.')
    }
    void element.play().catch(() => setNotice('Playback could not resume. Try the channel again.'))
  }, [])
  useEffect(() => {
    const key = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && guide) { setGuide(false); guideButton.current?.focus(); return }
      if (event.target instanceof HTMLElement && (event.target.matches('input, select, textarea') || event.target.isContentEditable)) return
      controller.noteInput()
      switch (event.key) {
        case 'ArrowUp': case 'PageUp': event.preventDefault(); changeChannel(1); break
        case 'ArrowDown': case 'PageDown': event.preventDefault(); changeChannel(-1); break
        case 'g': case 'G': event.preventDefault(); setGuide((open) => !open); break
        case ' ': if (!(event.target instanceof HTMLElement && event.target.closest('button, a'))) { event.preventDefault(); togglePlay() }; break
      }
    }
    window.addEventListener('keydown', key); return () => window.removeEventListener('keydown', key)
  }, [controller, changeChannel, togglePlay, guide])
  const failure = liveFailure(state.error)
  const blocked = caps.data && (!caps.data.enabled || !caps.data.allowed || !caps.data.playback)
  const missing = channels.data && (!channel || !channel.available)
  const queryError = caps.error || channels.error
  const ended = state.phase === 'ended' || state.phase === 'error'
  const session = state.session
  const adaptive = session?.directive ? session.directive.adaptive : session?.adaptive
  const actualQuality = adaptive ? videoState.height ? `${videoState.height}p` : 'Starting' : session?.automatic ? session.actualQuality === 'original' ? 'Original' : session.actualQuality ? `${session.actualQuality}p` : 'Starting' : undefined
  // One takeover is one warning: OK, or Don't record it, puts it away until another comes.
  const takeoverKey = session?.takeoverAt ? `${session.takeoverRecordingId ?? ''}@${session.takeoverAt}` : ''
  const warning = !!takeoverKey && state.phase === 'playing' && dismissed !== takeoverKey
  const pauseSeconds = session?.paused ? Math.max(0, session.pauseRemainingSeconds - Math.max(0, Math.floor((now - new Date(session.lastSeenAt).getTime()) / 1000))) : 300
  function chooseQuality(value: string) {
    const next = liveQuality(value); setQuality(next); setNotice('')
    saveLiveQuality(next)
  }
  return <div ref={container} className="relative flex h-dvh min-h-100 flex-col overflow-hidden bg-black text-white" onPointerDown={controller.noteInput} onKeyDown={controller.noteInput}>
    {state.phase === 'playing' && session && <LiveVideo key={session.id} session={session} controller={controller} videoRef={video} onState={updateVideo} />}
    <header className="relative z-20 flex shrink-0 items-center justify-between gap-3 bg-gradient-to-b from-black/90 to-transparent p-4 sm:p-6">
      <Link to="/live-tv" className={control} aria-label="Stop playback and return to channels"><span aria-hidden="true">‹</span><span>Channels</span></Link>
      <h1 className="min-w-0 truncate text-sm font-medium sm:text-base"><span className="mr-2 tabular-nums text-accent">{channel?.number}</span>{channel?.name || 'Live TV'}{onNow && <span className="hidden text-neutral-300 sm:inline"> · {onNow.title}</span>}</h1>
      <div className="flex shrink-0 items-center gap-2">
        {castOffered && <CastButton variant="player" notLive={notLive} />}
        <button type="button" className={control} onClick={() => { void controller.stop(); void navigate({ to: '/live-tv' }) }}>Stop</button>
      </div>
    </header>
    <div className="relative z-10 flex min-h-0 flex-1 items-center justify-center p-4 pointer-events-none">
      {queryError ? <div role="alert" className="pointer-events-auto max-w-md space-y-4 rounded-xl border border-line-2 bg-surface p-6 text-center"><h2 className="text-xl font-semibold">Unable to load this channel</h2><p className="text-sm text-neutral-300">{queryError.message}</p><button type="button" className={control} onClick={() => { void caps.refetch(); void channels.refetch() }}>Try again</button></div> : (blocked || missing) ? <div className="pointer-events-auto max-w-md space-y-4 rounded-xl border border-line-2 bg-surface p-6 text-center"><h2 className="text-xl font-semibold">This channel is not available</h2><p className="text-sm text-neutral-300">Return to Live TV to see the channels you can watch.</p><Link to="/live-tv" className={`${control} w-full`}>Back to channels</Link></div>
        : ended ? <section role="status" className="pointer-events-auto max-h-full w-full max-w-xl overflow-y-auto rounded-xl border border-line-2 bg-surface p-6"><h2 className="text-xl font-semibold">{failure.title}</h2><p className="mt-2 text-sm leading-relaxed text-neutral-300">{failure.detail}</p><div className="mt-5 flex flex-wrap gap-3"><button type="button" onClick={() => { setNotice(''); void controller.switchTo(channelId, quality) }} className="min-h-11 rounded-md btn-primary px-5 font-semibold">{state.error === 'pause_expired' || state.error === 'inactive' ? 'Resume live' : 'Try again'}</button>{state.error === 'recording' && session?.takeoverRecordingId && <Link to="/live-tv/recordings/$recordingId/watch" params={{ recordingId: session.takeoverRecordingId }} search={{ title: session.takeoverTitle, back: '/live-tv' }} className={control}>Watch it from the start</Link>}<Link to="/live-tv" className={control}>Back to channels</Link></div>{admin && state.error === 'tuners_busy' && <div className="mt-5 border-t border-line pt-4"><LiveActivity canStop /><Link to="/activity" className="mt-3 inline-flex min-h-11 items-center text-sm underline">Open Activity</Link></div>}</section>
          : state.phase !== 'playing' ? <div role="status" className="text-center"><span aria-hidden="true" className="mb-4 inline-block h-8 w-8 animate-spin rounded-full border-2 border-white/20 border-t-accent motion-reduce:animate-none" /><p className="text-lg font-medium">Tuning {channel?.number || 'channel'}…</p><p className="mt-2 text-sm text-neutral-400">{hold ? 'Checking what the TV can play' : 'Starting the live broadcast'}</p></div> : null}
    </div>
    {(warning || notice) && <div className="pointer-events-none absolute inset-x-4 top-22 z-20 mx-auto flex max-w-xl flex-col items-center gap-2">
      {/* The warning is announced as it appears; OK puts it away for this takeover. */}
      {warning && session?.takeoverAt && <section role="alert" className="pointer-events-auto flex w-full flex-wrap items-center gap-3 rounded-xl border border-warn/60 bg-ground/95 p-4">
        <p className="min-w-0 flex-1 basis-64 text-sm text-neutral-200">{takeoverWords(session.takeoverAt, session.takeoverTitle ?? 'a programme', now)} You can watch the recording from the start.</p>
        <div className="flex flex-wrap items-center gap-2">
          <button type="button" className={control} onClick={() => setDismissed(takeoverKey)}>OK</button>
          {session.takeoverCanCancel && session.takeoverRecordingId && <button type="button" disabled={cancel.isPending || (cancel.isSuccess && cancel.variables === session.takeoverRecordingId)} className={control} onClick={() => cancel.mutate(session.takeoverRecordingId!, { onSuccess: () => setDismissed(takeoverKey) })}>Don’t record it</button>}
        </div>
        {cancel.isError && <p role="alert" className="basis-full text-sm text-warn">{cancel.error.message}</p>}
      </section>}
      {notice && <p role="status" className="pointer-events-auto w-fit max-w-lg rounded-lg border border-white/20 bg-black/85 px-4 py-2 text-center text-sm">{notice}</p>}
    </div>}
    {(state.phase === 'playing' || state.phase === 'tuning') && <div className="relative z-20 shrink-0 bg-gradient-to-t from-black to-black/60 p-4 sm:p-6">
      {guide ? <section ref={miniGuide} id="live-mini-guide" aria-label="Channel guide" className="mb-4 max-h-[45dvh] overflow-y-auto rounded-xl border border-line-2 bg-ground/95">
        <header className="sticky top-0 z-10 flex items-center justify-between gap-3 bg-surface px-4 py-2"><div className="min-w-0"><h2 className="font-semibold">{listings ? `On now · ${timeOfDay(now)}` : 'Channels'}</h2>{guideState.data?.stale && <p className="text-xs text-neutral-400">Listings last updated {guideState.data.updatedAt ? shortDayTime(Date.parse(guideState.data.updatedAt), now) : 'over 36 hours ago'} and may be out of date</p>}</div><button type="button" className={control} onClick={() => { setGuide(false); guideButton.current?.focus() }}>Close guide</button></header>
        <ul className="divide-y divide-line">{channels.data?.items.map((item) => <li key={item.id}><button type="button" disabled={!item.available} aria-current={item.id === channelId ? 'true' : undefined} onClick={() => { setGuide(false); setNotice(''); void navigate({ to: '/live-tv/watch/$channelId', params: { channelId: item.id }, replace: true }) }} className={`flex min-h-16 w-full items-center gap-4 px-4 text-left hover:bg-surface-2 disabled:opacity-40 ${item.id === channelId ? 'ring-2 ring-inset ring-accent' : ''}`}>{listings ? <MiniGuideRow channel={item} now={now} /> : <><strong className="w-12 shrink-0 text-lg tabular-nums text-accent">{item.number}</strong><span className="min-w-0 flex-1 truncate text-sm">{item.name}</span><span className="text-xs text-neutral-400">{item.available ? 'No listings' : 'Unavailable'}</span></>}</button></li>)}</ul>
      </section> : listings && channel ? <ChannelBanner channel={channel} now={now}>{state.phase === 'playing' ? <LiveBadge behind={videoState.behind || videoState.paused} /> : <span className="text-xs text-neutral-300">Tuning…</span>}</ChannelBanner>
        : <div className="mb-4 flex flex-wrap items-center gap-x-4 gap-y-2 rounded-xl border border-white/15 bg-ground/85 px-4 py-3"><strong className="text-2xl tabular-nums text-accent">{channel?.number}</strong><div className="min-w-0 flex-1"><p className="truncate font-semibold">{channel?.name}</p><p className="mt-0.5 text-xs text-neutral-400">Live broadcast · No programme listings</p></div>{state.phase === 'playing' ? <LiveBadge behind={videoState.behind || videoState.paused} /> : <span className="text-xs text-neutral-300">Tuning…</span>}</div>}
      {state.phase === 'playing' && videoState.paused && <p role="status" className="mb-3 text-sm text-neutral-300">Paused · About one minute buffered. Tuner releases in {Math.floor(pauseSeconds / 60)}:{String(pauseSeconds % 60).padStart(2, '0')}.</p>}
      <div className="flex flex-wrap items-center gap-2">
        <button type="button" className={control} disabled={state.phase !== 'playing'} onClick={togglePlay} aria-label={videoState.paused ? 'Play' : 'Pause'}>{videoState.paused ? '▶ Play' : 'Ⅱ Pause'}</button>
        <button type="button" className={control} disabled={state.phase !== 'playing'} onClick={goLive}>Go live</button>
        <span className="mx-1 hidden h-5 w-px bg-white/20 sm:inline" aria-hidden="true" />
        <button type="button" className={control} onClick={() => changeChannel(-1)} aria-label="Previous channel">Ch−</button>
        <button type="button" className={control} onClick={() => changeChannel(1)} aria-label="Next channel">Ch+</button>
        <button ref={guideButton} type="button" className={control} aria-expanded={guide} aria-controls="live-mini-guide" onClick={() => setGuide((open) => !open)}>Guide</button>
        {session?.automatic && <span className="rounded-full border border-accent px-2.5 py-1 text-xs font-medium text-accent" role="status">Auto · {actualQuality}</span>}
        <label className="ml-auto"><span className="sr-only">Live TV quality</span><select aria-label="Live TV quality" value={selectedQuality} onChange={(event) => chooseQuality(event.target.value)} className={control}>{LIVE_QUALITIES.filter((value) => value !== 'auto' || caps.data?.automatic).map((value) => <option key={value} value={value}>{value === 'auto' ? 'Automatic' : `Up to ${value}p`}</option>)}</select></label>
        <button type="button" className={control} onClick={() => { if (video.current) video.current.muted = !video.current.muted }} aria-label={videoState.muted ? 'Unmute' : 'Mute'}>{videoState.muted ? 'Unmute' : 'Mute'}</button>
        <span className="hidden sm:contents"><button type="button" className={control} onClick={() => { if (document.fullscreenElement) void document.exitFullscreen(); else void container.current?.requestFullscreen().catch(() => setNotice('Fullscreen is not available in this browser.')) }}>Fullscreen</button></span>
      </div>
      {session?.automatic && session.switchStatus && session.switchStatus !== 'ready' && <p role="status" className="mt-2 text-xs text-neutral-300">{session.switchStatus === 'preparing' ? 'Preparing another quality while this channel keeps playing.' : session.switchStatus === 'waiting_capacity' ? 'Keeping this quality until the server has room to improve it.' : session.switchStatus === 'source_wait' ? 'Waiting for new broadcast segments.' : 'Keeping the current quality after a switch could not finish.'}</p>}
    </div>}
    {prompt === 'ask' && state.phase === 'playing' && <div role="dialog" aria-modal="true" aria-labelledby="live-still-watching" className="absolute inset-0 z-30 grid place-items-center bg-black/80 p-4"><div className="w-full max-w-sm rounded-xl border border-white/20 bg-surface p-6"><h2 id="live-still-watching" className="text-xl font-semibold">Are you still watching?</h2><p className="mt-2 text-sm text-neutral-300">Your tuner will be released in one minute unless you continue.</p><button type="button" autoFocus onClick={controller.noteInput} className="mt-5 min-h-11 w-full rounded-md btn-primary px-4 font-semibold">Continue watching</button><button type="button" onClick={() => { void controller.stop(); void navigate({ to: '/live-tv' }) }} className="mt-2 min-h-11 w-full rounded-md border border-line-2 px-4">Back to channels</button></div></div>}
  </div>
}

function LiveVideo({ session, controller, videoRef, onState }: { session: LiveSession; controller: LiveTVController; videoRef: RefObject<HTMLVideoElement | null>; onState: (paused: boolean, behind: boolean, muted: boolean, height?: number) => void }) {
  const [blocked, setBlocked] = useState(false)
  const sourceURL = session.automatic && session.directive?.url ? session.directive.url : session.url
  const switchGeneration = session.automatic ? session.directive?.generation : undefined
  const offered = session.directive ? session.directive.adaptive : session.adaptive
  const adaptiveRef = useRef(offered)
  useEffect(() => { adaptiveRef.current = offered }, [offered])
  const generationRef = useRef(switchGeneration)
  useEffect(() => { generationRef.current = switchGeneration }, [switchGeneration])
  useEffect(() => {
    const element = videoRef.current
    if (!element) return
    onState(true, false, element.muted)
    let active = true
    let hls: Hls | undefined
    const meter = new TransferMeter()
    let hasPlayed = false
    let waiting = false
    let stalls = 0
    let frameConfirmed = false
    let observed: ReturnType<typeof observedRendition>
    const firstFrame = () => {
      const generation = generationRef.current
      if (!active || frameConfirmed || !generation) return
      frameConfirmed = true
      controller.settleOutput(session.id, generation, false)
    }
    const measure = (state: 'playing' | 'paused' | 'buffering') => {
      const transfer = meter.read(performance.now())
      controller.reportPlayback(session.id, {
        state, ...observed, bufferedMs: Math.round(bufferedAhead(element.buffered, element.currentTime) * 1000),
        throughputBps: transfer?.throughputBps, throughputAgeMs: transfer?.throughputAgeMs, stalls,
      })
    }
    const update = () => {
      const end = element.seekable.length ? element.seekable.end(element.seekable.length - 1) : element.currentTime
      onState(element.paused, end - element.currentTime > 7, element.muted, observed?.renditionHeight)
      measure(element.paused ? 'paused' : waiting ? 'buffering' : 'playing')
    }
    const play = () => {
      hasPlayed = true; waiting = false; setBlocked(false); controller.setPaused(session.id, false); update()
      if (generationRef.current && typeof element.requestVideoFrameCallback === 'function') element.requestVideoFrameCallback(firstFrame)
    }
    const pause = () => { controller.setPaused(session.id, true); measure('paused'); update() }
    const stall = () => { if (hasPlayed && !waiting) stalls++; waiting = true; measure('buffering') }
    const frameFallback = () => { if (typeof element.requestVideoFrameCallback !== 'function' && hasPlayed && element.readyState >= HTMLMediaElement.HAVE_CURRENT_DATA) firstFrame() }
    let failurePending = false
    const failed = () => {
      if (!active || failurePending) return
      failurePending = true
      const route = failureRoute(!!generationRef.current, !!adaptiveRef.current)
      if (route === 'switch' && generationRef.current) { controller.settleOutput(session.id, generationRef.current, true); return }
      if (route === 'family') { controller.reportPlayback(session.id, { state: "buffering", adaptiveFailed: true }); void controller.poll(true); return }
      // A removed playlist can mean an admin stopped this viewer. Fetch the
      // authoritative reason before turning a media error into an encoder error.
      void controller.poll(false).then(() => {
        const current = controller.getSnapshot()
        if (active && current.session?.id === session.id && (current.phase === 'playing' || current.phase === 'tuning')) void controller.stop('encoder_failed')
      })
    }
    const begin = () => { void element.play().catch((error: unknown) => { if (active && error instanceof DOMException && error.name === 'NotAllowedError') { setBlocked(true); controller.setPaused(session.id, true) } }) }
    element.addEventListener('playing', play); element.addEventListener('pause', pause); element.addEventListener('waiting', stall); element.addEventListener('timeupdate', update); element.addEventListener('timeupdate', frameFallback); element.addEventListener('progress', update); element.addEventListener('volumechange', update); element.addEventListener('error', failed)
    if (prefersNativeHls() && element.canPlayType('application/vnd.apple.mpegurl')) { element.src = sourceURL; element.addEventListener('loadedmetadata', begin, { once: true }) }
    else if (Hls.isSupported()) {
      hls = new Hls({ liveSyncDurationCount: 3, maxLiveSyncPlaybackRate: 1, maxBufferLength: 30, backBufferLength: 10 })
      hls.on(Hls.Events.ERROR, (_event, data) => { if (data.fatal) failed() })
      hls.on(Hls.Events.MANIFEST_PARSED, begin)
      const observe = () => {
        if (active && element.readyState >= HTMLMediaElement.HAVE_CURRENT_DATA) {
          observed = observedRendition(adaptiveRef.current, element.videoHeight)
          update()
        }
      }
      hls.on(Hls.Events.FRAG_CHANGED, observe)
      element.addEventListener('resize', observe)
      hls.on(Hls.Events.DESTROYING, () => element.removeEventListener('resize', observe))
      hls.on(Hls.Events.FRAG_LOADED, (_event, data) => {
        const stats = data.part?.stats ?? data.frag.stats
        if (data.frag.type === 'main' && data.frag.sn !== 'initSegment') meter.record(stats.loaded, stats.loading.first, stats.loading.end)
        measure(waiting ? 'buffering' : 'playing')
      })
      hls.loadSource(sourceURL); hls.attachMedia(element)
    } else if (element.canPlayType('application/vnd.apple.mpegurl')) { element.src = sourceURL; element.addEventListener('loadedmetadata', begin, { once: true }) }
    else void controller.stop('This browser cannot play live HLS video.')
    return () => {
      active = false
      element.removeEventListener('playing', play); element.removeEventListener('pause', pause); element.removeEventListener('waiting', stall); element.removeEventListener('timeupdate', update); element.removeEventListener('timeupdate', frameFallback); element.removeEventListener('progress', update); element.removeEventListener('volumechange', update); element.removeEventListener('error', failed); element.removeEventListener('loadedmetadata', begin)
      hls?.destroy(); element.pause(); element.removeAttribute('src'); element.load()
    }
  }, [session.id, sourceURL, controller, videoRef, onState])
  return <><video ref={videoRef} playsInline disableRemotePlayback x-webkit-airplay="deny" className="absolute inset-0 h-full w-full object-contain" aria-label={`${session.channel.number} ${session.channel.name} live broadcast`} />{blocked && <div className="absolute inset-0 z-10 grid place-items-center"><button type="button" onClick={() => { controller.noteInput(); void videoRef.current?.play().catch(() => setBlocked(true)) }} className="min-h-14 rounded-full btn-primary px-8 text-lg font-semibold">▶ Play channel</button></div>}</>
}
