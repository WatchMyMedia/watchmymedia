import type { Client } from 'openapi-fetch'
import type { framework } from 'chromecast-caf-receiver'
import type { components, paths } from '../src/api/schema'
import { FEATURE_LIVE, FEATURE_LIVE_AUTO, NAMESPACE, PROTOCOL_VERSION, contentId, liveContentId, type CommandMessage, type LiveInfo, type LoadData, type StateMessage } from '../src/cast/protocol'
import type { LiveChannel, LiveQuality } from '../src/api/liveTV'
import { liveQuality, liveViewingPrompt, nextLiveChannel } from '../src/lib/liveTV'
import { LiveTVController } from '../src/lib/liveTVController'
import { AUTO, QUALITIES, applyQuality, qualityById } from '../src/lib/quality'
import { TransferMeter, higherStep, switchGeneration } from '../src/lib/play/recovery'
import { receiverBuffering } from './buffering'
import { interceptSeek, rememberedPosition, seekStaysInPlayer } from './seeking'
import { castRecoveryAvailable, recordCastTransfer, recordLiveCastTransfer } from './recovery'
import { NEXT_COUNTDOWN, StallMeter, activeSegment, beatDue, bufferedAhead, episodeLine, nextCardShowing, segmentEnd, segmentsAt, skipPlan, type BeatState, type SkipMode } from '../src/lib/play/rules'
import { noteAutoAdvance, noteInput, shouldAskStillWatching } from '../src/lib/stillWatching'
import { ADAPTIVE_LEVEL, adaptiveRestrictions, observedRendition, requestAdaptive } from '../src/lib/play/adaptive'
import { castCaps, castProfile, hlsCodecDefaults } from './profile'
import { render } from './ui'
import { publicCastMessage } from './security'
import { castClient, castServerKey, type CastCredential } from './server'
import { manualSubtitleSelection, nextEpisodeSubtitles, startingSubtitle, subtitleRequest } from './subtitles'
import { BANNER_MS, HEARTBEAT_MS, LIVE_CAST_LEVEL, LiveRefused, START_TIMEOUT_MS, castLiveTransport, liveInfo, nowEnds, receiverFailure, refusedLiveInfo, withCurrentToken, type ReceiverFailure } from './live'
import { nowAndNext } from '../src/lib/liveGuide'
import type { LiveScreen } from './ui'
import './style.css'

/**
 * The WatchMyMedia Cast receiver (PLAN-CAST.md §4). A sender's LOAD names
 * a server and a file and carries a cast token; from there this page runs
 * the play itself, as the web player does (PLAN-CLIENTS.md §2.3): it asks
 * the server how to play with this device's profile, reports heartbeats,
 * and runs Skip, Next Episode and Still watching. Senders are remotes: they
 * read our state messages and send commands (protocol.ts).
 */

type Decision = components['schemas']['Decision']
type Preferences = components['schemas']['Preferences']
type Messages = typeof cast.framework.messages

const ctx = cast.framework.CastReceiverContext.getInstance()
const pm = ctx.getPlayerManager()
const M: Messages = cast.framework.messages
const VERSION = '1'

/** An interceptor's answer that drops the message: CAF takes null for it. */
const drop = <T,>() => null as unknown as T

// How long a server address gets to answer GET /api/server.
const PROBE_TIMEOUT_MS = 3000

type Play = {
  adaptiveDisabled: boolean
  load: LoadData
  /** The sender's initial opt-in survives manual choices for this episode. */
  initialSubtitleMode?: LoadData['subtitleMode']
  base: string
  api: Client<paths>
  credential: CastCredential
  decision: Decision
  fileId: number
  playId: string
  /** Seconds: file position = offset + player time (event playlists). */
  offset: number
  lastPositionS: number
  title: string
  subtitle?: string
  ended: boolean
  creditsDismissed: boolean
  askingStill: boolean
  seekedInto: Set<number>
  autoSkipped: Set<number>
  /** When the current segment's auto-delay skip is due. */
  skipDueAt?: { id: number; at: number }
  /** When the Next Episode countdown ends; pushed back while paused. */
  nextDueAt?: number
  lastBeat: number
  beatState: BeatState
  /** An admin stopped it: no more reports. */
  adminStopped: boolean
  /** Reported over (stopped or closed) and its session let go. */
  over: boolean
  /** The quality played: auto plays as original on a server that cannot do it. */
  quality: string
  audioIndex?: number
  subtitleId?: string
  /** Text subtitle ID → the player's track ID. */
  trackIds: Map<string, number>
  /** Stalls and startup for the heartbeats, across the play's reloads. */
  meter: StallMeter
  /** Set once this stream first played: from then on Shaka's estimate is measured, not its default. */
  sampled: boolean
  transfer: TransferMeter
  recovery: boolean
  acceptedGeneration?: number
  failedGeneration?: number
  handledGeneration: number
  /** A step down is reloading. */
  switching: boolean
  /** The connection-too-slow note was shown. */
  floorNoted: boolean
}

let play: Play | null = null
let phase: StateMessage['phase'] = 'idle'
let errorText: string | undefined
let stopMessage: string | undefined
/** The player's error codes for this play, if it reported any. */
let playerError: string | undefined
let prefs: Preferences | undefined
let renewTimer: number | undefined
/** A reload of the same play (seek outside an event playlist, track or quality change). */
let continuing: { adaptiveDisabled?: boolean; fileId: number; playId: string; fromMs: number; generation?: number; target?: string; message?: string } | null = null
let pendingReload: { previous: Play; generation: number; target: string; message: string; fromMs: number } | null = null
let restoring: Play | null = null
let suppressFailedLoadEnd = false
let loadSequence = 0
class StaleLoad extends Error {}
const bases = new Map<string, string>()
/** Each server's apiLevel, from the same probe. */
const levels = new Map<string, number>()
/** A short note on screen (Automatic lowered the quality), and when it goes. */
let note: { text: string; until: number } | undefined

if (typeof PerformanceObserver !== 'undefined' && PerformanceObserver.supportedEntryTypes.includes('resource')) {
  const observer = new PerformanceObserver((list) => {
    const p = play
    const l = live
    for (const entry of list.getEntries()) {
      if (p?.recovery) {
        const ids = p.decision.adaptive?.renditions.map((r) => r.sessionId) ?? [p.decision.sessionId]
        for (const id of ids) recordCastTransfer(p.transfer, p.base, id, entry as PerformanceResourceTiming)
      }
      if (l?.automaticEnabled) recordLiveCastTransfer(l.transfer, l.base, l.switchURL ?? l.controller.getSnapshot().session?.url, entry as PerformanceResourceTiming)
    }
  })
  observer.observe({ type: 'resource', buffered: false })
}

// ---------------------------------------------------------------- server

/** The first address in order that answers as this server. */
async function pickServer(server: LoadData['server']): Promise<string> {
  const key = castServerKey(server)
  const known = bases.get(key)
  if (known) return known
  const urls = server.urls.map((u) => u.replace(/\/+$/, ''))
  const probes = urls.map(async (u) => {
    const ac = new AbortController()
    const t = window.setTimeout(() => ac.abort(), PROBE_TIMEOUT_MS)
    try {
      const res = await fetch(`${u}/api/server`, { signal: ac.signal })
      const body = (await res.json()) as { id?: string; apiLevel?: number }
      if (!res.ok || body.id !== server.id) return false
      levels.set(server.id, body.apiLevel ?? 0)
      return true
    } catch {
      return false
    } finally {
      window.clearTimeout(t)
    }
  })
  // The earliest address in order that answers wins, as soon as every one
  // before it has failed: an unreachable address later in the list (a
  // tailnet name) does not hold the cast up for its timeout.
  const results: (boolean | undefined)[] = urls.map(() => undefined)
  const i = await new Promise<number>((resolve) => {
    const settle = () => {
      for (let j = 0; j < results.length; j++) {
        if (results[j] === undefined) return
        if (results[j]) return resolve(j)
      }
      resolve(-1)
    }
    if (!urls.length) resolve(-1)
    probes.forEach((p, j) =>
      void p.then((ok) => {
        results[j] = ok
        settle()
      }),
    )
  })
  if (i < 0) throw new Error(`This TV can't reach the server (tried ${urls.join(', ') || 'no addresses'}).`)
  bases.set(key, urls[i])
  return urls[i]
}

