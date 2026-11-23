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
import { LiveActivity } from '../components/liveTV/LiveActivity'
import { ChannelName } from '../components/liveTV/LiveGuide'
import { RecordControls } from '../components/liveTV/LiveRecordings'
import { duration, episodeLine, FETCH_HOURS, fetchBlock, nowAndNext, progress, shortDayTime, showListings, timeOfDay, timeRange } from '../lib/liveGuide'
import { behindWords, liveModeWords, livePlaybackWords, parseVolume } from '../lib/liveWords'
import { recordingMarkWords, markLook } from '../lib/liveRecordings'
import { ArrowLeftIcon, CloseIcon, FullscreenIcon, GuideIcon, InfoIcon, PauseIcon, PipIcon, PlayIcon, VolumeIcon } from '../components/icons'
import { RECEIVER_WAIT_MS, liveCastMode, serverCastsLive } from '../lib/liveCast'
import { useCast } from '../cast/sender'
import { CastButton } from '../components/Cast'
import { LiveRemote } from './LiveRemote'

const control = 'btn btn-clear min-h-11 text-white disabled:opacity-40'
// A round button on the picture.
const round = 'inline-grid size-11 shrink-0 place-items-center rounded-full text-neutral-100 hover:bg-white/15 disabled:opacity-40 md:size-12'
const VOLUME_KEY = 'wmm.live.volume'
function storedVolume(): number {
  try {
    return parseVolume(localStorage.getItem(VOLUME_KEY))
  } catch {
    return 1
  }
}
type VideoState = { paused: boolean; behind: boolean; behindSeconds: number; bufferedSeconds: number; muted: boolean; volume: number; height?: number }

