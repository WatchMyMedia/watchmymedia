import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useNavigate } from '@tanstack/react-router'
import Hls, { PlaylistLevelType } from 'hls.js'
import { RecordingNotReady, automaticSubtitle, downloadSubtitle, searchSubtitles, startPlayback, stopPlayback, type Decision, type OnlineSubtitle, type SubtitleTrack } from '../api/playback'
import { AutomaticSubtitles } from '../lib/automaticSubtitles'
import { authScope } from '../lib/authScope'
import type { SubtitleSelection } from '../lib/subtitleIntent'
import { formatDuration, languageName, seCode } from '../lib/format'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { preferencesQuery, serverQuery } from '../api/queries'
import { noteAutoAdvance, noteInput, shouldAskStillWatching } from '../lib/stillWatching'
import { StillWatchingCard } from '../components/StillWatchingCard'
import { reportProgress, type PlayState, type QualityDirective } from '../api/watch'
import { onPlayStopped } from '../api/events'
import { useAssRenderer } from '../lib/ass'
import { AUTO, QUALITIES, qualitiesFor, qualityById, saveQuality, savedQuality, stepName, type Quality } from '../lib/quality'
import { QualityMenu } from '../components/QualityMenu'
import { NextEpisodeCard } from '../components/NextEpisodeCard'
import { SkipButton } from '../components/SkipButton'
import { Scrubber, type ScrubPreview } from '../components/Scrubber'
import { usableTrickplay } from '../lib/trickplay'
import { NEXT_COUNTDOWN, SKIP_LABELS, StallMeter, activeSegment, beatDue, bufferedAhead, nextCardShowing, segmentEnd, segmentsAt, skipPlan, waitingAtJoint, type SkipMode } from '../lib/play/rules'
import { AdaptiveFallback, failureRoute, observedRendition, requestAdaptive } from '../lib/play/adaptive'
import { fileHlsBuffer, hlsStartPosition, timelineOffset } from '../lib/play/stream'
import { TransferMeter, higherStep, switchGeneration } from '../lib/play/recovery'
import { mediaToken, prefersNativeHls, useAirPlay, withToken } from '../lib/airplay'
import { noteLocalPlay, useCast } from '../cast/sender'
import { growingDurationMs, growingSeekMs, showGoToNow, startRetryMs, stillRecording } from '../lib/liveRecordings'
import { backAfterNext } from '../lib/showPage'
import { RemotePlayer } from './RemotePlayer'

// back is a path on this site and may carry a query (an old season page's ?episode=),
// so it is followed as an href: as a `to` the query would read as part of the path.
// Links pass to="/" too, which the router uses only when there is no back.
// trailer marks a movie's trailer file, which returns to back when it ends (PLAN-TRAILERS.md §6.1).
type Props = { fileId: number; recordingId?: string; start?: number; title?: string; back?: string; trailer?: boolean }

/**
 * Plays one file here, or on the TV while this browser is casting
 * (PLAN-CAST.md §5.1). When casting ends, the remote stays up and offers to
 * carry on here.
 */
export function Player(props: Props) {
  const connected = useCast().status === 'connected' && !props.recordingId
  const [remote, setRemote] = useState(connected)
  const [lastSubtitle, setLastSubtitle] = useState<{ fileId: number; selection: SubtitleSelection } | null>(null)
  const rememberSubtitle = useCallback((selection: SubtitleSelection) => { setLastSubtitle({ fileId: props.fileId, selection }) }, [props.fileId])
  if (connected && !remote) setRemote(true)
  if (remote && !props.recordingId) return <RemotePlayer {...props} onLocal={() => setRemote(false)} />
  return <LocalPlayer key={props.recordingId ?? props.fileId} {...props} initialSubtitle={lastSubtitle?.fileId === props.fileId ? lastSubtitle.selection : { subtitleMode: 'auto' }} rememberSubtitle={rememberSubtitle} />
}

/**
 * Plays one file. Direct play uses the <video> element as-is; HLS modes
 * attach hls.js, or on Safari play natively from a VOD playlist so AirPlay
 * can take the stream. For an event playlist the server session starts at
 * `offset`, so file position = offset + video.currentTime, and seeking
 * outside what the session has produced restarts it at the new position.
 * VOD playlists and direct play cover the whole file: offset is 0.
 */