const withToken = (base: string, url: string, token: string) => `${base}${url}${url.includes('?') ? '&' : '?'}access_token=${encodeURIComponent(token)}`

function deviceId(fallback?: string): string {
  const KEY = 'watchmymedia.cast.deviceId'
  try {
    const saved = localStorage.getItem(KEY)
    if (saved) return saved
    const id = fallback || crypto.randomUUID()
    localStorage.setItem(KEY, id)
    return id
  } catch {
    return fallback || 'cast'
  }
}

/** Renews the cast token at half-life while something is playing: a file or a channel. */
function scheduleRenewal(expiresAt?: string) {
  window.clearTimeout(renewTimer)
  const exp = expiresAt ? Date.parse(expiresAt) : Date.now() + 12 * 3600_000
  renewTimer = window.setTimeout(async () => {
    const current = play ?? live
    if (!current) return
    const res = await current.api.POST('/api/auth/cast-token').catch(() => undefined)
    if (res?.data && (play ?? live) === current) {
      current.credential.token = res.data.token
      current.load.token = res.data.token
      scheduleRenewal(res.data.expiresAt)
    }
  }, Math.max(60_000, (exp - Date.now()) / 2))
}

// ---------------------------------------------------------------- loading

async function decide(api: Client<paths>, load: LoadData & { fileId: number }, extra: { startMs?: number; playId?: string; burnIn?: boolean; switchGeneration?: number; adaptiveDisabled?: boolean }): Promise<Decision> {
  const level = levels.get(load.server.id) ?? 0
  // Automatic sends the profile uncapped and lets the server pick the step.
  const quality = qualityById(load.quality, level)
  const res = await api.POST('/api/files/{id}/playback', {
    params: { path: { id: load.fileId } },
    body: {
      profile: applyQuality(castProfile(ctx, level), quality),
      quality: quality.id,
      ...(requestAdaptive(level, quality.id) && !extra.adaptiveDisabled ? { adaptive: true } : {}),
      autoRecovery: castRecoveryAvailable(level) && quality.id === AUTO,
      switchGeneration: extra.switchGeneration,
      playlist: 'vod',
      audioIndex: load.audioIndex ?? undefined,
      ...subtitleRequest(load, level),
      burnIn: extra.burnIn,
      startMs: extra.startMs,
      playId: extra.playId,
      client: {
        deviceId: deviceId(load.device?.id),
        name: load.device?.name || 'Chromecast',
        product: 'WatchMyMedia Cast',
        platform: 'cast',
        version: VERSION,
      },
    },
  })
  if (!res.data) {
    const detail = (res.error as { detail?: string } | undefined)?.detail
    throw new Error(detail || `The server refused (${res.response.status}).`)
  }
  return res.data
}

/** Fills a LOAD request in from the server's decision. */
async function prepare(req: framework.messages.LoadRequestData, sequence: number): Promise<framework.messages.LoadRequestData> {
  const load = (req.media.customData ?? req.customData) as LoadData | undefined
  if (!load || typeof load.fileId !== 'number' || !load.token || !load.server?.id) throw new Error('This cast came without a WatchMyMedia server or file.')
  const fileLoad = load as LoadData & { fileId: number }
  const restore = restoring?.fileId === load.fileId ? restoring : null
  restoring = null
  const cont = continuing?.fileId === load.fileId ? continuing : null
  continuing = null
  const prev = play
  if (!cont) pendingReload = null
  if (prev && !cont) endPlay(prev, 'stopped')
  if (live) stopLive(live)
  const credential = { token: load.token }
  phase = 'loading'
  errorText = stopMessage = playerError = undefined
  const base = await pickServer(load.server)
  if (sequence !== loadSequence) throw new StaleLoad()
  const api = castClient(base, credential)
  title = metaTitle(req) ?? title
  broadcast()
  if (!prefs || !cont) prefs = (await api.GET('/api/me/preferences')).data ?? prefs
  const requestedSub = load.subtitleId ?? undefined
  const subTrack = cont && prev ? prev.decision.subtitles.find((s) => s.id === requestedSub) : undefined
  let fromMs = cont ? cont.fromMs : (load.startMs ?? undefined)
  const meter = cont && prev ? prev.meter : new StallMeter()
  meter.asked(Date.now())
  if (sequence !== loadSequence) throw new StaleLoad()
  let d = restore?.decision ?? await decide(api, fileLoad, { startMs: fromMs, playId: cont?.playId, burnIn: subTrack?.burnInOnly, switchGeneration: cont?.generation, adaptiveDisabled: cont?.adaptiveDisabled })
  if (sequence !== loadSequence) {
    if (!restore && d.sessionId) void api.DELETE('/api/playback/{session}', { params: { path: { session: d.sessionId } } }).catch(() => undefined)
    throw new StaleLoad()
  }
  // No explicit start: pick up where this user left off.
  if (fromMs === undefined && d.resumeMs > 5000) {
    fromMs = d.resumeMs
    if (d.protocol === 'hls' && d.playlist !== 'vod') {
      d = await decide(api, fileLoad, { startMs: fromMs, playId: d.playId })
      if (sequence !== loadSequence) {
        if (d.sessionId) void api.DELETE('/api/playback/{session}', { params: { path: { session: d.sessionId } } }).catch(() => undefined)
        throw new StaleLoad()
      }
    }
  }
  const sub = startingSubtitle(load, d, levels.get(load.server.id) ?? 0)
  const vod = d.protocol === 'file' || d.playlist === 'vod'
  const offset = vod ? 0 : d.startMs / 1000
  const media = req.media
  media.contentId = contentId(load.fileId)
  media.contentUrl = withToken(base, d.url, credential.token)
  media.contentType = d.mimeType || 'video/mp4'
  media.streamType = M.StreamType.BUFFERED
  media.duration = d.durationMs / 1000
  if (d.protocol === 'hls') {
    media.hlsSegmentFormat = M.HlsSegmentFormat.FMP4
    media.hlsVideoSegmentFormat = M.HlsVideoSegmentFormat.FMP4
  }
  // VOD playlists carry the text subtitles as renditions; otherwise they are
  // side-loaded WebVTT (ASS is converted by asking for .vtt).
  const trackIds = new Map<string, number>()
  if (d.playlist !== 'vod') {
    const tracks: framework.messages.Track[] = []
    d.subtitles.forEach((s, i) => {
      if (s.format === 'image' || !s.url) return
      const t = new M.Track(i + 1, M.TrackType.TEXT)
      t.trackContentId = withToken(base, s.url.replace(/\.ass(?=\?|$)/, '.vtt'), credential.token)
      t.trackContentType = 'text/vtt'
      t.subtype = M.TextTrackType.SUBTITLES
      t.name = s.title || s.language || s.id
      t.language = s.language || 'und'
      tracks.push(t)
      trackIds.set(s.id, i + 1)
    })
    media.tracks = tracks
    const active = sub ? trackIds.get(sub) : undefined
    req.activeTrackIds = active ? [active] : []
  }
  if (!media.metadata) {
    const meta = new M.GenericMediaMetadata()
    meta.title = title ?? ''
    media.metadata = meta
  }
  req.currentTime = vod && fromMs !== undefined ? fromMs / 1000 : 0
  req.autoplay = req.autoplay ?? true
  // Keep credentials only in our private play context. Legacy LOADs remain accepted.
  media.customData = { v: load.v, fileId: load.fileId }
  req.customData = undefined
  play = {
    adaptiveDisabled: cont?.adaptiveDisabled ?? false,
    load,
    initialSubtitleMode: cont && prev ? prev.initialSubtitleMode : load.subtitleMode,
    base,
    api,
    credential,
    decision: d,
    fileId: load.fileId,
    playId: d.playId,
    offset,
    lastPositionS: (fromMs ?? d.startMs) / 1000,
    title: title ?? '',
    subtitle: (media.metadata as { subtitle?: string }).subtitle,
    ended: false,
    creditsDismissed: cont && prev ? prev.creditsDismissed : false,
    askingStill: false,
    seekedInto: cont && prev ? prev.seekedInto : new Set(),
    autoSkipped: cont && prev ? prev.autoSkipped : new Set(),
    lastBeat: 0,
    beatState: 'buffering',
    adminStopped: false,
    over: false,
    quality: qualityById(load.quality, levels.get(load.server.id)).id,
    audioIndex: load.audioIndex ?? d.audio.index,
    subtitleId: sub,
    trackIds,
    meter,
    sampled: false,
    transfer: new TransferMeter(),
    recovery: castRecoveryAvailable(levels.get(load.server.id) ?? 0) && qualityById(load.quality, levels.get(load.server.id)).id === AUTO,
    acceptedGeneration: restore ? undefined : cont?.generation,
    handledGeneration: cont && prev ? prev.handledGeneration : 0,
    switching: false,
    floorNoted: cont && prev ? prev.floorNoted : false,
  }
  // The old stream played on while asking: what it did is not this one's.
  meter.asked(Date.now())
  scheduleRenewal(load.tokenExpiresAt)
  return req
}