/**
 * Plays a channel here, or on the TV while this browser casts to one that
 * plays Live TV: then the page is the TV's remote,
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
  const [info, setInfo] = useState(false)
  const [volume, setVolume] = useState(storedVolume)
  const [pip, setPip] = useState(false)
  const [now, setNow] = useState(() => Date.now())
  const [notice, setNotice] = useState('')
  const [dismissed, setDismissed] = useState('')
  const [videoState, setVideoState] = useState<VideoState>({ paused: true, behind: false, behindSeconds: 0, bufferedSeconds: 0, muted: false, volume: 1 })
  const video = useRef<HTMLVideoElement>(null)
  const container = useRef<HTMLDivElement>(null)
  const guideButton = useRef<HTMLButtonElement>(null)
  const infoButton = useRef<HTMLButtonElement>(null)
  const miniGuide = useRef<HTMLElement>(null)
  const channel = channels.data?.items.find((item) => item.id === channelId)
  const canPlay = !hold && !!caps.data?.enabled && !!caps.data.allowed && !!caps.data.playback && !!channel?.available
  const prompt = liveViewingPrompt(state.lastInput, now)
  // The mini guide says when listings are out of date; the reply is the guide page's, so it is often cached.
  const guideState = useQuery({ ...liveTVGuideQuery(fetchBlock(now), FETCH_HOURS), enabled: listings && guide, select: (reply) => reply.state })
  const { now: onNow, next: upNext } = listings && channel ? nowAndNext(channel, now) : { now: undefined, next: undefined }

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
  const updateVideo = useCallback((next: VideoState) => setVideoState(next), [])
  const changeVolume = useCallback((value: number) => {
    const element = video.current
    setVolume(value)
    try { localStorage.setItem(VOLUME_KEY, String(value)) } catch { /* the level lasts this visit */ }
    if (element) { element.volume = value; if (value > 0 && element.muted) element.muted = false }
  }, [])
  const toggleMute = useCallback(() => { if (video.current) video.current.muted = !video.current.muted }, [])
  const toggleFullscreen = useCallback(() => {
    if (document.fullscreenElement) void document.exitFullscreen()
    else void container.current?.requestFullscreen().catch(() => setNotice('Full screen isn’t available in this browser.'))
  }, [])
  const togglePip = useCallback(() => {
    const element = video.current
    if (!element) return
    if (document.pictureInPictureElement) void document.exitPictureInPicture().catch(() => undefined)
    else void element.requestPictureInPicture().catch(() => setNotice('Picture in picture isn’t available for this channel.'))
  }, [])
  useEffect(() => {
    const element = video.current
    if (!element) return
    const enter = () => setPip(true), leave = () => setPip(false)
    element.addEventListener('enterpictureinpicture', enter); element.addEventListener('leavepictureinpicture', leave)
    return () => { element.removeEventListener('enterpictureinpicture', enter); element.removeEventListener('leavepictureinpicture', leave) }
  }, [state.session?.id])
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
      if (event.key === 'Escape' && info) { setInfo(false); infoButton.current?.focus(); return }
      if (event.target instanceof HTMLElement && (event.target.matches('input, select, textarea') || event.target.isContentEditable)) return
      controller.noteInput()
      switch (event.key) {
        case 'ArrowUp': case 'PageUp': event.preventDefault(); changeChannel(1); break
        case 'ArrowDown': case 'PageDown': event.preventDefault(); changeChannel(-1); break
        case 'g': case 'G': event.preventDefault(); setInfo(false); setGuide((open) => !open); break
        case 'i': case 'I': event.preventDefault(); setGuide(false); setInfo((open) => !open); break
        case 'm': case 'M': event.preventDefault(); toggleMute(); break
        case 'f': case 'F': event.preventDefault(); toggleFullscreen(); break
        case ' ': if (!(event.target instanceof HTMLElement && event.target.closest('button, a'))) { event.preventDefault(); togglePlay() }; break
      }
    }
    window.addEventListener('keydown', key); return () => window.removeEventListener('keydown', key)
  }, [controller, changeChannel, togglePlay, toggleMute, toggleFullscreen, guide, info])
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
  const playing = state.phase === 'playing'
  const behind = playing && (videoState.behind || videoState.paused) ? behindWords(videoState.behindSeconds) : undefined
  const channels_ = channels.data?.items ?? []
  const prev = nextLiveChannel(channels_, channelId, -1)
  const nextChannel = nextLiveChannel(channels_, channelId, 1)
  const mode = session?.directive?.mode ?? session?.mode
  const modeReason = session?.directive?.modeReason ?? session?.modeReason
  const pipOffered = typeof document !== 'undefined' && document.pictureInPictureEnabled
  const qualityLabel = (value: string) => (value === 'auto' ? (session?.automatic && actualQuality ? `Auto · ${actualQuality}` : 'Automatic') : `Up to ${value}p`)
  const sheetOpen = guide || info
  return <div ref={container} className="relative h-dvh min-h-100 overflow-hidden bg-black text-white" onPointerDown={controller.noteInput} onKeyDown={controller.noteInput}>
    {playing && session && <LiveVideo key={session.id} session={session} controller={controller} videoRef={video} volume={volume} onState={updateVideo} />}

    {/* The top: back to the guide, the channel and what is on, Record, Cast and About. */}
    <header className={`absolute inset-x-0 top-0 z-20 flex items-center gap-2.5 bg-gradient-to-b from-black/75 to-transparent px-3 pb-11 pt-3 md:gap-3.5 md:px-7 md:pt-5 ${guide ? 'md:left-[420px]' : ''} ${info ? 'md:right-[400px]' : ''}`}>
      <Link to="/live-tv" className="btn btn-ghost min-h-11 shrink-0 pl-1.5 pr-3 text-neutral-100" aria-label="Stop watching and go back to the guide"><ArrowLeftIcon size={18} /><span className="hidden sm:inline">Guide</span></Link>
      {channel && <span className="inline-flex min-h-9 shrink-0 items-center gap-2 rounded-[9px] bg-ground/60 py-1 pl-1.5 pr-3 ring-1 ring-inset ring-white/15"><ChannelName channel={channel} /></span>}
      <div className="flex min-w-0 flex-col leading-tight">
        <h1 className="truncate text-base font-[650] md:text-xl">{onNow?.title ?? channel?.name ?? 'Live TV'}</h1>
        {onNow && episodeLine(onNow) && <span className="truncate text-[13.5px] text-neutral-300">{episodeLine(onNow)}</span>}
      </div>
      <div className="ml-auto flex shrink-0 items-center gap-1.5">
        {onNow && playing && <span className="hidden flex-wrap items-center gap-1.5 md:flex [&_.btn]:border-white/15 [&_.btn]:bg-ground/55 [&_[role=status]]:text-[13px]"><RecordControls programme={onNow} now={now} /></span>}
        {castOffered && <CastButton variant="player" notLive={notLive} />}
        <button ref={infoButton} type="button" className={round} aria-expanded={info} aria-controls="live-info" aria-label="About this programme and how it plays" onClick={() => { setGuide(false); setInfo((open) => !open) }}><InfoIcon size={21} /></button>
      </div>
    </header>

    {/* What happens in the middle: errors, tuning and the end of a play. */}
    <div className="pointer-events-none absolute inset-0 z-10 flex items-center justify-center p-4">
      {queryError ? <div role="alert" className="pointer-events-auto max-w-md space-y-4 rounded-xl border border-line-2 bg-surface p-6 text-center"><h2 className="text-xl font-semibold">Unable to load this channel</h2><p className="text-sm text-neutral-300">{queryError.message}</p><button type="button" className={control} onClick={() => { void caps.refetch(); void channels.refetch() }}>Try again</button></div> : (blocked || missing) ? <div className="pointer-events-auto max-w-md space-y-4 rounded-xl border border-line-2 bg-surface p-6 text-center"><h2 className="text-xl font-semibold">This channel is not available</h2><p className="text-sm text-neutral-300">Return to Live TV to see the channels you can watch.</p><Link to="/live-tv" className={`${control} w-full`}>Back to the guide</Link></div>
        : ended ? <section role="status" className="pointer-events-auto max-h-full w-full max-w-xl overflow-y-auto rounded-xl border border-line-2 bg-surface p-6"><h2 className="text-xl font-semibold">{failure.title}</h2><p className="mt-2 text-sm leading-relaxed text-neutral-300">{failure.detail}</p><div className="mt-5 flex flex-wrap gap-3"><button type="button" onClick={() => { setNotice(''); void controller.switchTo(channelId, quality) }} className="btn btn-primary min-h-11">{state.error === 'pause_expired' || state.error === 'inactive' ? 'Resume live' : 'Try again'}</button>{state.error === 'recording' && session?.takeoverRecordingId && <Link to="/live-tv/recordings/$recordingId/watch" params={{ recordingId: session.takeoverRecordingId }} search={{ title: session.takeoverTitle, back: '/live-tv' }} className="btn min-h-11">Watch it from the start</Link>}<Link to="/live-tv" className="btn min-h-11">Back to the guide</Link></div>{admin && state.error === 'tuners_busy' && <div className="mt-5 border-t border-line pt-4"><LiveActivity canStop /><Link to="/activity" className="mt-3 inline-flex min-h-11 items-center text-sm underline">Open Activity</Link></div>}</section>
          : !playing ? <div role="status" className="text-center"><span aria-hidden="true" className="mb-4 inline-block h-8 w-8 animate-spin rounded-full border-2 border-white/20 border-t-accent motion-reduce:animate-none" /><p className="text-lg font-medium">Tuning {channel?.number || 'channel'}…</p><p className="mt-2 text-sm text-neutral-400">{hold ? 'Checking what the TV can play' : 'Starting the live broadcast'}</p></div> : null}
    </div>

    {(warning || notice) && <div className="pointer-events-none absolute inset-x-4 top-24 z-30 mx-auto flex max-w-xl flex-col items-center gap-2">
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

    {/* The foot: now and next, then the controls. */}
    {(playing || state.phase === 'tuning') && <div className={`absolute inset-x-0 bottom-0 z-20 bg-gradient-to-t from-black/90 to-transparent px-3 pb-4 pt-16 md:px-12 md:pb-5 ${guide ? 'md:left-[420px]' : ''} ${info ? 'md:right-[400px]' : ''} ${sheetOpen ? 'max-md:hidden' : ''}`}>
      <div className="flex items-end gap-8">
        <section aria-label="On now" className="flex min-w-0 max-w-[620px] flex-1 flex-col gap-1.5">
          <p className="text-xs font-bold uppercase tracking-[.08em] text-neutral-300">{onNow ? `Now · ${timeRange(Date.parse(onNow.start), Date.parse(onNow.end))}` : 'Live · no listings for this channel now'}</p>
          <p className="truncate text-lg font-bold tracking-[-.01em] md:text-[22px]">{onNow?.title ?? channel?.name}</p>
          {onNow && <div className="flex items-center gap-3"><span className="meter flex-1 bg-white/25" role="img" aria-label={`${Math.round((now - Date.parse(onNow.start)) / 60_000)} minutes in`}><i style={{ width: `${progress(onNow, now)}%` }} /></span><span className="whitespace-nowrap text-[13.5px]">{duration(Date.parse(onNow.end) - now)} left</span></div>}
        </section>
        {upNext && <section aria-label="Next" className="ml-auto hidden min-w-0 flex-col gap-1 text-right md:flex">
          <p className="text-xs font-bold uppercase tracking-[.08em] text-neutral-300">Next · {timeOfDay(Date.parse(upNext.start))}</p>
          <p className="truncate font-semibold">{upNext.title}</p>
          {(episodeLine(upNext) || markLook(upNext.recording)) && <p className="inline-flex items-center justify-end gap-[7px] text-[13px] text-neutral-300">{episodeLine(upNext)}{markLook(upNext.recording) && markLook(upNext.recording) !== 'hollow' && <><span aria-hidden="true" className="size-2 rounded-full bg-red-400" />{recordingMarkWords(upNext.recording).toLowerCase()}</>}</p>}
        </section>}
      </div>
      {playing && videoState.paused && <p role="status" className="mt-3 text-sm text-neutral-300">Paused · About one minute buffered. Tuner releases in {Math.floor(pauseSeconds / 60)}:{String(pauseSeconds % 60).padStart(2, '0')}.</p>}
      <div className="mt-3.5 flex flex-wrap items-center gap-x-2 gap-y-2">
        <button type="button" className={round} disabled={!playing} onClick={togglePlay} aria-label={videoState.paused ? 'Play' : 'Pause'}>{videoState.paused ? <PlayIcon size={22} /> : <PauseIcon size={22} />}</button>
        {playing && (behind ? <>
          <span className="inline-flex min-h-[26px] items-center rounded-md bg-ground/70 px-2.5 text-xs font-semibold tabular-nums">{behind}</span>
          <button type="button" className="btn btn-clear btn-sm" onClick={goLive}>Go live</button>
        </> : <span className="inline-flex min-h-[26px] items-center gap-1.5 rounded-md bg-brand px-2.5 text-xs font-bold text-white ring-1 ring-inset ring-brand-line"><span aria-hidden="true" className="size-[7px] rounded-full bg-white" />Live</span>)}
        {!playing && <span className="text-xs text-neutral-300">Tuning…</span>}
        <span className="ml-1.5 flex items-center gap-1.5 md:ml-3.5">
          <button type="button" className={`${control} px-3`} onClick={() => changeChannel(-1)} aria-label={prev ? `Channel down: ${prev.number} ${prev.name}` : 'Channel down'}>Ch −</button>
          <button type="button" className={`${control} px-3`} onClick={() => changeChannel(1)} aria-label={nextChannel ? `Channel up: ${nextChannel.number} ${nextChannel.name}` : 'Channel up'}>Ch +</button>
          <button ref={guideButton} type="button" className={`${control} px-3`} aria-expanded={guide} aria-controls="live-mini-guide" onClick={() => { setInfo(false); setGuide((open) => !open) }}><GuideIcon size={17} /><span className="hidden sm:inline">Guide</span></button>
        </span>
        <span className="ml-auto flex items-center gap-1.5">
          <label className="relative">
            <span className="sr-only">Live TV quality</span>
            <select aria-label="Live TV quality" value={selectedQuality} onChange={(event) => chooseQuality(event.target.value)} className="chip min-h-9 appearance-none border-white/30 bg-transparent pr-3 text-neutral-100 [&>option]:bg-surface [&>option]:text-neutral-100">
              {LIVE_QUALITIES.filter((value) => value !== 'auto' || caps.data?.automatic).map((value) => <option key={value} value={value}>{qualityLabel(value)}</option>)}
            </select>
          </label>
          <button type="button" className={round} onClick={toggleMute} aria-label={videoState.muted || volume === 0 ? 'Unmute' : 'Mute'}><VolumeIcon muted={videoState.muted || volume === 0} size={21} /></button>
          <label className="hidden md:block"><span className="sr-only">Volume</span><input type="range" min={0} max={100} value={Math.round((videoState.muted ? 0 : volume) * 100)} onChange={(event) => changeVolume(Number(event.target.value) / 100)} className="w-[110px] accent-neutral-100" /></label>
          {pipOffered && <button type="button" className={`${round} ${pip ? 'text-accent' : ''}`} disabled={!playing} onClick={togglePip} aria-pressed={pip} aria-label="Picture in picture"><PipIcon size={21} /></button>}
          <button type="button" className={`${round} max-sm:hidden`} onClick={toggleFullscreen} aria-label="Full screen"><FullscreenIcon size={21} /></button>
        </span>
      </div>
      {session?.automatic && session.switchStatus && session.switchStatus !== 'ready' && <p role="status" className="mt-2 text-xs text-neutral-300">{session.switchStatus === 'preparing' ? 'Preparing another quality while this channel keeps playing.' : session.switchStatus === 'waiting_capacity' ? 'Keeping this quality until the server has room to improve it.' : session.switchStatus === 'source_wait' ? 'Waiting for new broadcast segments.' : 'Keeping the current quality after a switch could not finish.'}</p>}
    </div>}

    {/* The mini guide: every channel and what is on, from the left. */}
    {guide && <aside ref={miniGuide} id="live-mini-guide" aria-label="On now" className="absolute inset-y-0 left-0 z-30 flex w-full flex-col gap-3.5 border-r border-line-2 bg-surface/97 px-4 pb-6 pt-5 md:w-[420px] md:px-[22px]">
      <div className="flex items-center gap-2.5">
        <div className="min-w-0 flex-1"><h2 className="text-lg font-[650]">{listings ? 'On now' : 'Channels'}</h2>{guideState.data?.stale && <p className="text-xs text-neutral-400">Listings last updated {guideState.data.updatedAt ? shortDayTime(Date.parse(guideState.data.updatedAt), now) : 'over 36 hours ago'} and may be out of date</p>}</div>
        <span className="text-[13px] text-neutral-400">{timeOfDay(now)}</span>
        <button type="button" className={`${round} size-10 md:size-10`} aria-label="Close the guide" onClick={() => { setGuide(false); guideButton.current?.focus() }}><CloseIcon size={18} /></button>
      </div>
      <ul className="-mx-2.5 flex min-h-0 flex-1 flex-col gap-0.5 overflow-y-auto">{channels_.map((item) => {
        const { now: p, next } = listings ? nowAndNext(item, now) : { now: undefined, next: undefined }
        const current = item.id === channelId
        const recording = p?.recording?.state === 'recording'
        return <li key={item.id}><button type="button" disabled={!item.available} aria-current={current ? 'true' : undefined} onClick={() => { setGuide(false); setNotice(''); void navigate({ to: '/live-tv/watch/$channelId', params: { channelId: item.id }, replace: true }) }}
          className={`flex min-h-[58px] w-full items-center gap-2.5 rounded-[10px] border px-2.5 py-1.5 text-left hover:bg-surface-2 disabled:opacity-40 ${current ? 'border-accent bg-raise' : 'border-transparent'}`}>
          <span className="flex w-[108px] shrink-0 items-center gap-2"><ChannelName channel={item} /></span>
          <span className="min-w-0 flex-1 leading-snug">
            <span className="block truncate font-semibold">{p?.title ?? (item.available ? item.name : 'Unavailable')}</span>
            <span className="block truncate text-[12.5px] text-neutral-400">{p ? `Until ${timeOfDay(Date.parse(p.end))}${next ? ` · next ${next.title}` : ''}` : item.available ? 'No listings' : 'Not in the tuner’s latest lineup'}</span>
          </span>
          {current && <span className="pill pill-gold">Watching</span>}
          {recording && <span role="img" aria-label="Recording now" className="size-2 shrink-0 rounded-full bg-red-400" />}
        </button></li>
      })}</ul>
      <Link to="/live-tv" className="btn btn-sm self-start">Open the full guide</Link>
    </aside>}

    {/* About this programme, and how it plays here: the sentence first, the numbers closed. */}
    {info && <aside id="live-info" aria-label="About this programme" className="absolute inset-y-0 right-0 z-30 flex w-full flex-col gap-3.5 overflow-y-auto border-l border-line-2 bg-surface/97 px-4 pb-6 pt-5 md:w-[400px] md:px-[22px]">
      <div className="flex items-center gap-2.5"><h2 className="flex-1 text-lg font-[650]">About this programme</h2><button type="button" className={`${round} size-10 md:size-10`} aria-label="Close" onClick={() => { setInfo(false); infoButton.current?.focus() }}><CloseIcon size={18} /></button></div>
      {onNow ? <>
        <div>
          <p className="text-[17px] font-[650]">{onNow.title}</p>
          <p className="mt-0.5 text-[13.5px] text-neutral-300">{[episodeLine(onNow), timeRange(Date.parse(onNow.start), Date.parse(onNow.end))].filter(Boolean).join(' · ')}</p>
        </div>
        {(onNow.new || onNow.rating || onNow.categories?.length) && <p className="flex flex-wrap gap-1.5">{onNow.new && <span className="pill pill-gold">New</span>}{onNow.rating && <span className="pill pill-line">{onNow.rating}</span>}{onNow.categories?.length ? <span className="pill">{onNow.categories.join(' · ')}</span> : null}</p>}
        {onNow.description && <p className="text-[13.5px] leading-relaxed text-neutral-300">{onNow.description}</p>}
        {playing && <span className="flex flex-wrap items-center gap-2 md:hidden"><RecordControls programme={onNow} now={now} /></span>}
      </> : <p className="text-sm text-neutral-300">{channel ? `${channel.number} ${channel.name}` : 'This channel'} has no listings now. It still plays.</p>}
      <div className="mt-1.5 flex flex-col gap-2.5 border-t border-line-2 pt-4">
        <p className="text-[14.5px] leading-relaxed">{session ? livePlaybackWords({ mode, modeReason, automatic: session.automatic, actualQuality: session.actualQuality, quality: session.quality, height: videoState.height }) : 'Tuning the channel.'}</p>
        {session && <details className="tech bg-surface-2">
          <summary className="!min-h-11">Stream details</summary>
          <div className="tech-body">
            <dl className="dl">
              {liveModeWords(mode) && <><dt>Mode</dt><dd>{liveModeWords(mode)}</dd></>}
              <dt>Quality</dt><dd>{selectedQuality === 'auto' ? `Automatic${actualQuality ? `, now ${actualQuality}` : ''}` : `Up to ${selectedQuality}p`}</dd>
              {!!session.renditionBitrate && <><dt>Bitrate</dt><dd>{(session.renditionBitrate / 1e6).toFixed(1)} Mb/s</dd></>}
              {playing && <><dt>Buffer</dt><dd>{Math.round(videoState.bufferedSeconds)} s ahead</dd></>}
            </dl>
          </div>
        </details>}
        {!!caps.data?.pauseSeconds && <p className="text-[13px] text-neutral-400">The tuner is released {duration(caps.data.pauseSeconds * 1000)} after you pause.</p>}
      </div>
    </aside>}

    {prompt === 'ask' && playing && <div role="dialog" aria-modal="true" aria-labelledby="live-still-watching" className="absolute inset-0 z-40 grid place-items-center bg-black/80 p-4"><div className="w-full max-w-sm rounded-xl border border-white/20 bg-surface p-6"><h2 id="live-still-watching" className="text-xl font-semibold">Are you still watching?</h2><p className="mt-2 text-sm text-neutral-300">Your tuner will be released in one minute unless you continue.</p><button type="button" autoFocus onClick={controller.noteInput} className="btn btn-primary mt-5 min-h-11 w-full">Continue watching</button><button type="button" onClick={() => { void controller.stop(); void navigate({ to: '/live-tv' }) }} className="btn mt-2 min-h-11 w-full">Back to the guide</button></div></div>}
  </div>
}