function LocalPlayer({ fileId, recordingId, start, title, back, trailer, initialSubtitle, rememberSubtitle }: Props & { initialSubtitle: SubtitleSelection; rememberSubtitle: (s: SubtitleSelection) => void }) {
  const [ownerScope] = useState(authScope)
  const videoRef = useRef<HTMLVideoElement>(null)
  const hlsRef = useRef<Hls | null>(null)
  const adaptiveFallback = useRef(new AdaptiveFallback())
  const observed = useRef<ReturnType<typeof observedRendition>>(undefined)
  const [observedHeight, setObservedHeight] = useState<number>()
  const [decision, setDecision] = useState<Decision | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [offset, setOffset] = useState(0) // seconds the HLS session starts at
  const [time, setTime] = useState(0)
  const [bufferedEnd, setBufferedEnd] = useState(0)
  const [paused, setPaused] = useState(true)
  const [audioIndex, setAudioIndex] = useState<number | undefined>(undefined)
  const [subtitleId, setSubtitleId] = useState<string>('')
  const [subtitleWork] = useState(() => {
    const work = new AutomaticSubtitles<SubtitleTrack>(fileId, automaticSubtitle)
    if (initialSubtitle.subtitleMode === 'off') work.choose(initialSubtitle.subtitleId ?? '')
    return work
  })
  const loadSequence = useRef(0)
  const loading = useRef(false)
  const active = useRef(true)
  const installedSession = useRef<string | undefined>(undefined)
  useEffect(() => {
    active.current = true
    subtitleWork.activate()
    return () => {
      active.current = false
      subtitleWork.close()
    }
  }, [subtitleWork])
  // Automatic needs a server at API level 3; the first load waits to know.
  const server = useQuery(serverQuery)
  const apiLevel = server.data?.apiLevel
  const levelKnown = server.data !== undefined || server.failureCount > 0
  // A pick in this player; until then, this browser's default.
  const [picked, setPicked] = useState<string | null>(null)
  const quality = useMemo(() => (picked ? qualityById(picked, apiLevel) : savedQuality(apiLevel)), [picked, apiLevel])
  const [ended, setEnded] = useState(false)
  const [creditsDismissed, setCreditsDismissed] = useState(false)
  const [askStill, setAskStill] = useState(false)
  // Segments the viewer seeked into: never skipped by themselves.
  const [seekedInto, setSeekedInto] = useState<Set<number>>(() => new Set())
  const prefs = useQuery(preferencesQuery).data
  // Set when moving on to the next episode, which reports this one as
  // finished; later position reports must not undo that.
  const finished = useRef(false)
  // Heartbeats for activity: the play this viewing reports as (the server
  // may hand over a new one), and the latest position, buffer and state.
  const playIdRef = useRef<string | undefined>(undefined)
  const posRef = useRef(0)
  const bufRef = useRef(0)
  const durationRef = useRef(0)
  const stateRef = useRef<PlayState>('buffering')
  const lastBeat = useRef(0)
  // What the heartbeats measure for Automatic quality (PLAN-AUTO-QUALITY.md
  // §3.1): stalls and startup, and hls.js's estimate once it has sampled a
  // fragment (before that it gives its default).
  const [meter] = useState(() => new StallMeter())
  const measuredHls = useRef<Hls | null>(null)
  const transferMeter = useRef(new TransferMeter())
  const pendingSwitch = useRef<{ generation: number; previous: Decision; restore: () => void; message: string } | null>(null)
  const failedGeneration = useRef<number | undefined>(undefined)
  const acceptedGeneration = useRef<number | undefined>(undefined)
  const acceptedMessage = useRef('')
  const handledGeneration = useRef(0)
  const autoRef = useRef(false)
  const atFloor = useRef(false)
  const floorNoted = useRef(false)
  const onDirective = useRef<(d: QualityDirective) => void>(() => undefined)
  // A short note when Automatic lowers the quality.
  const [note, setNote] = useState<string | null>(null)
  const [menuOpen, setMenuOpen] = useState(false)
  // Set when an admin stops the play: no more reports, and the message shows.
  const adminStopped = useRef(false)
  const [stopMessage, setStopMessage] = useState<string | null>(null)
  const [showControls, setShowControls] = useState(true)
  // Where the viewer is hovering or dragging on the seek bar.
  const [scrub, setScrub] = useState<ScrubPreview | null>(null)
  const hideTimer = useRef<number | undefined>(undefined)
  const navigate = useNavigate()
  const ass = useAssRenderer(videoRef)
  const qc = useQueryClient()
  const native = prefersNativeHls()
  const recoveryEnabled = (apiLevel ?? 0) >= 24 && !native && !recordingId
  const airplay = useAirPlay(videoRef)
  // Native text tracks (VOD renditions) arrive after the playlist loads.
  const [textTracks, setTextTracks] = useState(0)
  // Online subtitles are searched once the video is playing, never before:
  // pressing Play must not wait on OpenSubtitles.
  const [started, setStarted] = useState(false)
  const online = useQuery({ queryKey: ['subtitleSearch', fileId], queryFn: () => searchSubtitles(fileId), enabled: started && !recordingId, staleTime: 10 * 60_000, retry: false })
  const [fetching, setFetching] = useState<string | null>(null)
  const [subNote, setSubNote] = useState<string | null>(null)

  // A recording still being written grows from what the decision said was
  // written, on this device's monotonic clock (the server's times are only
  // compared with each other), until its planned end or the stream's end.
  // The stream's end needs no recording details, which a viewer without
  // recording rights can't read.
  const [recordedEnd, setRecordedEnd] = useState<number>()
  const [receivedAt, setReceivedAt] = useState(0)
  const [clock, setClock] = useState(() => performance.now())
  const growing = decision?.growing
  const sinceDecision = clock - receivedAt
  const endedMs = recordedEnd === undefined ? undefined : recordedEnd * 1000
  const recordingNow = stillRecording(growing, decision?.durationMs ?? 0, sinceDecision, endedMs)
  useEffect(() => {
    if (!recordingNow) return
    const timer = window.setInterval(() => setClock(performance.now()), 1000)
    return () => window.clearInterval(timer)
  }, [recordingNow])
  const duration = growingDurationMs(growing, decision?.durationMs ?? 0, sinceDecision, endedMs) / 1000
  const plannedDuration = recordingNow && growing?.startedAt && growing.endsAt ? Math.max(duration, (Date.parse(growing.endsAt) - Date.parse(growing.startedAt)) / 1000) : duration
  // A recording that is just starting can't be played for a few seconds.
  const [starting, setStarting] = useState(false)
  const trickplay = useMemo(() => usableTrickplay(decision?.trickplay), [decision?.trickplay])

  // Start (or restart) playback from a file position.
  const load = useCallback(
    (fromSeconds: number, opts: { audioIndex?: number; subtitleId?: string; quality?: Quality; resume?: boolean; switchGeneration?: number; switchTarget?: string; switchMessage?: string } = {}) => {
      const sequence = ++loadSequence.current
      const scope = authScope()
      loading.current = true
      if (!opts.switchGeneration) {
        pendingSwitch.current = null
        acceptedGeneration.current = undefined
        acceptedMessage.current = ''
      }
      const install = (d: Decision, from: number, token: string, onFatal: (reason: string) => void) => {
        const video = videoRef.current
        if (!video) return
        const src = token ? withToken(d.url, token) : d.url
        const whole = d.protocol === 'file' || d.playlist === 'vod'
        meter.asked(Date.now())
        hlsRef.current?.destroy()
        hlsRef.current = null
        measuredHls.current = null
        transferMeter.current.reset()
        observed.current = undefined
        setObservedHeight(undefined)
        if (whole && (d.protocol === 'file' || native)) {
          setOffset(0)
          video.src = src
          if (d.protocol === 'file') video.currentTime = from
          else if (from > 0) video.addEventListener('loadedmetadata', () => {
            if (active.current && sequence === loadSequence.current) video.currentTime = from
          }, { once: true })
        } else {
          setOffset(timelineOffset(d))
          if (Hls.isSupported() && !native) {
            const hls = new Hls({ ...fileHlsBuffer, startPosition: hlsStartPosition(d, from), liveDurationInfinity: false })
            hlsRef.current = hls
            hls.on(Hls.Events.ERROR, (_e, data) => {
              if (data.fatal && hlsRef.current === hls && active.current && sequence === loadSequence.current) onFatal(`Playback error: ${data.details}`)
            })
            const observe = () => {
              if (hlsRef.current !== hls || !active.current || sequence !== loadSequence.current || video.readyState < HTMLMediaElement.HAVE_CURRENT_DATA) return
              observed.current = observedRendition(d.adaptive, video.videoHeight)
              setObservedHeight(observed.current?.renditionHeight)
            }
            hls.on(Hls.Events.FRAG_CHANGED, observe)
            video.addEventListener('resize', observe)
            hls.on(Hls.Events.DESTROYING, () => video.removeEventListener('resize', observe))
            hls.on(Hls.Events.FRAG_BUFFERED, (_e, data) => {
              if (hlsRef.current === hls && data.frag.type === PlaylistLevelType.MAIN && data.frag.sn !== 'initSegment') measuredHls.current = hls
            })
            hls.on(Hls.Events.FRAG_LOADED, (_e, data) => {
              if (hlsRef.current !== hls || data.frag.type !== PlaylistLevelType.MAIN || data.frag.sn === 'initSegment') return
              const stats = data.part?.stats ?? data.frag.stats
              // Preparation and round-trip wait precede the first byte;
              // only the body transfer measures the available downlink.
              transferMeter.current.record(stats.loaded, stats.loading.first, stats.loading.end)
            })
            if (d.growing) hls.on(Hls.Events.LEVEL_LOADED, (_e, data) => {
              if (!data.details.live && active.current && sequence === loadSequence.current) setRecordedEnd(d.startMs / 1000 + data.details.totalduration)
            })
            hls.loadSource(d.url)
            hls.attachMedia(video)
          } else if (video.canPlayType('application/vnd.apple.mpegurl')) {
            video.src = src
          } else throw new Error('This browser cannot play HLS streams.')
        }
        void video.play().catch((e: unknown) => {
          if (e instanceof DOMException && e.name === 'NotAllowedError') meter.untimed()
        })
      }
      // prev is the decision being replaced. An upward switch keeps its
      // session available until the replacement plays its first frame.
      const run = async (from: number, o: typeof opts, prev: Decision | null, attempt = 0): Promise<void> => {
        setError(null)
        const upgrade = !!(prev?.auto && o.switchGeneration && o.switchTarget && higherStep(prev.auto.step, o.switchTarget))
        try {
          if (prev?.sessionId && !upgrade) void stopPlayback(prev.sessionId)
          const selection = subtitleWork.intent.request()
          const sub = o.subtitleId ?? selection.subtitleId
          const subTrack = prev?.subtitles.find((s) => s.id === sub)
          meter.asked(Date.now())
          const d = await startPlayback(fileId, {
            startMs: Math.floor(from * 1000),
            audioIndex: o.audioIndex ?? audioIndex,
            subtitleId: sub || undefined,
            subtitleMode: selection.subtitleMode,
            burnIn: subTrack?.burnInOnly,
            quality: o.quality ?? quality,
            adaptive: adaptiveFallback.current.enabled && requestAdaptive(apiLevel, (o.quality ?? quality).id, native) && !recordingId,
            autoRecovery: recoveryEnabled && (o.quality ?? quality).id === AUTO,
            switchGeneration: o.switchGeneration,
            playId: prev ? (playIdRef.current ?? prev.playId) : undefined,
            native,
            recordingId,
          })
          if (!active.current || sequence !== loadSequence.current || scope !== authScope()) {
            if (d.sessionId && d.sessionId !== installedSession.current) void stopPlayback(d.sessionId)
            return
          }
          const restore = () => {
            const pending = pendingSwitch.current
            if (!pending || pending.generation !== o.switchGeneration || !active.current || sequence !== loadSequence.current) return
            pendingSwitch.current = null
            failedGeneration.current = pending.generation
            acceptedGeneration.current = undefined
            installedSession.current = prev?.sessionId
            if (d.sessionId && d.sessionId !== prev?.sessionId) void stopPlayback(d.sessionId)
            if (prev) {
              setDecision(prev)
              setNote(`Still playing at ${stepName(prev.auto?.step)}. Quality will retry later.`)
              try { install(prev, from, '', (reason) => setError(reason)) }
              catch (e) { setError(e instanceof Error ? e.message : String(e)) }
            }
          }
          if (upgrade && prev && o.switchGeneration) {
            pendingSwitch.current = { generation: o.switchGeneration, previous: prev, restore, message: o.switchMessage ?? '' }
          }
          installedSession.current = d.sessionId
          playIdRef.current = d.playId
          subtitleWork.setPlay(d.playId)
          setSubtitleId(d.subtitleId ?? sub ?? '')
          setRecordedEnd(undefined)
          setReceivedAt(performance.now())
          setClock(performance.now())
          setStarting(false)
          setDecision(d)
          const whole = d.protocol === 'file' || d.playlist === 'vod'
          // No explicit start: pick up where this user left off.
          if (o.resume !== false && start === undefined && from === 0 && d.resumeMs > 5000) {
            if (whole) from = d.resumeMs / 1000
            else return run(d.resumeMs / 1000, { ...o, resume: false }, d)
          }
          // The Apple TV fetches what it AirPlays itself, without our cookie.
          const token = native ? await mediaToken() : ''
          if (!active.current || sequence !== loadSequence.current || scope !== authScope()) return
          install(d, from, token, (reason) => {
            const route = failureRoute(!!o.switchGeneration && pendingSwitch.current?.generation === o.switchGeneration, adaptiveFallback.current.enabled && !!d.adaptive)
            if (route === 'switch') restore()
            else if (route === 'family' && adaptiveFallback.current.take(d.adaptive)) {
              hlsRef.current?.destroy()
              hlsRef.current = null
              void run(posRef.current, { ...o, resume: false, switchGeneration: undefined }, d)
            } else {
              if (acceptedGeneration.current === o.switchGeneration) failedGeneration.current = o.switchGeneration
              acceptedGeneration.current = undefined
              setError(reason)
            }
          })
          acceptedGeneration.current = o.switchGeneration
          acceptedMessage.current = o.switchMessage ?? ''
        } catch (e) {
          if (!active.current || sequence !== loadSequence.current || scope !== authScope()) return
          // Taken over a moment ago, the recording's playback answers 409 until it starts.
          const wait = !prev && e instanceof RecordingNotReady ? startRetryMs(attempt, e.retryAfter) : undefined
          if (wait !== undefined) {
            setStarting(true)
            await new Promise((resolve) => window.setTimeout(resolve, wait))
            if (active.current && sequence === loadSequence.current && scope === authScope()) return await run(from, o, prev, attempt + 1)
            return
          }
          setStarting(false)
          if (upgrade && o.switchGeneration && prev) {
            if (pendingSwitch.current?.generation === o.switchGeneration) pendingSwitch.current.restore()
            else {
              failedGeneration.current = o.switchGeneration
              acceptedGeneration.current = undefined
              setNote(`Still playing at ${stepName(prev.auto?.step)}. Quality will retry later.`)
            }
          } else {
            if (o.switchGeneration) failedGeneration.current = o.switchGeneration
            acceptedGeneration.current = undefined
            setError(e instanceof Error ? e.message : String(e))
          }
        } finally {
          if (sequence === loadSequence.current) loading.current = false
        }
      }
      return run(fromSeconds, opts, decision)
    },
    [fileId, recordingId, audioIndex, quality, decision, start, native, meter, subtitleWork, recoveryEnabled, apiLevel],
  )
  const latestLoad = useRef(load)
  useEffect(() => { latestLoad.current = load }, [load])

  // Provider work follows the first playing frame and never delays deciding a stream.
  useEffect(() => {
    const playId = decision?.playId
    if (recordingId || !started || !playId || !prefs?.subtitles?.automaticDownload || prefs.subtitles.mode !== 'on' || subtitleId || (apiLevel ?? 0) < 18) return
    void subtitleWork.begin(playId).then((result) => {
      if (!result) return
      if (result.error) setSubNote(result.error)
      const track = result.track
      if (!track) return
      // Refetching the decision keeps VOD renditions, event cue shifts, burn-in,
      // quality and current position on their normal playback paths.
      void latestLoad.current(posRef.current, { subtitleId: track.id, resume: false })
      void qc.invalidateQueries({ queryKey: ['subtitleSearch', fileId] })
    }).catch(() => setSubNote('Couldn’t get an automatic subtitle. Video will keep playing; choose a subtitle in the player or try again later.'))
  }, [recordingId, started, decision?.playId, prefs?.subtitles?.automaticDownload, prefs?.subtitles?.mode, subtitleId, apiLevel, subtitleWork, qc, fileId])

  // Initial load: an explicit start, else the server's saved position (handled in load), else the beginning.
  useEffect(() => {
    if (!levelKnown) return
    const from = start ?? 0
    const handle = window.setTimeout(() => void load(from), 0)
    return () => {
      window.clearTimeout(handle)
      hlsRef.current?.destroy()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fileId, levelKnown])

  // Stop only on unmount. A negotiated upward switch owns two sessions until
  // the first frame is acknowledged or the old stream is restored.
  useEffect(() => {
    return () => {
      const ids = new Set([installedSession.current, pendingSwitch.current?.previous.sessionId])
      for (const id of ids) if (id) void stopPlayback(id)
    }
  }, [])

  useEffect(() => {
    const video = videoRef.current
    if (!video) return
    const onError = () => pendingSwitch.current?.restore()
    video.addEventListener('error', onError)
    return () => video.removeEventListener('error', onError)
  }, [])

  useEffect(() => {
    const reset = () => transferMeter.current.reset()
    window.addEventListener('online', reset)
    window.addEventListener('offline', reset)
    return () => { window.removeEventListener('online', reset); window.removeEventListener('offline', reset) }
  }, [])

  useEffect(() => {
    durationRef.current = duration
  }, [duration])

  // At Automatic the decision names its step; the lowest step that stalls
  // is as low as it goes.
  useEffect(() => {
    autoRef.current = !!decision?.auto
    atFloor.current = decision?.auto?.step === QUALITIES[QUALITIES.length - 1].id
  }, [decision])

  // Automatic's change reloads at this position without changing the saved
  // pick. Negotiated generations show their note after first-frame ack.
  const switching = useRef(false)
  useEffect(() => {
    onDirective.current = (d) => {
      if (switching.current || pendingSwitch.current || quality.id !== AUTO || !decision?.auto || d.quality === decision.auto.step) return
      const generation = recoveryEnabled ? switchGeneration(decision.auto.step, quality.id, d.quality, d.generation) : undefined
      if (d.generation && (!generation || generation <= handledGeneration.current)) return
      if (generation) handledGeneration.current = generation
      switching.current = true
      if (!generation) setNote(d.message || `Lowered to ${stepName(d.quality)} for your connection.`)
      void load(posRef.current, { resume: false, switchGeneration: generation, switchTarget: d.quality,
        switchMessage: d.message || `Quality changed to ${stepName(d.quality)}.` }).finally(() => (switching.current = false))
    }
  })
  useEffect(() => {
    if (!note) return
    const id = window.setTimeout(() => setNote(null), 6000)
    return () => window.clearTimeout(id)
  }, [note])

  // An admin stopped the play: stop the video and say so.
  const stopByAdmin = useCallback((message?: string) => {
    if (adminStopped.current) return
    adminStopped.current = true
    hlsRef.current?.destroy()
    hlsRef.current = null
    const v = videoRef.current
    if (v) {
      v.pause()
      v.removeAttribute('src')
      v.load()
    }
    setStopMessage(message ?? '')
  }, [])

  // A heartbeat, which also saves the position: at every state change,
  // every 10 s while playing and every 30 s otherwise (5 s at Automatic).
  // It carries what the player measured; the reply may stop the play or
  // lower its quality. After moving on to the next episode this one stays
  // finished, so its last report gives the end as the position.
  const beat = useCallback(
    (state: PlayState, force = false): Promise<void> => {
      const now = Date.now()
      if (ownerScope !== authScope() || adminStopped.current || !playIdRef.current) return Promise.resolve()
      if (!force && !beatDue(state, lastBeat.current, now, autoRef.current)) return Promise.resolve()
      lastBeat.current = now
      const dur = durationRef.current * 1000
      const pos = finished.current ? dur : posRef.current * 1000
      const hls = hlsRef.current
      const transfer = recoveryEnabled ? transferMeter.current.read(performance.now()) : undefined
      const throughputBps = recoveryEnabled ? transfer?.throughputBps : hls && measuredHls.current === hls ? hls.bandwidthEstimate : undefined
      const ack = state === 'playing' ? acceptedGeneration.current : undefined
      const failed = failedGeneration.current
      return reportProgress(fileId, pos, dur, { playId: playIdRef.current, state, bufferedMs: bufRef.current * 1000, throughputBps,
        ...observed.current, throughputAgeMs: transfer?.throughputAgeMs, switchGeneration: ack, switchFailedGeneration: failed, ...meter.report(now) }, recordingId).then((res) => {
        if (res && ack && acceptedGeneration.current === ack) {
          acceptedGeneration.current = undefined
          if (pendingSwitch.current?.generation === ack) pendingSwitch.current = null
          if (acceptedMessage.current) setNote(acceptedMessage.current)
          acceptedMessage.current = ''
        }
        if (res && failed && failedGeneration.current === failed) failedGeneration.current = undefined
        if (res?.playId) playIdRef.current = res.playId
        if (res?.directive?.action === 'stop') stopByAdmin(res.directive.message)
        else if (res?.directive?.action === 'quality') onDirective.current(res.directive)
      })
    },
    [fileId, recordingId, ownerScope, stopByAdmin, meter, recoveryEnabled],
  )

  // Time tracking and heartbeats.
  useEffect(() => {
    const video = videoRef.current
    if (!video) return
    const onTime = () => {
      const t = offset + video.currentTime
      const ahead = bufferedAhead(video.buffered, video.currentTime)
      posRef.current = t
      bufRef.current = ahead
      setTime(t)
      setBufferedEnd(t + ahead)
    }
    const report = (state: PlayState) => {
      stateRef.current = state
      void beat(state, true)
    }
    const onPlaying = () => {
      setStarted(true)
      meter.playing(Date.now())
      report('playing')
    }
    const onWaiting = () => {
      // A moment's wait at a hole in the buffer is not a stall of the link.
      if (!waitingAtJoint(video.buffered, video.currentTime) && meter.waiting(Date.now()) && autoRef.current && atFloor.current && !floorNoted.current) {
        floorNoted.current = true
        setNote('Your connection is too slow to play this smoothly.')
      }
      report('buffering')
    }
    const onSeeking = () => meter.excuse()
    // HAVE_FUTURE_DATA: the seek landed in what is buffered.
    const onSeeked = () => {
      if (!video.paused && video.readyState >= 3) meter.settled()
    }
    const onPlay = () => {
      meter.excuse()
      setPaused(false)
      setEnded(false)
    }
    const onPause = () => {
      meter.paused(Date.now())
      setPaused(true)
      if (!video.ended) report('paused')
    }
    const onEnded = () => {
      // A watcher who cannot manage recordings cannot query its details.
      // The event playlist's EOF still freezes the actual captured extent.
      if (recordingId && growing) {
        const end = Math.max(0, offset + (Number.isFinite(video.duration) ? video.duration : video.currentTime))
        setRecordedEnd(end)
        durationRef.current = end
      }
      setEnded(true)
      posRef.current = durationRef.current
      report('paused')
    }
    const tick = window.setInterval(() => void beat(stateRef.current), 1000)
    video.addEventListener('timeupdate', onTime)
    video.addEventListener('playing', onPlaying)
    video.addEventListener('waiting', onWaiting)
    video.addEventListener('seeking', onSeeking)
    video.addEventListener('seeked', onSeeked)
    video.addEventListener('play', onPlay)
    video.addEventListener('pause', onPause)
    video.addEventListener('ended', onEnded)
    return () => {
      window.clearInterval(tick)
      video.removeEventListener('timeupdate', onTime)
      video.removeEventListener('playing', onPlaying)
      video.removeEventListener('waiting', onWaiting)
      video.removeEventListener('seeking', onSeeking)
      video.removeEventListener('seeked', onSeeked)
      video.removeEventListener('play', onPlay)
      video.removeEventListener('pause', onPause)
      video.removeEventListener('ended', onEnded)
    }
  }, [offset, beat, meter, recordingId, growing])

  // Leaving the player ends the play; so does closing the page, reported as
  // it unloads. Positions changed, so rows and detail pages refetch, once
  // the last one is saved: asked sooner, the server could answer with the
  // position from before.
  useEffect(() => {
    const onHide = () => void beat('closed', true)
    window.addEventListener('pagehide', onHide)
    return () => {
      window.removeEventListener('pagehide', onHide)
      void beat('stopped', true).finally(() => void qc.invalidateQueries())
    }
  }, [beat, qc])

  // An admin's stop arrives on the event stream at once, or with the next heartbeat.
  useEffect(
    () =>
      onPlayStopped((e) => {
        if (e.playId === playIdRef.current && e.reason === 'terminated') stopByAdmin(e.message)
      }),
    [stopByAdmin],
  )

  // Subtitles: WebVTT through <track>; ASS through the WASM renderer; on
  // Safari's VOD path, the playlist's renditions in the decision's order,
  // which an Apple TV shows too.
  const nativeVod = native && decision?.protocol === 'hls' && decision.playlist === 'vod'
  useEffect(() => {
    const video = videoRef.current
    if (!video) return
    const onAdd = () => setTextTracks(video.textTracks.length)
    video.textTracks.addEventListener('addtrack', onAdd)
    return () => video.textTracks.removeEventListener('addtrack', onAdd)
  }, [])
  useEffect(() => {
    const video = videoRef.current
    if (!video || !decision) return
    const track = decision.subtitles.find((s) => s.id === subtitleId)
    for (const t of Array.from(video.textTracks)) t.mode = 'disabled'
    if (nativeVod) {
      const renditions = Array.from(video.textTracks).filter((t) => t.kind === 'subtitles' || t.kind === 'captions')
      const i = decision.subtitles.filter((s) => s.format !== 'image').findIndex((s) => s.id === subtitleId)
      if (i >= 0 && renditions[i]) renditions[i].mode = 'showing'
      return
    }
    if (track?.format === 'ass' && track.url) {
      ass.show(track.url)
      return
    }
    ass.hide()
    if (track?.format === 'webvtt') {
      const el = Array.from(video.querySelectorAll('track')).find((t) => t.dataset.id === track.id)
      if (el?.track) el.track.mode = 'showing'
    }
  }, [decision, subtitleId, ass, nativeVod, textTracks])

  // A subtitle picked from the list: a track, or an online result to download first.
  const chooseSubtitle = async (value: string) => {
    if (!decision) return
    subtitleWork.choose(value.startsWith('online:') ? subtitleId : value)
    rememberSubtitle(subtitleWork.intent.request())
    const revision = subtitleWork.intent.capture()
    const scope = authScope()
    setSubNote(null)
    const pick = (id: string) => {
      subtitleWork.choose(id)
      rememberSubtitle(subtitleWork.intent.request())
      setSubtitleId(id)
      const t = decision.subtitles.find((s) => s.id === id)
      // Image subtitles need a burn-in session; text ones switch instantly.
      if (t?.burnInOnly || decision.burnIn || loading.current) void load(time, { subtitleId: id })
    }
    if (!value.startsWith('online:')) return pick(value)
    const r = online.data?.results.find((x) => x.id === value.slice('online:'.length))
    if (!r) return
    if (r.trackId && decision.subtitles.some((s) => s.id === r.trackId)) return pick(r.trackId)
    setFetching(r.id)
    setSubNote(`Downloading ${languageName(r.language)}…`)
    try {
      const t = await downloadSubtitle(fileId, r.id)
      if (!active.current || !subtitleWork.intent.accepts(revision) || scope !== authScope()) return
      subtitleWork.choose(t.id)
      rememberSubtitle(subtitleWork.intent.request())
      void qc.invalidateQueries({ queryKey: ['subtitleSearch', fileId] })
      setSubNote(null)
      // Use the latest position/quality and let the server shift event cues and
      // rebuild native renditions, even if the stream changed during download.
      void latestLoad.current(posRef.current, { subtitleId: t.id, resume: false })
    } catch (e) {
      if (active.current && subtitleWork.intent.accepts(revision) && scope === authScope()) setSubNote(e instanceof Error ? e.message : String(e))
    } finally {
      setFetching(null)
    }
  }
  const found = online.data
  const showOnline = !!found?.available && !!decision
  const ownTracks = decision?.subtitles.filter((s) => s.source !== 'opensubtitles') ?? []
  const downloaded = decision?.subtitles.filter((s) => s.source === 'opensubtitles') ?? []
  const toDownload = (found?.results ?? []).filter((r) => !r.trackId || !downloaded.some((s) => s.id === r.trackId))

  // Next episode: from when the server says (the final credits, else
  // shortly before the end) the card counts down and moves on; "Watch
  // credits" hides it until the end.
  const next = decision?.nextEpisode
  const creditsAt = decision?.upNextMs ? decision.upNextMs / 1000 : 0
  const showNext = nextCardShowing({ hasNext: !!next, upNextMs: decision?.upNextMs ?? 0, time, ended, creditsDismissed, askingStill: askStill })
  const autoplay = prefs?.autoplayNext ?? true
  const goNext = useCallback(() => {
    if (!next) return
    finished.current = true
    void reportProgress(fileId, duration * 1000, duration * 1000, undefined, recordingId)
    void navigate({
      to: '/play/$fileId',
      params: { fileId: String(next.fileId) },
      // Back follows along to the episode now playing when it was on the show.
      search: { title: `${next.showTitle} · ${seCode(next.seasonNumber, next.episodeNumber)} ${next.title}`, back: backAfterNext(back, next) },
    })
  }, [next, fileId, recordingId, duration, navigate, back])
  // Moving on by itself: after a long run with nobody touching anything, ask first.
  const playNext = useCallback(() => {
    if (shouldAskStillWatching()) {
      setAskStill(true)
      return
    }
    noteAutoAdvance()
    goNext()
  }, [goNext])
  const playNextNow = () => {
    noteInput()
    goNext()
  }

  // The segment playing now and what the viewer's setting for its kind
  // says to do with it (lib/play/rules.ts).
  const segment = activeSegment(decision?.segments, time, duration, !!next)
  const mode = (segment ? (prefs?.skip[segment.kind] ?? 'ask') : 'none') as SkipMode
  const seekedIn = !!segment && seekedInto.has(segment.id)
  const { showButton: showSkip, autoAfterMs: autoAfter, autoNow } = skipPlan(segment, duration, mode, seekedIn, showNext)
  // A trailer has nothing to offer at its end: back to the movie.
  useEffect(() => {
    if (trailer && ended) void navigate({ href: back ?? '/' })
  }, [trailer, ended, back, navigate])

  const skipSegment = () => {
    if (!segment) return
    const end = segmentEnd(segment, duration)
    if (end < duration - 1) {
      seekTo(end, false)
      return
    }
    // Skipping to the end finishes the item: mark it watched and leave.
    finished.current = true
    void reportProgress(fileId, duration * 1000, duration * 1000, undefined, recordingId)
    void navigate({ href: back ?? '/' })
  }

  const seekTo = (target: number, byViewer = true) => {
    const video = videoRef.current
    if (!video || !decision) return
    target = growing ? growingSeekMs(target * 1000, duration * 1000) / 1000 : Math.max(0, Math.min(target, duration || target))
    if (target < creditsAt) setCreditsDismissed(false) // back before the credits: offer the card again
    if (byViewer) {
      noteInput()
      const inside = segmentsAt(decision.segments, target, duration)
      if (inside.length) setSeekedInto((prev) => new Set([...prev, ...inside]))
    }
    if (offset === 0 && (decision.protocol === 'file' || decision.playlist === 'vod')) {
      video.currentTime = target
      return
    }
    const local = target - offset
    const buffered = video.buffered.length ? video.buffered.end(video.buffered.length - 1) : 0
    if (local >= 0 && local <= buffered + 0.5) {
      video.currentTime = local
    } else {
      void load(target)
    }
  }

  useEffect(() => {
    if (autoNow) skipSegment()
    // Once per segment entered; skipSegment reads the latest state itself.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [autoNow, segment?.id])

  const togglePlay = () => {
    const v = videoRef.current
    if (!v) return
    if (v.paused) void v.play()
    else v.pause()
  }

  const poke = () => {
    setShowControls(true)
    window.clearTimeout(hideTimer.current)
    hideTimer.current = window.setTimeout(() => setShowControls(false), 3000)
  }

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.target instanceof HTMLSelectElement || (e.target instanceof Element && e.target.closest('[data-player-keys="off"]'))) return
      if (e.key === ' ' || e.key === 'k') {
        e.preventDefault()
        togglePlay()
      } else if (e.key === 'ArrowRight') seekTo(time + 10)
      else if (e.key === 'ArrowLeft') seekTo(time - 10)
      else if (e.key === 'f') void videoRef.current?.parentElement?.requestFullscreen?.()
      else if (e.key === 'Escape' && back) void navigate({ href: back })
      noteInput()
      poke()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  })

  // A manual Off choice can leave subtitleId empty and a paused position
  // unchanged. Observe the primitive mode as well as the selected track so
  // casting immediately inherits that choice rather than the earlier Auto.
  const subtitleMode = subtitleWork.intent.request().subtitleMode
  useEffect(() => {
    if (recordingId) return
    noteLocalPlay({ fileId, positionMs: time * 1000, audioIndex, subtitleId: subtitleId || undefined, subtitleMode, quality: quality.id, title })
  }, [fileId, recordingId, time, audioIndex, subtitleId, subtitleMode, quality, title])

  // An open menu, or the pointer on the seek bar, keeps the controls up.
  const controlsShown = showControls || menuOpen || !!scrub
  const goToNow = showGoToNow(recordingNow, time * 1000, duration * 1000)

  return (
    <div
      className="fixed inset-0 z-50 bg-black"
      onMouseMove={poke}
      onClick={() => {
        noteInput()
        poke()
      }}
    >
      <video ref={videoRef} className="h-full w-full" playsInline x-webkit-airplay={recordingId ? "deny" : "allow"} onDoubleClick={() => videoRef.current?.parentElement?.requestFullscreen?.()}>
        {!nativeVod && decision?.subtitles
          .filter((s) => s.format === 'webvtt' && s.url)
          .map((s) => (
            <track key={s.url} data-id={s.id} kind="subtitles" src={s.url} srcLang={s.language || 'und'} label={s.title || s.language || s.id} />
          ))}
      </video>

      {segment && showSkip && !showNext && (
        <SkipButton key={segment.id} label={SKIP_LABELS[segment.kind] ?? 'Skip'} controlsVisible={controlsShown} autoAfterMs={autoAfter} onSkip={skipSegment} />
      )}

      {showNext && next && (
        <NextEpisodeCard
          next={next}
          seconds={autoplay ? NEXT_COUNTDOWN : 0}
          hold={paused && !ended}
          onPlay={playNext}
          onPlayNow={playNextNow}
          onDismiss={() => setCreditsDismissed(true)}
        />
      )}

      {askStill && next && (
        <StillWatchingCard
          showTitle={next.showTitle}
          onContinue={() => {
            setAskStill(false)
            noteInput()
            goNext()
          }}
          onBack={() => void navigate({ href: back ?? '/' })}
        />
      )}

      {airplay.active && (
        <div className="pointer-events-none absolute inset-0 grid place-items-center bg-black">
          <div className="text-center">
            <p className="text-lg font-semibold text-white">Playing on AirPlay</p>
            <p className="mt-1 text-sm text-white/60">{title}</p>
          </div>
        </div>
      )}

      {starting && !error && (
        <div role="status" className="pointer-events-none absolute left-1/2 top-[72px] z-10 max-w-[calc(100vw-2rem)] -translate-x-1/2 rounded-full border border-white/20 bg-ground/90 px-3.5 py-2 text-center text-sm text-white shadow-xl">
          The recording is just starting…
        </div>
      )}

      {note && !error && !starting && (
        <div role="status" className="pointer-events-none absolute left-1/2 top-[72px] z-10 max-w-[calc(100vw-2rem)] -translate-x-1/2 rounded-full border border-white/20 bg-ground/90 px-3.5 py-2 text-center text-sm text-white shadow-xl">
          {note}
        </div>
      )}

      {error && (
        <div className="absolute inset-x-0 top-16 mx-auto max-w-lg rounded-md border border-red-900 bg-red-950/90 px-4 py-3 text-sm text-red-200">{error}</div>
      )}

      {stopMessage !== null && (
        <div className="absolute inset-0 z-30 grid place-items-center bg-black/85 p-6" role="alertdialog" aria-labelledby="stopped-title">
          <div className="max-w-md rounded-lg border border-line-2 bg-surface p-6 text-center">
            <h2 id="stopped-title" className="text-lg font-semibold">Playback stopped</h2>
            <p className="mt-2 text-sm text-neutral-300">
              {stopMessage ? <>The server&rsquo;s admin stopped this play: &ldquo;{stopMessage}&rdquo;</> : <>The server&rsquo;s admin stopped this play.</>}
            </p>
            <Link to="/" href={back} className="btn-primary mt-5 inline-block rounded-md px-4 py-2 text-sm font-semibold">
              Back
            </Link>
          </div>
        </div>
      )}

      <div className={`absolute inset-x-0 top-0 flex items-center gap-4 bg-gradient-to-b from-black/80 to-transparent p-4 transition-opacity ${controlsShown ? 'opacity-100' : 'opacity-0'}`}>
        <Link to="/" href={back} className="rounded-md border border-white/20 px-3 py-1.5 text-sm text-white hover:bg-white/10">
          ‹ Back
        </Link>
        <div className="min-w-0 flex-1 truncate text-sm text-white">{title}</div>
        {decision && (
          <span className="flex shrink-0 items-center gap-2 text-xs text-white/60" title={[decision.auto ? `Automatic: ${decision.auto.reason}` : '', ...decision.reasons].filter(Boolean).join('; ')}>
            {decision.auto && <span className="rounded-full border border-accent/70 px-1.5 text-[11px] font-semibold text-accent">Auto · {(decision.adaptive ? observedHeight ? `${observedHeight}p` : "Starting" : stepName(decision.auto.step))}</span>}
            <span>
              {decision.mode === 'direct' ? 'Direct play' : decision.mode === 'remux' ? 'Direct stream' : decision.mode === 'audio' ? 'Audio transcode' : 'Transcoding'}
              {!decision.adaptive && !decision.video.copy && decision.video.height ? ` · ${decision.video.height}p` : ''}
              {decision.video.toneMap ? ' · tone-mapped' : ''}
            </span>
          </span>
        )}
      </div>

      {/* While the seek bar shows a thumbnail, it sits above Skip and the next episode card. */}
      <div className={`absolute inset-x-0 bottom-0 bg-gradient-to-t from-black/90 to-transparent p-4 transition-opacity ${scrub ? 'z-[15]' : ''} ${controlsShown ? 'opacity-100' : 'opacity-0'}`}>
        {/* Only while recording: when it ends the label goes and the play carries on. */}
        {recordingNow && <div className="mb-2 flex items-center justify-between gap-3"><span role="status" className="rounded border border-red-500/60 bg-red-500/10 px-2 py-1 text-xs font-bold text-red-300">● Recording</span>{goToNow && <button type="button" onClick={(e) => { e.stopPropagation(); seekTo(growingSeekMs(duration * 1000, duration * 1000) / 1000) }} className="ml-auto min-h-10 rounded-md border border-white/20 px-3 text-sm">Go to now</button>}</div>}
        <Scrubber time={time} duration={plannedDuration} available={growing ? duration : undefined} bufferedEnd={bufferedEnd} trickplay={trickplay} controlsShown={controlsShown} onSeek={seekTo} onPreview={setScrub} />
        <div className="mt-3 flex flex-wrap items-center gap-3 text-sm text-white">
          <button type="button" onClick={(e) => { e.stopPropagation(); togglePlay() }} className="min-h-[40px] min-w-[44px] rounded-md bg-white/10 px-3 hover:bg-white/20">
            {paused ? '▶' : '❚❚'}
          </button>
          <span className="tabular-nums text-white/80">
            {formatDuration((scrub?.dragging ? scrub.seconds : time) * 1000)} / {formatDuration(duration * 1000)}
          </span>
          <div className="ml-auto flex flex-wrap items-center gap-2">
            {decision && decision.audioTracks.length > 1 && (
              <select
                value={audioIndex ?? decision.audio.index}
                onChange={(e) => {
                  const idx = Number(e.target.value)
                  setAudioIndex(idx)
                  void load(time, { audioIndex: idx })
                }}
                className="min-h-[36px] rounded-md border border-white/20 bg-black/60 px-2 text-sm"
              >
                {decision.audioTracks.map((a) => (
                  <option key={a.index} value={a.index}>
                    {[a.language?.toUpperCase(), a.codec.toUpperCase(), a.channels ? `${a.channels}ch` : '', a.title].filter(Boolean).join(' · ')}
                  </option>
                ))}
              </select>
            )}
            {decision && ((apiLevel ?? 0) >= 18 || decision.subtitles.length > 0 || showOnline) && (
              <select
                value={subtitleWork.intent.automatic && !subtitleId ? '__account' : subtitleId}
                aria-label="Subtitles"
                onChange={(e) => void chooseSubtitle(e.target.value)}
                className="min-h-[36px] max-w-[18rem] rounded-md border border-white/20 bg-black/60 px-2 text-sm"
              >
                {subtitleWork.intent.automatic && !subtitleId && <option value="__account" disabled>Using your subtitle settings</option>}
                <option value="">Subtitles off</option>
                {ownTracks.length > 0 && (
                  <optgroup label={showOnline ? 'In this file' : 'Subtitles'}>
                    {ownTracks.map((s) => (
                      <option key={s.id} value={s.id}>
                        {trackLabel(s)}
                      </option>
                    ))}
                  </optgroup>
                )}
                {showOnline && found && (
                  <optgroup label="OpenSubtitles">
                    {downloaded.map((s) => (
                      <option key={s.id} value={s.id}>
                        {trackLabel(s)}
                      </option>
                    ))}
                    {toDownload.map((r) => (
                      <option key={r.id} value={`online:${r.id}`} disabled={!found.canDownload || found.quota.remaining === 0 || fetching !== null}>
                        {onlineLabel(r)}
                      </option>
                    ))}
                    {found.error && <option disabled>Search failed</option>}
                    {!found.error && downloaded.length === 0 && toDownload.length === 0 && <option disabled>None found ({found.languages.join(', ')})</option>}
                    {!found.canDownload && toDownload.length > 0 && <option disabled>Only admins can download</option>}
                    {found.canDownload && found.quota.remaining === 0 && <option disabled>No downloads left today</option>}
                  </optgroup>
                )}
              </select>
            )}
            {showOnline && found && (
              <a href="https://opensubtitles.com" target="_blank" rel="noreferrer" onClick={(e) => e.stopPropagation()} className="text-[11px] text-white/50 hover:text-white/80">
                {found.attribution}
              </a>
            )}
            {subNote && <span className="text-xs text-white/70">{subNote}</span>}
            <QualityMenu
              value={quality}
              options={qualitiesFor(apiLevel)}
              autoStep={decision?.adaptive ? observedHeight ? String(observedHeight) : undefined : decision?.auto?.step}
              onOpenChange={setMenuOpen}
              onPick={(q: Quality) => {
                setPicked(q.id)
                saveQuality(q)
                setNote(null)
                void load(time, { quality: q, resume: false })
              }}
            />
            {airplay.available && !recordingId && (
              <button
                type="button"
                onClick={(e) => { e.stopPropagation(); airplay.pick() }}
                className={`min-h-[40px] min-w-[44px] rounded-md px-3 hover:bg-white/20 ${airplay.active ? 'bg-accent text-black' : 'bg-white/10'}`}
                aria-label="AirPlay"
                title="AirPlay"
              >
                <AirPlayIcon />
              </button>
            )}
            <button
              type="button"
              onClick={(e) => { e.stopPropagation(); void videoRef.current?.parentElement?.requestFullscreen?.() }}
              className="min-h-[40px] min-w-[44px] rounded-md bg-white/10 px-3 hover:bg-white/20"
              aria-label="Fullscreen"
            >
              ⛶
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}

function AirPlayIcon() {
  return (
    <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinejoin="round" aria-hidden="true">
      <path d="M6 17H4a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1h16a1 1 0 0 1 1 1v11a1 1 0 0 1-1 1h-2" />
      <path d="M12 14l5 6H7z" fill="currentColor" stroke="none" />
    </svg>
  )
}

const short = (s: string, n = 40) => (s.length > n ? `${s.slice(0, n - 1)}…` : s)

function trackLabel(s: SubtitleTrack) {
  if (s.source === 'opensubtitles') return [languageName(s.language), s.hearingImpaired ? 'HI' : '', s.forced ? 'forced' : '', s.release ? short(s.release) : ''].filter(Boolean).join(' · ')
  return [s.language?.toUpperCase() || 'und', s.title, s.forced ? 'forced' : '', s.external ? 'external' : '', s.burnInOnly ? 'burn-in' : ''].filter(Boolean).join(' · ')
}

function onlineLabel(r: OnlineSubtitle) {
  return [`↓ ${languageName(r.language)}`, r.hashMatch ? 'matches this file' : '', r.hearingImpaired ? 'HI' : '', r.forced ? 'forced' : '', r.aiTranslated ? 'AI translated' : '', short(r.release)]
    .filter(Boolean)
    .join(' · ')
}