let title: string | undefined
function metaTitle(req: framework.messages.LoadRequestData): string | undefined {
  return (req.media.metadata as { title?: string } | undefined)?.title || undefined
}

/** Reloads the current play at the current position with changed tracks or quality. */
function reload(change: Partial<Pick<LoadData, 'audioIndex' | 'subtitleId' | 'subtitleMode' | 'quality'>>, atMs = filePositionMs(), qualitySwitch?: { generation: number; target: string; message: string }) {
  if (!play) return
  const load: LoadData = { ...play.load, ...change, startMs: Math.floor(atMs) }
  continuing = { adaptiveDisabled: play.adaptiveDisabled, fileId: play.fileId, playId: play.playId, fromMs: Math.floor(atMs), generation: qualitySwitch?.generation,
    target: qualitySwitch?.target, message: qualitySwitch?.message }
  pendingReload = qualitySwitch ? { previous: play, generation: qualitySwitch.generation, target: qualitySwitch.target,
    message: qualitySwitch.message, fromMs: Math.floor(atMs) } : null
  const req = new M.LoadRequestData()
  req.media = new M.MediaInformation()
  req.media.contentId = contentId(play.fileId)
  req.media.customData = load
  const meta = new M.GenericMediaMetadata()
  meta.title = play.title
  if (play.subtitle) meta.subtitle = play.subtitle
  req.media.metadata = meta
  req.autoplay = pm.getPlayerState() !== M.PlayerState.PAUSED
  void pm.load(req).catch(() => { if (pendingReload?.generation === qualitySwitch?.generation) failReload() })
}

/** Reports a failed negotiated reload and restores the retained old source
 * when the switch was upward. This never ends the logical play. */
function failReload(): boolean {
  const pending = pendingReload
  if (!pending) return false
  pendingReload = null
  const old = pending.previous
  old.switching = false
  old.failedGeneration = pending.generation
  void old.api.PUT('/api/files/{id}/progress', { params: { path: { id: old.fileId } }, body: {
    playId: old.playId, positionMs: pending.fromMs, durationMs: old.decision.durationMs, state: 'buffering',
    switchFailedGeneration: pending.generation,
  } }).then((res) => { if (res.data && old.failedGeneration === pending.generation) old.failedGeneration = undefined }).catch(() => undefined)
  if (!old.decision.auto || !higherStep(old.decision.auto.step, pending.target)) return false
  const replaced = play
  if (replaced && replaced !== old && replaced.decision.sessionId && replaced.decision.sessionId !== old.decision.sessionId) {
    void replaced.api.DELETE('/api/playback/{session}', { params: { path: { session: replaced.decision.sessionId } } }).catch(() => undefined)
  }
  play = old
  restoring = old
  suppressFailedLoadEnd = true
  continuing = { fileId: old.fileId, playId: old.playId, fromMs: pending.fromMs }
  phase = 'loading'
  const req = new M.LoadRequestData()
  req.media = new M.MediaInformation()
  req.media.contentId = contentId(old.fileId)
  req.media.customData = { ...old.load, startMs: pending.fromMs }
  const meta = new M.GenericMediaMetadata()
  meta.title = old.title
  if (old.subtitle) meta.subtitle = old.subtitle
  req.media.metadata = meta
  req.autoplay = true
  showNote(`Still playing at ${old.decision.auto.step === 'original' ? 'Original' : `${old.decision.auto.step}p`}. Quality will retry later.`)
  window.setTimeout(() => { void pm.load(req).catch(() => undefined) }, 0)
  return true
}

/** Plays another file of the same server for the same person: the next episode. */
function playFile(fileId: number, name: string, subtitle?: string) {
  if (!play) return
  const load: LoadData = { ...nextEpisodeSubtitles(play.load, play.initialSubtitleMode), fileId, startMs: null, audioIndex: null }
  const req = new M.LoadRequestData()
  req.media = new M.MediaInformation()
  req.media.contentId = contentId(fileId)
  req.media.customData = load
  const meta = new M.GenericMediaMetadata()
  meta.title = name
  if (subtitle) meta.subtitle = subtitle
  req.media.metadata = meta
  req.autoplay = true
  title = name
  void pm.load(req)
}

// ---------------------------------------------------------------- the play

const durationS = () => (play ? play.decision.durationMs / 1000 : 0)
const filePositionS = () => {
  if (!play) return 0
  play.lastPositionS = rememberedPosition(play.lastPositionS, play.offset + pm.getCurrentTimeSec(), pm.getPlayerState())
  return play.lastPositionS
}
const filePositionMs = () => filePositionS() * 1000

/** The video CAF plays into, inside its player element. */
function mediaElement(): HTMLMediaElement | null {
  return document.querySelector('cast-media-player')?.shadowRoot?.querySelector('video') ?? document.querySelector('video')
}

/** Milliseconds buffered ahead of the position; undefined when the video can't be read. */
function bufferedMs(): number | undefined {
  const v = mediaElement()
  return v ? Math.round(bufferedAhead(v.buffered, v.currentTime) * 1000) : undefined
}

/** The offered rendition on screen, once the play has shown a frame. */
function currentRendition(offer: Decision["adaptive"]) {
  if (play && !play.sampled || live && !live.played) return undefined
  // The decoded picture, as the web player reports it: Shaka's statistics
  // name the variant being fetched, which leads what is on screen.
  const video = mediaElement()
  const height = video instanceof HTMLVideoElement && video.videoHeight ? video.videoHeight : undefined
  try { return observedRendition(offer, height ?? pm.getStats().height) } catch { return undefined }
}

/**
 * Shaka's estimate of the link, for HLS. CAF offers it only under Shaka;
 * before the stream's first frame it is Shaka's default, not a measurement.
 */
function throughputBps(p: Play): number | undefined {
  if (p.decision.protocol !== 'hls' || !p.sampled) return undefined
  try {
    const bw = pm.getStats().estimatedBandwidth
    return bw && Number.isFinite(bw) ? Math.round(bw) : undefined
  } catch {
    return undefined
  }
}

function beat(state: BeatState, force = false) {
  const p = play
  if (!p || p.adminStopped || p.over) return
  const now = Date.now()
  if (!force && !beatDue(state, p.lastBeat, now, !!p.decision.auto)) return
  p.lastBeat = now
  p.beatState = state
  const dur = p.decision.durationMs
  const pos = p.ended ? dur : Math.min(dur, filePositionMs())
  const transfer = p.recovery ? p.transfer.read(performance.now()) : undefined
  const ack = state === 'playing' ? p.acceptedGeneration : undefined
  const failed = p.failedGeneration
  void p.api
    .PUT('/api/files/{id}/progress', {
      params: { path: { id: p.fileId } },
      body: {
        positionMs: Math.max(0, Math.floor(pos)),
        durationMs: dur,
        playId: p.playId,
        state,
        bufferedMs: bufferedMs(),
        throughputBps: p.recovery ? transfer?.throughputBps : throughputBps(p),
        ...currentRendition(p.decision.adaptive),
        throughputAgeMs: transfer?.throughputAgeMs,
        switchGeneration: ack,
        switchFailedGeneration: failed,
        ...p.meter.report(now),
      },
    })
    .then((res) => {
      const r = res.data
      if (!r || play !== p) return
      if (ack && p.acceptedGeneration === ack) {
        p.acceptedGeneration = undefined
        if (pendingReload?.generation === ack) {
          showNote(pendingReload.message)
          pendingReload = null
        }
      }
      if (failed && p.failedGeneration === failed) p.failedGeneration = undefined
      if (r.playId) p.playId = r.playId
      if (r.directive?.action === 'stop') stopByAdmin(r.directive.message)
      else if (r.directive?.action === 'quality') switchQuality(p, r.directive.quality ?? '', r.directive.message, r.directive.generation)
    })
    .catch(() => undefined)
}