function LiveVideo({ session, controller, videoRef, volume, onState }: { session: LiveSession; controller: LiveTVController; videoRef: RefObject<HTMLVideoElement | null>; volume: number; onState: (state: VideoState) => void }) {
  const [blocked, setBlocked] = useState(false)
  const sourceURL = session.automatic && session.directive?.url ? session.directive.url : session.url
  const switchGeneration = session.automatic ? session.directive?.generation : undefined
  const offered = session.directive ? session.directive.adaptive : session.adaptive
  const adaptiveRef = useRef(offered)
  useEffect(() => { adaptiveRef.current = offered }, [offered])
  const generationRef = useRef(switchGeneration)
  useEffect(() => { generationRef.current = switchGeneration }, [switchGeneration])
  // The level chosen here, kept for the next channel's picture.
  const volumeRef = useRef(volume)
  useEffect(() => { volumeRef.current = volume }, [volume])
  useEffect(() => {
    const element = videoRef.current
    if (!element) return
    element.volume = volumeRef.current
    onState({ paused: true, behind: false, behindSeconds: 0, bufferedSeconds: 0, muted: element.muted, volume: element.volume })
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
      const start = element.seekable.length ? element.seekable.start(0) : end
      onState({ paused: element.paused, behind: end - element.currentTime > 7, behindSeconds: Math.max(0, liveEdge(start, end) - element.currentTime), bufferedSeconds: bufferedAhead(element.buffered, element.currentTime), muted: element.muted, volume: element.volume, height: observed?.renditionHeight })
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
  return <><video ref={videoRef} playsInline disableRemotePlayback x-webkit-airplay="deny" className="absolute inset-0 h-full w-full object-contain" aria-label={`${session.channel.number} ${session.channel.name} live broadcast`} />{blocked && <div className="absolute inset-0 z-10 grid place-items-center"><button type="button" onClick={() => { controller.noteInput(); void videoRef.current?.play().catch(() => setBlocked(true)) }} className="btn btn-primary btn-lg min-h-14 rounded-full px-8 text-lg"><PlayIcon size={20} />Play channel</button></div>}</>
}