/**
 * Automatic's step down: reload at the position with quality auto and the
 * same play, and the server gives the new step. The sender's pick stays.
 */
function switchQuality(p: Play, step: string, message?: string, generation?: number) {
  if (p.switching || pendingReload || p.quality !== AUTO || !p.decision.auto || step === p.decision.auto.step) return
  const negotiated = p.recovery ? switchGeneration(p.decision.auto.step, p.quality, step, generation) : undefined
  if (generation && (!negotiated || generation <= p.handledGeneration)) return
  if (negotiated) p.handledGeneration = negotiated
  p.switching = true
  if (!negotiated) showNote(message || 'Lowered the quality for your connection.')
  reload({ quality: AUTO, audioIndex: p.audioIndex ?? null }, filePositionMs(), negotiated ? { generation: negotiated, target: step,
    message: message || `Quality changed to ${step}.` } : undefined)
}

function showNote(text: string) {
  note = { text, until: Date.now() + 6000 }
  broadcast()
}

/** Reports a play over and lets its transcode go. */
function endPlay(p: Play, state: 'stopped' | 'closed') {
  if (p.over) return
  p.over = true
  if (!p.adminStopped) {
    const dur = p.decision.durationMs
    const pos = p.ended ? dur : Math.min(dur, (p === play ? filePositionS() : 0) * 1000)
    void p.api
      .PUT('/api/files/{id}/progress', {
        params: { path: { id: p.fileId } },
        body: { positionMs: Math.max(0, Math.floor(pos)), durationMs: dur, playId: p.playId, state, bufferedMs: 0 },
      })
      .catch(() => undefined)
  }
  if (p.decision.sessionId) void p.api.DELETE('/api/playback/{session}', { params: { path: { session: p.decision.sessionId } } }).catch(() => undefined)
}

function stopByAdmin(message?: string) {
  if (!play) return
  play.adminStopped = true
  stopMessage = message ?? ''
  phase = 'stopped'
  pm.stop()
  broadcast()
}

/** The episode after this one is done: report it finished. */
function finish(p: Play) {
  p.ended = true
  const dur = p.decision.durationMs
  void p.api.PUT('/api/files/{id}/progress', { params: { path: { id: p.fileId } }, body: { positionMs: dur, durationMs: dur } }).catch(() => undefined)
}

/** Moves on to the next episode; by itself, it may ask "Still watching?" first. */
function advance(byItself: boolean) {
  const p = play
  const n = p?.decision.nextEpisode
  if (!p || !n) return
  if (byItself && shouldAskStillWatching()) {
    p.askingStill = true
    p.nextDueAt = undefined
    pm.pause()
    broadcast()
    return
  }
  if (byItself) noteAutoAdvance()
  else noteInput()
  finish(p)
  playFile(n.fileId, n.showTitle, episodeLine(n.seasonNumber, n.episodeNumber, n.title))
}

function skipNow() {
  const p = play
  if (!p) return
  const seg = currentSegment()
  if (!seg) return
  if (seg.id !== undefined) p.autoSkipped.add(seg.id)
  const end = segmentEnd(seg, durationS())
  if (end < durationS() - 1) {
    seekFile(end, false)
    return
  }
  // Skipping to the end finishes the item.
  if (p.decision.nextEpisode) {
    advance(false)
  } else {
    finish(p)
    pm.stop()
  }
}

function currentSegment() {
  const p = play
  if (!p) return undefined
  return activeSegment(p.decision.segments, filePositionS(), durationS(), !!p.decision.nextEpisode)
}

/** A viewer's seek into a segment means it is never skipped by itself. */
function noteSeek(target: number, byViewer: boolean) {
  const p = play
  if (!p) return
  target = Math.max(0, Math.min(target, durationS() || target))
  p.lastPositionS = target
  if (target < p.decision.upNextMs / 1000) p.creditsDismissed = false
  if (byViewer) {
    noteInput()
    for (const id of segmentsAt(p.decision.segments, target, durationS())) p.seekedInto.add(id)
  }
}

function seekFile(target: number, byViewer: boolean) {
  const p = play
  if (!p) return
  target = Math.max(0, Math.min(target, durationS() || target))
  noteSeek(target, byViewer)
  const local = target - p.offset
  if (seekStaysInPlayer(local, p.offset, wholeTitle(p), pm.getDurationSec())) {
    ownSeek = local
    pm.seek(local)
  } else reload({}, target * 1000)
}

/** The player time of a seek this page made, which reaches the SEEK interceptor too. */
let ownSeek: number | undefined
const wholeTitle = (p: Play) => p.decision.protocol === 'file' || p.decision.playlist === 'vod'

// Every half second: Skip, the Next Episode countdown, heartbeats, state.
window.setInterval(() => {
  const p = play
  if (!p || phase !== 'playing') return
  const now = Date.now()
  const state = pm.getPlayerState()
  const paused = state === M.PlayerState.PAUSED
  const time = filePositionS()
  const showNext = nextCardShowing({ hasNext: !!p.decision.nextEpisode, upNextMs: p.decision.upNextMs, time, ended: p.ended, creditsDismissed: p.creditsDismissed, askingStill: p.askingStill })
  const autoplay = prefs?.autoplayNext ?? true
  if (showNext && autoplay) {
    if (p.nextDueAt === undefined) p.nextDueAt = now + NEXT_COUNTDOWN * 1000
    else if (paused && !p.ended) p.nextDueAt += 500
    else if (now >= p.nextDueAt) {
      p.nextDueAt = undefined
      advance(true)
      return
    }
  } else {
    p.nextDueAt = undefined
  }
  const seg = currentSegment()
  const mode = (seg ? (prefs?.skip[seg.kind] ?? 'ask') : 'none') as SkipMode
  const seekedIn = !!seg && seg.id !== undefined && p.seekedInto.has(seg.id)
  const plan = skipPlan(seg, durationS(), mode, seekedIn, showNext)
  const fresh = !!seg && (seg.id === undefined || !p.autoSkipped.has(seg.id))
  if (seg && fresh && plan.autoNow) skipNow()
  else if (seg && fresh && plan.autoAfterMs !== undefined && !showNext) {
    if (p.skipDueAt?.id !== seg.id) p.skipDueAt = { id: seg.id ?? -1, at: now + plan.autoAfterMs }
    else if (now >= p.skipDueAt.at) skipNow()
  }
  beat(state === M.PlayerState.PLAYING ? 'playing' : state === M.PlayerState.BUFFERING ? 'buffering' : 'paused')
  broadcast()
}, 500)

// ---------------------------------------------------------------- Live TV

/**
 * A channel cast from a sender (PLAN-LIVE-TV.md §4.1). The session is the
 * sender's, through the cast token, and runs through the web player's own
 * controller, which stops one channel's session before starting the next:
 * the household's tuners are few. The TV shows the channel's banner;
 * channels change from the sender.
 */
type LivePlay = {
  load: LoadData
  serverKey: string
  base: string
  api: Client<paths>
  credential: CastCredential
  controller: LiveTVController
  unsubscribe: () => void
  channelId: string
  quality: LiveQuality
  automaticEnabled: boolean
  transfer: TransferMeter
  stalls: number
  waiting: boolean
  played: boolean
  handledGeneration: number
  switchGeneration?: number
  switchURL?: string
  channels: LiveChannel[]
  /** When to ask for the channel list again: its Now ended. */
  channelsDue?: number
  pausedAt?: number
  pauseSeconds: number
  bannerUntil: number
  lastBeat: number
  /** The player has the stream; until then the LOAD is waiting for the session. */
  loaded: boolean
}

type LiveOutputLoad = LoadData & { liveOutput?: { generation: number; url: string; restore?: boolean } }

let live: LivePlay | null = null
/** Why the last channel couldn't play, in the TV's words, until something else loads. */
let liveShown: ReceiverFailure | undefined
/** The channel whose start was refused, for senders' state, until something else loads. */
let liveRefused: LiveInfo | undefined

const liveChannel = (l: LivePlay) => l.channels.find((c) => c.id === l.channelId)

async function refreshChannels(l: LivePlay) {
  const res = await l.api.GET('/api/live-tv/channels').catch(() => undefined)
  if (!res?.data || live !== l) return
  l.channels = res.data.items
  const end = nowEnds(liveChannel(l))
  l.channelsDue = end !== undefined && end > Date.now() ? end + 5000 : Date.now() + 60_000
}

/** Waits until the session plays, or says why it can't. */
function sessionPlaying(l: LivePlay): Promise<string> {
  return new Promise((resolve, reject) => {
    const began = Date.now()
    const check = () => {
      const s = l.controller.getSnapshot()
      if (s.phase === 'playing' && s.session?.url) return resolve(s.session.url)
      if (s.phase === 'error' || s.phase === 'ended' || s.phase === 'idle') return reject(new LiveRefused(s.session?.reason || s.error || '', s.error))
      if (Date.now() - began > START_TIMEOUT_MS) return reject(new LiveRefused('', 'The channel took too long to start.'))
      void l.controller.poll(false).finally(() => window.setTimeout(check, 1000))
    }
    check()
  })
}

/** A receiver-owned reload of the prepared output, never a new channel
 * session or tuner. The marker is private to the receiver's LOAD lane. */
function loadLiveOutput(l: LivePlay, generation: number, url: string, restore = false) {
  const req = new M.LoadRequestData()
  req.media = new M.MediaInformation()
  req.media.contentId = liveContentId(l.channelId)
  req.media.customData = { ...l.load, liveOutput: { generation, url, restore } } satisfies LiveOutputLoad
  req.autoplay = true
  void pm.load(req)
}

/** The family the live player is loading: a pending switch's, else the session's. */
function liveOffer(l: LivePlay) {
  const session = l.controller.getSnapshot().session
  return l.switchGeneration ? session?.directive?.adaptive : session?.adaptive
}

/** A live family is fMP4 with separate audio; a single live output here is MPEG-TS. */
function liveSegments(media: framework.messages.MediaInformation, fmp4: boolean) {
  media.hlsSegmentFormat = fmp4 ? M.HlsSegmentFormat.FMP4 : M.HlsSegmentFormat.TS
  media.hlsVideoSegmentFormat = fmp4 ? M.HlsVideoSegmentFormat.FMP4 : M.HlsVideoSegmentFormat.MPEG2_TS
}

function prepareLiveOutput(req: framework.messages.LoadRequestData, marker: NonNullable<LiveOutputLoad['liveOutput']>) {
  const l = live
  const session = l?.controller.getSnapshot().session
  if (!l || !session || (marker.restore
    ? session.url !== marker.url || session.state !== 'playing'
    : l.switchGeneration !== marker.generation || session.directive?.generation !== marker.generation || session.directive.url !== marker.url)) throw new StaleLoad()
  req.media.contentId = liveContentId(l.channelId)
  req.media.contentUrl = withToken(l.base, marker.url, l.credential.token)
  req.media.contentType = 'application/x-mpegurl'
  req.media.streamType = M.StreamType.LIVE
  liveSegments(req.media, !!liveOffer(l))
  req.media.customData = { v: l.load.v, live: { channelId: l.channelId, quality: l.quality } }
  req.customData = undefined
  return req
}

function switchLiveOutput(l: LivePlay, directive: NonNullable<NonNullable<ReturnType<LiveTVController['getSnapshot']>['session']>['directive']>) {
  if (!l.automaticEnabled || l.pausedAt !== undefined || l.switchGeneration || directive.generation <= l.handledGeneration) return
  l.handledGeneration = directive.generation
  l.switchGeneration = directive.generation
  l.switchURL = directive.url
  l.transfer.reset()
  l.waiting = false
  loadLiveOutput(l, directive.generation, directive.url)
}

function failLiveOutput(l: LivePlay) {
  const generation = l.switchGeneration
  const session = l.controller.getSnapshot().session
  if (!generation || !session) return false
  l.switchGeneration = undefined
  l.switchURL = undefined
  void l.controller.settleOutput(session.id, generation, true).then(() => {
    const current = l.controller.getSnapshot()
    if (live === l && current.phase === 'playing' && current.session?.state === 'playing' && !current.session.directive) {
      l.transfer.reset()
      loadLiveOutput(l, 0, current.session.url, true)
    }
  })
  return true
}

/** Ends a channel's play and gives its tuner back. */
function stopLive(l: LivePlay) {
  if (live === l) live = null
  l.unsubscribe()
  void l.controller.stop()
}

/** Fills a channel's LOAD in once its session plays. */
async function prepareLive(req: framework.messages.LoadRequestData, load: LoadData): Promise<framework.messages.LoadRequestData> {
  const want = load.live
  if (!want?.channelId || !load.token || !load.server?.id) throw new Error('This cast came without a WatchMyMedia server or channel.')
  if (play) {
    endPlay(play, 'stopped')
    play = null
  }
  phase = 'loading'
  errorText = stopMessage = liveShown = undefined
  const base = await pickServer(load.server)
  if ((levels.get(load.server.id) ?? 0) < LIVE_CAST_LEVEL) throw new Error('This server can’t cast Live TV yet. Update WatchMyMedia on the server.')
  const serverKey = castServerKey(load.server)
  let l = live
  if (l && l.serverKey !== serverKey) {
    stopLive(l)
    l = null
  }
  if (!l) {
    const credential = { token: load.token }
    const api = castClient(base, credential)
    const caps = await api.GET('/api/live-tv/capabilities').catch(() => undefined)
    const level = levels.get(load.server.id) ?? 0
    const automaticEnabled = level >= 26 && !!caps?.data?.automatic && castRecoveryAvailable(level)
    const controller = new LiveTVController(castLiveTransport(api, load.device?.name || 'Chromecast', { profile: level >= 25, automatic: automaticEnabled, adaptive: level >= ADAPTIVE_LEVEL }))
    l = { load, serverKey, base, api, credential, controller, unsubscribe: () => {}, channelId: want.channelId,
      quality: '1080', automaticEnabled, transfer: new TransferMeter(), stalls: 0, waiting: false, played: false,
      handledGeneration: 0, channels: [], pauseSeconds: 300, bannerUntil: 0, lastBeat: 0, loaded: false }
    if (caps?.data?.pauseSeconds) l.pauseSeconds = caps.data.pauseSeconds
    const self = l
    l.unsubscribe = controller.subscribe(() => liveChanged(self))
  } else {
    l.load = load
    l.credential.token = load.token
  }
  live = l
  l.channelId = want.channelId
  const selectedQuality = liveQuality(want.quality ?? null)
  l.quality = selectedQuality === 'auto' && !l.automaticEnabled ? '1080' : selectedQuality
  l.pausedAt = undefined
  l.loaded = false
  l.transfer.reset()
  l.stalls = 0
  l.waiting = false
  l.played = false
  l.handledGeneration = 0
  l.switchGeneration = undefined
  l.switchURL = undefined
  await refreshChannels(l)
  title = (() => {
    const c = liveChannel(l)
    return c ? `${c.number} ${c.name}` : title
  })()
  broadcast()
  await l.controller.switchTo(l.channelId, l.quality)
  const url = await sessionPlaying(l)
  if (live !== l) throw new Error('Another cast replaced this one.')
  const channel = liveChannel(l)
  const media = req.media
  media.contentId = liveContentId(l.channelId)
  media.contentUrl = withToken(base, url, l.credential.token)
  media.contentType = 'application/x-mpegurl'
  media.streamType = M.StreamType.LIVE
  liveSegments(media, !!liveOffer(l))
  const meta = new M.GenericMediaMetadata()
  meta.title = channel ? `${channel.number} ${channel.name}` : ''
  if (channel?.now?.title) meta.subtitle = channel.now.title
  media.metadata = meta
  req.autoplay = true
  // Keep credentials only in our private play context.
  media.customData = { v: load.v, live: { channelId: l.channelId, quality: l.quality } }
  req.customData = undefined
  l.loaded = true
  l.lastBeat = Date.now()
  l.bannerUntil = Date.now() + BANNER_MS
  scheduleRenewal(load.tokenExpiresAt)
  return req
}

/** The session ended or failed while playing: stop the player and say why. */
function liveChanged(l: LivePlay) {
  if (live !== l || !l.loaded) return
  const s = l.controller.getSnapshot()
  if (s.phase === 'playing') {
    if (s.session?.directive) switchLiveOutput(l, s.session.directive)
    return
  }
  if (s.phase === 'tuning') return
  l.loaded = false
  const reason = s.session?.reason || s.error || ''
  if (reason === 'stopped_by_admin') {
    phase = 'stopped'
    stopMessage = ''
    liveShown = { title: 'Stopped from Activity', body: 'The server’s admin stopped Live TV on this TV.', small: '' }
  } else if (s.phase !== 'idle') {
    phase = 'error'
    liveShown = receiverFailure(reason, liveChannel(l)?.number ?? '')
    errorText = `${liveShown.title}. ${liveShown.body}`
  }
  pm.stop()
  broadcast()
}

function reportLivePlayback(l: LivePlay) {
  const session = l.controller.getSnapshot().session
  if (!session?.automatic) return
  const video = mediaElement()
  const transfer = l.transfer.read(performance.now())
  l.controller.reportPlayback(session.id, {
    state: l.pausedAt !== undefined ? 'paused' : l.waiting ? 'buffering' : 'playing',
    ...currentRendition(session.directive ? session.directive.adaptive : session.adaptive),
    ...(video ? { bufferedMs: Math.round(bufferedAhead(video.buffered, video.currentTime) * 1000) } : {}),
    throughputBps: transfer?.throughputBps, throughputAgeMs: transfer?.throughputAgeMs, stalls: l.stalls,
  })
}

/** Plays another channel, or the same one at another quality. */
function loadChannel(l: LivePlay, channelId: string, quality: string = l.quality) {
  const req = new M.LoadRequestData()
  req.media = new M.MediaInformation()
  req.media.contentId = liveContentId(channelId)
  req.media.customData = { ...l.load, token: l.credential.token, live: { channelId, quality } } satisfies LoadData
  req.autoplay = true
  void pm.load(req)
}

// Every second while a channel plays: its heartbeat, Now moving on, the banner.
window.setInterval(() => {
  const l = live
  if (!l || !l.loaded) return
  const now = Date.now()
  if (now - l.lastBeat >= HEARTBEAT_MS) {
    l.lastBeat = now
    reportLivePlayback(l)
    void l.controller.poll(true)
  }
  if (l.channelsDue !== undefined && now >= l.channelsDue) {
    l.channelsDue = undefined
    void refreshChannels(l).then(() => broadcast())
  }
  broadcast()
}, 1000)

/** What the TV shows for a channel: its own screens, with pictures on the cast token. */
function liveScreen(): LiveScreen | undefined {
  const l = live
  if (liveShown && (phase === 'error' || phase === 'stopped')) return { kind: 'failure', failure: liveShown }
  if (!l) return undefined
  const at = Date.now()
  const c = liveChannel(l)
  const { now, next } = c ? nowAndNext(c, at) : {}
  const art = (path?: string) => (path ? withToken(l.base, path, l.credential.token) : undefined)
  const shown = { number: c?.number ?? '', name: c?.name ?? '', logo: art(c?.logoUrl), picture: art(now?.pictureUrl), now, next }
  if (!l.loaded) return { ...shown, kind: 'tuning' }
  if (liveViewingPrompt(l.controller.getSnapshot().lastInput, at) !== 'none') return { ...shown, kind: 'still' }
  if (l.pausedAt !== undefined) {
    const left = liveInfo(c, l.channelId, l.quality, l.controller.getSnapshot().session, l.pausedAt, l.pauseSeconds, at).pauseLeftSeconds ?? 0
    return { ...shown, kind: 'paused', pauseLeftMin: Math.ceil(left / 60) }
  }
  return { ...shown, kind: at < l.bannerUntil ? 'banner' : 'playing' }
}

function liveState(l: LivePlay): Partial<StateMessage> {
  const s = l.controller.getSnapshot()
  const info = liveInfo(liveChannel(l), l.channelId, l.quality, s.session, l.pausedAt, l.pauseSeconds, Date.now())
  return {
    title: info.number ? `${info.number} ${info.name}` : title,
    subtitle: info.now?.title,
    quality: l.quality,
    autoStep: currentRendition(s.session?.adaptive)?.renditionHeight?.toString() ?? (s.session?.adaptive ? undefined : s.session?.automatic ? s.session.actualQuality : undefined),
    stillWatching: l.loaded && liveViewingPrompt(s.lastInput, Date.now()) !== 'none',
    live: info,
  }
}

// ---------------------------------------------------------------- state

function stateMessage(): StateMessage {
  const p = play
  const base: StateMessage = { type: 'state', v: PROTOCOL_VERSION, features: [FEATURE_LIVE, FEATURE_LIVE_AUTO], phase, error: errorText ?? null, stopMessage: phase === 'stopped' ? (stopMessage ?? '') : null }
  if (live) return { ...base, ...liveState(live) }
  if (liveRefused) return { ...base, title: liveRefused.number ? `${liveRefused.number} ${liveRefused.name}` : undefined, live: liveRefused }
  if (!p) return base
  const d = p.decision
  const time = filePositionS()
  const n = d.nextEpisode
  const showNext = nextCardShowing({ hasNext: !!n, upNextMs: d.upNextMs, time, ended: p.ended, creditsDismissed: p.creditsDismissed, askingStill: p.askingStill })
  const seg = currentSegment()
  const mode = (seg ? (prefs?.skip[seg.kind] ?? 'ask') : 'none') as SkipMode
  const plan = skipPlan(seg, durationS(), mode, !!seg && seg.id !== undefined && p.seekedInto.has(seg.id), showNext)
  const still = n?.stillPath ? withToken(p.base, `/api/images/w300${n.stillPath}`, p.credential.token) : undefined
  const next = n ? { fileId: n.fileId, showTitle: n.showTitle, seasonNumber: n.seasonNumber, episodeNumber: n.episodeNumber, title: n.title, stillUrl: still } : null
  return {
    ...base,
    fileId: p.fileId,
    playId: p.playId,
    title: p.title,
    subtitle: p.subtitle,
    mode: d.mode,
    quality: p.quality,
    autoStep: currentRendition(d.adaptive)?.renditionHeight?.toString() ?? (d.adaptive ? null : d.auto?.step ?? null),
    durationMs: d.durationMs,
    offsetMs: Math.round(p.offset * 1000),
    watchedAtMs: d.watchedAtMs,
    segments: d.segments.map((s) => ({ kind: s.kind, startMs: s.startMs, endMs: s.endMs ?? null, final: s.final })),
    skip: seg && (plan.showButton || plan.autoNow || plan.autoAfterMs !== undefined) ? { kind: seg.kind, endMs: segmentEnd(seg, durationS()) * 1000, auto: plan.autoNow } : null,
    upNext: showNext && next ? { ...next, secondsLeft: (prefs?.autoplayNext ?? true) && p.nextDueAt !== undefined ? Math.max(0, Math.ceil((p.nextDueAt - Date.now()) / 1000)) : null } : null,
    nextEpisode: next,
    stillWatching: p.askingStill,
    audio: d.audioTracks.map((a) => ({ index: a.index, codec: a.codec, channels: a.channels, language: a.language, title: a.title, default: a.default })),
    selectedAudio: p.audioIndex ?? null,
    subtitles: d.subtitles.map((s) => ({ id: s.id, language: s.language, title: s.title, format: s.format, forced: s.forced, burnInOnly: s.burnInOnly })),
    selectedSubtitle: p.subtitleId ?? null,
  }
}

let lastSent = ''
let senderName: string | undefined

/** Sends the state to every sender when it changed, and draws the screens. */
function broadcast(force = false, to?: string) {
  const msg = stateMessage()
  if (note && note.until <= Date.now()) note = undefined
  render({ ...msg, senderName, note: note?.text, liveScreen: liveScreen() })
  const published = publicCastMessage(msg)
  const json = JSON.stringify(published)
  if (!force && json === lastSent) return
  lastSent = json
  try {
    ctx.sendCustomMessage(NAMESPACE, to, published)
  } catch {
    // No sender connected.
  }
}

// ---------------------------------------------------------------- messages

pm.setMessageInterceptor(M.MessageType.LOAD, async (req) => {
  const load = (req.media?.customData ?? req.customData) as LiveOutputLoad | undefined
  const sequence = ++loadSequence
  try {
    if (load?.liveOutput) return prepareLiveOutput(req, load.liveOutput)
    senderName = load?.sender?.name ?? senderName
    liveRefused = undefined
    if (!load?.live) liveShown = undefined
    return await (load?.live ? prepareLive(req, load) : prepare(req, sequence))
  } catch (e) {
    if (e instanceof StaleLoad) return new M.ErrorData(M.ErrorType.LOAD_FAILED)
    if (load?.liveOutput && live && failLiveOutput(live)) return new M.ErrorData(M.ErrorType.LOAD_FAILED)
    if (!load?.live && failReload()) {
      broadcast()
      return new M.ErrorData(M.ErrorType.LOAD_FAILED)
    }
    suppressFailedLoadEnd = false
    phase = 'error'
    if (load?.live && e instanceof LiveRefused) {
      liveShown = receiverFailure(e.reason, (live && liveChannel(live)?.number) ?? '')
      errorText = `${liveShown.title}. ${liveShown.body}`
      const l = live
      liveRefused = refusedLiveInfo(l?.channels.find((c) => c.id === load.live?.channelId), load.live.channelId, liveQuality(load.live.quality ?? null), e.reason)
    } else {
      errorText = e instanceof Error ? e.message : String(e)
    }
    play = null
    if (live && load?.live) stopLive(live)
    broadcast()
    return new M.ErrorData(M.ErrorType.LOAD_FAILED)
  }
})

// CAF includes media URLs, subtitle tracks and queued LOAD data in status.
// Copy before redacting so the private player still has its authenticated URLs.
pm.setMessageInterceptor(M.MessageType.MEDIA_STATUS, (status) => publicCastMessage(status))

// Standard seeks (system controls, Google Home) are in player time.
pm.setMessageInterceptor(M.MessageType.SEEK, (req) => {
  const p = play
  if (!p) return req
  if (ownSeek !== undefined && req.currentTime !== undefined && Math.abs(req.currentTime - ownSeek) < 0.01) {
    ownSeek = undefined
    return req
  }
  const plan = interceptSeek(req.currentTime, p.offset, wholeTitle(p), pm.getDurationSec())
  if (!plan) return req
  noteSeek(plan.fileS, true)
  if (!plan.reload) return req
  reload({}, Math.max(0, Math.min(plan.fileS, durationS() || plan.fileS)) * 1000)
  return drop<typeof req>()
})

// The standard Next button.
pm.setMessageInterceptor(M.MessageType.QUEUE_UPDATE, (req) => {
  if ((req.jump ?? 0) > 0 && play?.decision.nextEpisode) {
    advance(false)
    return drop<typeof req>()
  }
  return req
})

pm.setMessageInterceptor(M.MessageType.PLAY, (req) => {
  noteInput()
  live?.controller.noteInput()
  if (play?.askingStill) {
    play.askingStill = false
    advance(false)
    return drop<typeof req>()
  }
  return req
})

pm.addEventListener(cast.framework.events.EventType.PLAYER_LOAD_COMPLETE, () => {
  suppressFailedLoadEnd = false
  phase = 'playing'
  const p = play
  if (p && p.decision.playlist === 'vod') {
    // Map the rendition text tracks onto the decision's subtitles, in order.
    const text = pm.getTextTracksManager().getTracks().filter((t) => t.type === M.TrackType.TEXT)
    const subs = p.decision.subtitles.filter((s) => s.format !== 'image')
    subs.forEach((s, i) => {
      if (text[i]) p.trackIds.set(s.id, text[i].trackId)
    })
    showSubtitle(p.subtitleId)
  }
  beat('buffering', true)
  broadcast()
})

for (const [type, state] of [
  [cast.framework.events.EventType.PLAYING, 'playing'],
  [cast.framework.events.EventType.PAUSE, 'paused'],
] as const) {
  pm.addEventListener(type, () => {
    if (play) play.ended = false
    const l = live
    const id = l?.controller.getSnapshot().session?.id
    if (l && id) {
      // A pause keeps the tuner for the server's allowance; it says so.
      l.pausedAt = state === 'paused' ? (l.pausedAt ?? Date.now()) : undefined
      l.controller.setPaused(id, state === 'paused')
      if (state === 'playing') {
        l.played = true
        l.waiting = false
        const generation = l.switchGeneration
        if (generation) {
          const confirm = () => {
            if (live !== l || l.switchGeneration !== generation) return
            l.switchGeneration = undefined
            l.switchURL = undefined
            l.stalls = 0
            void l.controller.settleOutput(id, generation, false)
          }
          const video = mediaElement()
          if (video instanceof HTMLVideoElement && typeof video.requestVideoFrameCallback === 'function') video.requestVideoFrameCallback(confirm)
          else if (video instanceof HTMLVideoElement) {
            const frame = () => {
              if (video.readyState < HTMLMediaElement.HAVE_CURRENT_DATA) return
              video.removeEventListener('timeupdate', frame)
              confirm()
            }
            video.addEventListener('timeupdate', frame)
            frame()
          } else confirm() // CAF PLAYING is the available signal when its video is inaccessible.
        }
      }
    }
    beat(state, true)
    broadcast()
  })
}
// Stalls (PLAN-AUTO-QUALITY.md §3.1), counted as the web player counts
// them: buffering after the first frame that no seek or resume brought.
// CAF's BUFFERING is the signal: under Shaka (HLS) the video holds still
// on a rebuffer and fires neither waiting nor playing.
pm.addEventListener(cast.framework.events.EventType.BUFFERING, (e) => {
  const l = live
  if (l?.automaticEnabled && l.loaded) {
    const buffering = (e as framework.events.BufferingEvent).isBuffering
    if (buffering && l.played && !l.waiting) l.stalls++
    l.waiting = buffering
    if (buffering) { reportLivePlayback(l); void l.controller.poll(true) }
  }
  const p = play
  if (!p) return
  const now = Date.now()
  if (!(e as framework.events.BufferingEvent).isBuffering) {
    p.meter.playing(now)
    p.sampled = true
    return
  }
  beat('buffering', true)
  if (!p.meter.waiting(now)) return
  // At the lowest step there is nowhere left to go: say so, once a play.
  if (p.decision.auto?.step === QUALITIES[QUALITIES.length - 1].id && !p.floorNoted) {
    p.floorNoted = true
    showNote('Your connection is too slow to play this smoothly.')
  }
})
pm.addEventListener(cast.framework.events.EventType.PLAYING, () => {
  if (!play) return
  play.meter.playing(Date.now())
  play.sampled = true
})
pm.addEventListener([cast.framework.events.EventType.SEEKING, cast.framework.events.EventType.PLAY], () => play?.meter.excuse())
pm.addEventListener(cast.framework.events.EventType.SEEKED, () => {
  if (play && pm.getPlayerState() === M.PlayerState.PLAYING) play.meter.settled()
})
pm.addEventListener(cast.framework.events.EventType.PAUSE, () => play?.meter.paused(Date.now()))

pm.addEventListener(cast.framework.events.EventType.MEDIA_FINISHED, (e) => {
  const reason = (e as framework.events.MediaFinishedEvent).endedReason
  const l = live
  if (l && l.loaded && reason !== 'INTERRUPTED') {
    if (reason === 'ERROR' && failLiveOutput(l)) return
    const session = l.controller.getSnapshot().session
    if (reason === 'ERROR' && session?.adaptive) { l.controller.reportPlayback(session.id, { state: 'buffering', adaptiveFailed: true }); void l.controller.poll(true); return }
    // A stop from a sender or the TV, the stream's end or a failure: the
    // tuner goes back. A new LOAD interrupts, and keeps the controller.
    if (reason === 'ERROR') {
      phase = 'error'
      errorText = `The TV could not play this channel${playerError ? ` (error ${playerError})` : ''}.`
    } else if (phase === 'playing') {
      phase = 'idle'
    }
    stopLive(l)
    broadcast()
    return
  }
  const p = play
  if (!p) return
  if (reason === 'ERROR' && suppressFailedLoadEnd) return
  if (reason === 'ERROR' && failReload()) {
    broadcast()
    return
  }
  if (reason === 'ERROR' && p.decision.adaptive && !p.adaptiveDisabled) {
    p.adaptiveDisabled = true
    reload({}, filePositionMs())
    return
  }
  if (reason === 'END_OF_STREAM') {
    finish(p)
    if (p.decision.nextEpisode) {
      phase = 'playing' // the Next Episode card keeps counting at the end
      beat('paused', true)
    } else {
      phase = 'ended'
      endPlay(p, 'stopped')
    }
  } else if (reason === 'STOPPED' || reason === 'ERROR') {
    endPlay(p, 'stopped')
    if (!p.adminStopped) phase = reason === 'ERROR' ? 'error' : 'idle'
    if (reason === 'ERROR') errorText = `The TV could not play this stream${playerError ? ` (error ${playerError})` : ''}.`
    play = null
  }
  broadcast()
})

// Why the player failed, for the message: CAF's detailed error code, and the
// player library's own code when it passes one on.
pm.addEventListener(cast.framework.events.EventType.ERROR, (e) => {
  const { detailedErrorCode, error } = e as framework.events.ErrorEvent
  const inner = error as { shakaErrorCode?: number; code?: number; data?: { name?: string }[] } | undefined
  // Shaka's first datum is often the exception a browser API threw, such as NotSupportedError.
  const thrown = inner?.data?.[0]?.name
  const codes = [detailedErrorCode, inner?.shakaErrorCode ?? inner?.code, typeof thrown === 'string' ? thrown : undefined].filter((c) => c !== undefined)
  if (codes.length) playerError = codes.join(', ')
  // The detail for diagnosis (senders ignore message types they do not know):
  // the exception's text names the media type the TV refused.
  const d = play?.decision
  const detail = {
    type: 'diag',
    v: PROTOCOL_VERSION,
    codes,
    message: String((inner?.data?.[0] as { message?: unknown } | undefined)?.message ?? '').slice(0, 300),
    decision: d && { mode: d.mode, playlist: d.playlist, video: d.video, audio: d.audio, defaults: hlsCodecDefaults(d) },
  }
  try {
    ctx.sendCustomMessage(NAMESPACE, undefined, publicCastMessage(detail))
  } catch {
    // No sender connected.
  }
})

ctx.addEventListener(cast.framework.system.EventType.SHUTDOWN, () => {
  // Off a Cast device (the dev server in a browser) CAF shuts down each time
  // it fails to reach the device, which says nothing about the play.
  if (import.meta.env.DEV) return
  if (play) endPlay(play, 'closed')
  play = null
  if (live) stopLive(live)
})

// A TV may close the page without SHUTDOWN: the tuner goes back anyway.
window.addEventListener('pagehide', () => {
  if (live) stopLive(live)
})

ctx.addEventListener(cast.framework.system.EventType.SENDER_CONNECTED, (e) => {
  broadcast(true, (e as framework.system.SenderConnectedEvent).senderId)
})

function showSubtitle(id: string | undefined) {
  const p = play
  if (!p) return
  const tid = id ? p.trackIds.get(id) : undefined
  pm.getTextTracksManager().setActiveByIds(tid ? [tid] : [])
}

ctx.addCustomMessageListener(NAMESPACE, (event) => {
  const msg = (typeof event.data === 'string' ? JSON.parse(event.data) : event.data) as CommandMessage
  const p = play
  switch (msg.type) {
    case 'getState':
      broadcast(true, event.senderId)
      return
    case 'input':
      noteInput()
      // A touch on a sender counts as watching for Live TV too (interacted).
      live?.controller.noteInput()
      return
    case 'caps':
      ctx.sendCustomMessage(NAMESPACE, event.senderId, { type: 'caps', v: PROTOCOL_VERSION, ...castCaps(ctx) })
      return
  }
  const l = live
  if (l) {
    noteInput()
    l.controller.noteInput()
    switch (msg.type) {
      case 'channel':
        if (msg.channelId !== l.channelId) loadChannel(l, msg.channelId)
        break
      case 'channelStep': {
        const next = nextLiveChannel(l.channels, l.channelId, msg.by)
        if (next) loadChannel(l, next.id)
        break
      }
      case 'quality':
        if (liveQuality(msg.id) !== l.quality) loadChannel(l, l.channelId, liveQuality(msg.id))
        break
      case 'stillWatching':
        if (!msg.keep) pm.stop()
        break
    }
    broadcast()
    return
  }
  if (!p) return
  noteInput()
  switch (msg.type) {
    case 'seek':
      seekFile(msg.positionMs / 1000, true)
      break
    case 'skip':
      skipNow()
      break
    case 'playNext':
      advance(false)
      break
    case 'watchCredits':
      p.creditsDismissed = true
      p.nextDueAt = undefined
      break
    case 'stillWatching':
      p.askingStill = false
      if (msg.keep) advance(false)
      else pm.stop()
      break
    case 'audio':
      p.audioIndex = msg.index
      reload({ audioIndex: msg.index })
      break
    case 'subtitle': {
      const prev = p.decision.subtitles.find((s) => s.id === p.subtitleId)
      const next = p.decision.subtitles.find((s) => s.id === msg.id)
      p.subtitleId = msg.id ?? undefined
      p.load = manualSubtitleSelection(p.load, msg.id)
      // Image subtitles need a burn-in session; text ones switch in place.
      if (next?.burnInOnly || prev?.burnInOnly || p.decision.burnIn) reload({ subtitleId: msg.id, audioIndex: p.audioIndex ?? null })
      else showSubtitle(p.subtitleId)
      break
    }
    case 'quality':
      p.quality = qualityById(msg.id, levels.get(p.load.server.id)).id
      note = undefined
      reload({ quality: p.quality, audioIndex: p.audioIndex ?? null })
      break
  }
  broadcast()
})

// ---------------------------------------------------------------- start

const options = new cast.framework.CastReceiverOptions()
options.customNamespaces = { [NAMESPACE]: cast.framework.system.MessageType.JSON }
options.supportedCommands = M.Command.ALL_BASIC_MEDIA | M.Command.QUEUE_NEXT
options.statusText = 'WatchMyMedia'
// Shaka plays HLS; tell it which codecs are coming in case the playlist
// does not (hlsCodecDefaults).
options.useShakaForHls = true
pm.setMediaPlaybackInfoHandler((_req, config) => {
  const l = live
  const offer = play?.decision.adaptive ?? (l && liveOffer(l))
  if (play || l) config.shakaConfig = {
    abr: { enabled: !!offer }, restrictions: adaptiveRestrictions(offer),
    ...(play ? { manifest: { hls: hlsCodecDefaults(play.decision) } } : {}),
    ...(play?.recovery || l?.automaticEnabled ? { streaming: receiverBuffering(!!l) } : {}),
  }
  const credential = play?.credential ?? l?.credential
  if (credential) {
    // The playlist and its segments carry the cast token; keep it the
    // current one as it renews.
    const current = (info: framework.NetworkRequestInfo) => {
      if (info.url) info.url = withCurrentToken(info.url, credential.token)
    }
    config.manifestRequestHandler = current
    config.segmentRequestHandler = current
  }
  return config
})
render({ type: 'state', v: PROTOCOL_VERSION, phase: 'idle' })
ctx.start(options)
