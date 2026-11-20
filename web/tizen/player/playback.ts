import { AutomaticSubtitles, asksForAutomaticSubtitle } from '../../src/lib/automaticSubtitles'
import { growingDurationMs, growingSeekMs, plannedRecordingMs, showGoToNow, startRetryMs, stillRecording } from '../../src/lib/liveRecordings'
import { AUTO, QUALITIES, applyQuality, qualityById } from '../../src/lib/quality'
import { AUTO_DELAY_MS, NEXT_COUNTDOWN, SKIP_LABELS, StallMeter, activeSegment, beatDue, nextCardShowing, segmentsAt, skipPlan, type BeatState, type SkipMode } from '../../src/lib/play/rules'
import { requestedPlaylist, resumePlan, timelineOffset } from '../../src/lib/play/stream'
import type { StillWatchingSession } from '../../src/lib/stillWatching'
import { fetchImage, usableTrickplay } from '../../src/lib/trickplay'
import { call, PlayerError, safeMessage, type ClientInfo, type Decision, type DecisionRequest, type Directive, type PlayerApi, type Preferences, type Profile, type Progress, type Segment, type SubtitlePreferences, type SubtitleSearch, type SubtitleTrack, type UpNext } from './api'
import { PlaybackFailure, type Player, type PlayerFailure, type PlayerState, type PlayerUpdate } from './avplay'
import { systemClock, type Clock } from './clock'
import type { Cue } from './vtt'
import { audioWords, automaticStepNote, episodeWords, onlineWords, SLOW_CONNECTION_NOTE, STOPPED_BY_ADMIN, streamPill, SUBTITLE_FAILED_NOTE, AUTOMATIC_SUBTITLE_FAILED, subtitleWords, type OptionWords } from './labels'
import { fallbackProfile, samsungProfile, uhd } from './profile'
import { Scrub, seekStepLabel } from './seek'
import { burnsIn, CueTracks, fetchCueText, trackKey, type SubtitleSize } from './subtitles'
import { Thumbnails, type Thumbnail } from './thumbnails'
import { MediaTokenClock } from './tokens'

// ---------------------------------------------------------------- what the shell gives and gets

/** What to play. A trailer file starts at 0 and leaves at its end; a recording plays through its own routes. */
export type FileRequest =
  | { kind: 'file'; fileId: number; startMs?: number; title?: string; audioIndex?: number; subtitleId?: string | null; diagnostics?: Diagnostics }
  | { kind: 'trailer'; fileId: number; title?: string }
  | { kind: 'recording'; recordingId: string; startMs?: number; title?: string }

/** S0 experiments only: a profile and playlist to send instead of the TV's. */
export type Diagnostics = { profile?: Profile; playlist?: 'vod' | 'event' }

/** The person's and the TV's playback settings, read at each play. */
export type PlaybackSettings = {
  /** The TV's default quality: auto unless one was saved. */
  quality: string
  /** Per segment kind, from the server's preferences. */
  skip: Record<string, SkipMode>
  autoplayNext: boolean
  subtitles?: SubtitlePreferences
  subtitleSize: SubtitleSize
  /** HDR10 was proven on this model: the profile lists it. */
  hdr10?: boolean
}

export const DEFAULT_SETTINGS: PlaybackSettings = { quality: AUTO, skip: {}, autoplayNext: true, subtitleSize: 'normal' }

const SKIP_MODES: readonly string[] = ['none', 'ask', 'auto', 'auto-delay']

/** The settings from the person's preferences (GET /api/me/preferences) and the TV's own. */
export function playbackSettings(preferences: Preferences | undefined, device: Partial<Pick<PlaybackSettings, 'quality' | 'subtitleSize' | 'hdr10'>> = {}): PlaybackSettings {
  const skip: Record<string, SkipMode> = {}
  for (const [kind, mode] of Object.entries(preferences?.skip ?? {})) if (SKIP_MODES.includes(mode)) skip[kind] = mode as SkipMode
  return { ...DEFAULT_SETTINGS, ...device, skip, autoplayNext: preferences?.autoplayNext ?? true, subtitles: preferences?.subtitles }
}

export type PlaybackContext = {
  api: PlayerApi
  player: Player
  /** The server's address for a media path, carrying token as ?access_token=; it refuses paths outside the media routes. */
  mediaUrl: (path: string, token: string) => string
  client: ClientInfo
  settings: () => PlaybackSettings
  /** One for the app: the counts outlive each play. */
  stillWatching: StillWatchingSession
  /** A pick in the player becomes the TV's default. */
  saveQuality?: (id: string) => void
  clock?: Clock
  fetchText?: (url: string) => Promise<string>
  loadImage?: (url: string) => Promise<void>
}

export type FilePhase = 'idle' | 'loading' | 'buffering' | 'playing' | 'paused' | 'ended' | 'error'

export type AudioOption = OptionWords & { index: number }
export type SubtitleOption = OptionWords & { id: string; language?: string; burnIn: boolean; source?: string }
export type OnlineOption = OptionWords & { id: string; trackId?: string }

export type FilePlaybackState = {
  phase: FilePhase
  request?: FileRequest
  title?: string
  /** File time. While scrubbing, scrub has the target. */
  positionMs: number
  /** The file's length, or how much of a growing recording is written. */
  durationMs: number
  /** The bar's length: a growing recording's planned length, else durationMs. */
  timelineMs: number
  bufferedMs?: number
  /** The pill: "Direct play · 4K HDR · EAC3 5.1", "Auto · 720p". */
  stream?: string
  mode?: Decision['mode']
  audio: { options: readonly AudioOption[]; selected?: number }
  subtitles: {
    options: readonly SubtitleOption[]
    selected?: string
    /** Nothing chosen in this play: the account's subtitle settings apply. */
    automatic: boolean
    online?: { available: boolean; canDownload: boolean; remaining: number; attribution: string; results: readonly OnlineOption[]; error?: string; downloading?: string }
    /** Showing now, at size px. */
    cues: readonly Cue[]
    size: SubtitleSize
  }
  quality: { id: string; options: readonly { id: string; label: string }[]; autoStep?: string }
  note?: string
  skip?: { segmentId: number; kind: string; label: string; progress?: number }
  next?: { episode: UpNext; heading: string; countdownMs?: number; seconds?: number }
  askStillWatching: boolean
  scrub?: { targetMs: number; stepMs: number; step: string; holding: boolean; thumbnail?: Thumbnail }
  /** Seek thumbnails exist for this file. */
  trickplay: boolean
  recording?: { now: boolean; goToNow: boolean; starting: boolean }
  /** The decision's segments (intro, credits, ad breaks), for the scrubber's bands. */
  segments?: readonly Segment[]
  /** A file's next episode, whenever there is one (next is the card, which shows only near the end). */
  upNext?: UpNext
  adminStop?: { message: string }
  error?: string
  /** With error: what kind of failure it was and the server's status, for the TV's list of playback errors. Never words. */
  failure?: { kind: PlayerFailure['kind']; status?: number }
  /** The play ended by itself (the end, a skip to the end, Still watching's No): leave the player. */
  exited: boolean
}

// ---------------------------------------------------------------- numbers (PLAN-CLIENTS.md §2.3, Android's Rules.kt)

export const TICK_MS = 250
const NOTE_MS = 5_000
const MAX_RECOVERIES = 2
const RECOVERY_RESET_MS = 60_000
const TOKEN_RETRY_MS = 30_000
const END_SLACK_MS = 1_000
const EVENT_SEEK_SLACK_MS = 500
const DECIDE_TIMEOUT_MS = 45_000
const EVENT_START_TOLERANCE_MS = 15_000
const STEP_GAP_MS = 10_000

const IDLE: FilePlaybackState = {
  phase: 'idle', positionMs: 0, durationMs: 0, timelineMs: 0, audio: { options: [] },
  subtitles: { options: [], automatic: true, cues: [], size: 'normal' }, quality: { id: AUTO, options: [] },
  askStillWatching: false, trickplay: false, exited: false,
}

const QUALITY_OPTIONS = QUALITIES.map((q) => ({ id: q.id, label: q.label }))

/** One file being watched, from play (or the next episode starting) until it stops. */
class Viewing {
  decision?: Decision
  playId?: string
  /** Load requests: only the latest one's decision is applied. */
  loads = 0
  loading = false
  /** While loading: the file position it will start at. */
  pendingMs?: number
  receivedAt = 0
  recordedEndMs?: number
  playerState: PlayerState = 'idle'
  playerDurationMs = 0
  paused = false
  everPlayed = false
  bufferedMs?: number
  audioIndex?: number
  subtitleId?: string
  audioOptions: readonly AudioOption[] = []
  subtitleOptions: readonly SubtitleOption[] = []
  online?: FilePlaybackState['subtitles']['online']
  searched = false
  automaticTried = false
  seekedInto = new Set<number>()
  creditsDismissed = false
  askStill = false
  countdownMs?: number
  fillSegment?: number
  fillMs = 0
  autoSkipped?: number
  watched = false
  finished = false
  stopped = false
  adminStopped = false
  stopMessage?: string
  error?: string
  failure?: FilePlaybackState['failure']
  lastReported?: BeatState
  lastBeatAt = 0
  seekLanded = false
  recoveries = 0
  recoveredAt = 0
  fellBack = false
  starting = false
  stepNote?: string
  steppedAt = -Infinity
  floorNoted = false
  parkedAtMs?: number
  suspended = false
  thumbnails?: Thumbnails
  cueTimer?: unknown
  readonly meter = new StallMeter()
  readonly subtitles: AutomaticSubtitles<SubtitleTrack>
  constructor(readonly request: FileRequest, readonly generation: number, readonly quality: { id: string }, automatic: (playId: string, cancel?: boolean) => Promise<{ track?: SubtitleTrack; error?: string }>) {
    this.subtitles = new AutomaticSubtitles<SubtitleTrack>(request.kind === 'recording' ? 0 : request.fileId, (_id, playId, cancel) => automatic(playId, cancel))
    if (request.kind === 'file') {
      this.audioIndex = request.audioIndex
      if (request.subtitleId !== undefined) this.subtitles.choose(request.subtitleId ?? '')
      if (request.subtitleId) this.subtitleId = request.subtitleId
    }
  }
}

type LoadOptions = { seekMs?: number; subtitleId?: string; burnIn?: boolean; newToken?: boolean; fallback?: boolean; paused?: boolean }

/**
 * The file player's behaviour (PLAN-CLIENTS.md §2.3) around one Player, as
 * Android's RealPlaybackController: asks the server how to play, maps the
 * player's time to the file's, sends heartbeats and obeys their directives,
 * and runs Skip, Next episode, Still watching, subtitles, quality, seek
 * thumbnails and recovery. Everything is driven by player updates and a
 * 250 ms tick; reports and session ends go out in order through one queue.
 * One controller belongs to one server and sign-in.
 */
export class FilePlayback {
  private state: FilePlaybackState = IDLE
  private listeners = new Set<() => void>()
  private viewing?: Viewing
  private generation = 0
  private outbox: Promise<void> = Promise.resolve()
  private readonly clock: Clock
  private readonly tokens: MediaTokenClock
  private readonly scrub = new Scrub()
  private readonly cues: CueTracks
  private ticker?: unknown
  private lastTick = 0
  private unlisten?: () => void
  private renewing = false
  private renewAfter = 0
  private note?: { text: string; until: number }
  private backgrounded = false
  private released = false

  readonly subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener) } }
  readonly snapshot = (): FilePlaybackState => this.state

  constructor(private ctx: PlaybackContext) {
    this.clock = ctx.clock ?? systemClock
    this.tokens = new MediaTokenClock(this.clock)
    this.cues = new CueTracks(ctx.fetchText ?? fetchCueText, () => this.refresh(0), (key) => this.cueFailed(key))
  }

  // ------------------------------------------------------------ commands

  /** Starts a play, ending the one before. */
  play(request: FileRequest): Promise<void> {
    if (this.released) return Promise.resolve()
    this.end('stopped')
    this.ctx.stillWatching.notePlay()
    this.ctx.stillWatching.noteInput()
    return this.begin(request)
  }

  pause() {
    this.noteInput()
    const v = this.active()
    if (!v || v.loading && !v.decision) return
    v.paused = true
    void this.ctx.player.pause().catch(() => undefined)
    this.refresh(0)
  }

  resume() {
    this.noteInput()
    const v = this.active()
    if (!v) return
    // Play after an error tries again from where it stopped.
    if (v.error && !v.loading) { v.error = undefined; this.reload(v); return }
    v.paused = false
    v.meter.excuse()
    if (v.parkedAtMs !== undefined) { this.unpark(v); return }
    void this.ctx.player.resume().catch(() => undefined)
    this.refresh(0)
  }

  togglePause() {
    const v = this.active()
    if (v && !v.paused && this.state.phase !== 'ended' && this.state.phase !== 'error') this.pause()
    else this.resume()
  }

  seekBy(deltaMs: number) { this.seekTo(this.position(this.active()) + deltaMs) }

  seekTo(targetMs: number) {
    this.noteInput()
    const v = this.active()
    if (v?.decision) this.seekWithin(v, targetMs, true)
  }

  /** A seek key went down: the target moves 10 s and the thumbnail shows it. */
  beginScrub(direction: 1 | -1) { this.scrubPress(direction, false) }

  /** The key is held: the step grows to 30 s, 1 min, then 2 min. */
  scrubStep(direction: 1 | -1) { this.scrubPress(direction, true) }

  /** The key came up: seek to the target. */
  commitScrub() {
    const target = this.scrub.release(this.clock.now())
    const v = this.active()
    if (target !== undefined && v?.decision) this.seekWithin(v, target, true)
    this.refresh(0)
  }

  /** Back while scrubbing: the target goes, nothing seeks. */
  cancelScrub() { this.scrub.clear(); this.refresh(0) }

  /** Skips the segment the Skip button offers. */
  skip() {
    this.noteInput()
    const v = this.active()
    const id = this.state.skip?.segmentId
    const segment = v?.decision?.segments.find((s) => s.id === id)
    if (v && segment) this.skipSegment(v, segment)
  }

  /** Plays the next episode now: from the card it counts this one as finished. */
  playNext() {
    this.noteInput()
    const v = this.active()
    if (v) this.goNext(v, v.watched || !!this.state.next || this.state.phase === 'ended')
  }

  /** Hides the Next episode card until the end. */
  watchCredits() {
    this.noteInput()
    const v = this.active()
    if (!v) return
    v.creditsDismissed = true
    v.countdownMs = undefined
    this.refresh(0)
  }

  /** Still watching: yes. (No is stop.) */
  keepWatching() {
    const v = this.active()
    if (!v?.askStill) return
    v.askStill = false
    this.ctx.stillWatching.noteInput()
    this.goNext(v, true)
  }

  /** Goes to what a recording still recording has written. */
  goToNow() {
    const v = this.active()
    if (!v?.decision) return
    const available = this.duration(v)
    this.seekTo(growingSeekMs(available, available))
  }

  selectAudio(index: number) {
    this.noteInput()
    const v = this.active()
    const d = v?.decision
    if (!v || !d || index === v.audioIndex) return
    const ordinal = d.audioTracks.findIndex((a) => a.index === index)
    if (ordinal < 0) return
    v.audioIndex = index
    // The file's own track, switched here, when the subtitle is the viewer's
    // and so needn't follow the audio; otherwise the server sends it.
    const own = this.ctx.player.tracks().filter((t) => t.kind === 'audio')
    if (d.protocol === 'file' && !v.subtitles.intent.automatic && !v.loading && own.length === d.audioTracks.length) {
      void this.ctx.player.selectTrack('audio', own[ordinal].index).then((switched) => {
        if (this.viewing !== v || v.stopped) return
        if (switched) this.refresh(0)
        else this.reload(v)
      })
      this.refresh(0)
      return
    }
    this.reload(v)
  }

  /** A subtitle track, or undefined for off. Image ones are burned in by the server, which restarts the stream. */
  selectSubtitle(id: string | undefined) {
    this.noteInput()
    const v = this.active()
    const d = v?.decision
    if (!v || !d) return
    const track = id === undefined ? undefined : d.subtitles.find((s) => s.id === id)
    if (id !== undefined && !track) return
    v.subtitles.choose(id ?? '')
    if (id === v.subtitleId) { this.refresh(0); return }
    v.subtitleId = id
    if (burnsIn(track)) this.reload(v, { subtitleId: id, burnIn: true })
    else if (d.burnIn) this.reload(v, { subtitleId: id, burnIn: false })
    else this.refresh(0)
  }

  /** Downloads an OpenSubtitles result and turns it on. */
  downloadSubtitle(resultId: string) {
    this.noteInput()
    const v = this.active()
    const d = v?.decision
    const online = v?.online
    const result = online?.results.find((r) => r.id === resultId)
    if (!v || !d || !online || !result || online.downloading || v.request.kind === 'recording') return
    if (result.trackId && d.subtitles.some((s) => s.id === result.trackId)) { this.selectSubtitle(result.trackId); return }
    v.subtitles.choose(v.subtitleId ?? '')
    const revision = v.subtitles.intent.capture()
    v.online = { ...online, downloading: resultId, error: undefined }
    this.refresh(0)
    const fileId = v.request.fileId
    void call<SubtitleTrack>((signal) => this.ctx.api.POST('/api/files/{id}/subtitles/download', { params: { path: { id: fileId } }, body: { resultId }, signal })).then((track) => {
      if (this.viewing !== v || v.stopped || !v.online) return
      v.online = { ...v.online, downloading: undefined, remaining: Math.max(0, v.online.remaining - 1),
        results: v.online.results.map((r) => r.id === resultId ? { ...r, trackId: track.id } : r) }
      if (!v.subtitles.intent.accepts(revision)) { this.refresh(0); return }
      v.subtitles.choose(track.id)
      v.subtitleId = track.id
      // The new track comes with the next decision, at this position and quality.
      this.reload(v, { subtitleId: track.id, burnIn: burnsIn(track) })
    }, (error: unknown) => {
      if (this.viewing !== v || !v.online) return
      v.online = { ...v.online, downloading: undefined, error: safeMessage(error, 'Couldn’t download the subtitle.'),
        remaining: error instanceof PlayerError && error.status === 429 ? 0 : v.online.remaining }
      this.refresh(0)
    })
  }

  /** A pick in the player: it holds for this play, and becomes the TV's default. */
  setQuality(id: string) {
    this.noteInput()
    const q = qualityById(id, Infinity).id
    const v = this.active()
    if (!v || q === v.quality.id) return
    v.quality.id = q
    this.ctx.saveQuality?.(q)
    this.note = undefined
    if (v.decision) this.reload(v)
    else this.refresh(0)
  }

  /** Any key: the viewer is there. */
  noteInput() { this.ctx.stillWatching.noteInput() }

  /** The app went to the background: report closed, and let go of the decoder and the server's session. */
  suspend() {
    const v = this.active()
    this.backgrounded = true
    const d = v?.decision
    if (!v || !d || v.parkedAtMs !== undefined || v.suspended) return
    const pos = this.position(v)
    v.paused = true
    this.report(v, 'closed', pos)
    if (d.sessionId) {
      // A TV keeps no transcode running while it's away (Android TV's
      // stopInBackground): the session ends, and the return asks again there.
      this.endSession(d.sessionId)
      v.decision = { ...d, sessionId: undefined }
      v.parkedAtMs = pos
      v.pendingMs = pos
      void this.ctx.player.stop().catch(() => undefined)
    } else {
      // Direct play holds nothing on the server: the player keeps its place.
      v.suspended = true
      void this.ctx.player.suspend().catch(() => undefined)
    }
    this.refresh(0)
  }

  /** Back in front: the same play where it was, paused. */
  restore() {
    this.backgrounded = false
    const v = this.active()
    if (!v) return
    if (v.parkedAtMs !== undefined) { this.unpark(v); return }
    if (v.suspended) {
      v.suspended = false
      if (this.tokens.due) this.reopen(v)
      else void this.ctx.player.restore().catch(() => undefined)
    }
    this.refresh(0)
  }

  /** Ends the play: the last report, and the server's session, go out; resolves once they have. */
  stop(): Promise<void> {
    this.end('stopped')
    this.publish({ ...IDLE, exited: true })
    return this.outbox
  }

  /** The controller's last call: stop, and nothing more starts. */
  release(): Promise<void> {
    const done = this.stop()
    this.released = true
    this.cues.close()
    return done
  }

  // ------------------------------------------------------------ the viewing's life

  private active(): Viewing | undefined {
    const v = this.viewing
    return v && !v.stopped && !v.adminStopped ? v : undefined
  }

  private begin(request: FileRequest): Promise<void> {
    const quality = { id: qualityById(this.ctx.settings().quality, Infinity).id }
    const v: Viewing = new Viewing(request, ++this.generation, quality, (playId, cancel) => this.automaticSubtitle(v, playId, cancel))
    this.viewing = v
    this.tokens.forget()
    this.scrub.clear()
    this.note = undefined
    this.unlisten?.()
    this.unlisten = this.ctx.player.listen((update) => this.onUpdate(v, update))
    this.startTicker()
    v.loading = true
    this.publish({ ...IDLE, phase: 'loading', request, title: request.title, positionMs: request.kind === 'trailer' ? 0 : request.startMs ?? 0,
      quality: { id: quality.id, options: QUALITY_OPTIONS }, subtitles: { ...IDLE.subtitles, size: this.ctx.settings().subtitleSize } })
    return this.firstLoad(v)
  }

  private async firstLoad(v: Viewing) {
    const load = ++v.loads
    const explicit = v.request.kind === 'trailer' ? 0 : v.request.startMs
    try {
      let d = await this.decide(v, explicit, undefined)
      if (!this.current(v, load)) { this.endReplaced(d, undefined); return }
      let start = explicit ?? 0
      // No explicit start: pick up where the server resumes, 5 s before where
      // the viewer left off. An event session that didn't start there is asked for again.
      const resume = explicit === undefined ? resumePlan(d) : undefined
      if (resume) {
        start = resume.fromMs
        if (resume.again) {
          const first = d
          d = await this.decide(v, start, first.playId)
          this.endReplaced(first, d)
          if (!this.current(v, load)) { this.endReplaced(d, undefined); return }
        }
      }
      await this.apply(v, d, start, false)
    } catch (error) {
      if (this.current(v, load)) this.fail(v, error)
    }
  }

  /**
   * Asks again for the same play: a track or quality change, Automatic's
   * step change, an event seek, a recovery. The playing stream goes on until
   * the new one opens, where it has got to by then (or at seekMs).
   */
  private reload(v: Viewing, o: LoadOptions = {}) {
    const atMs = o.seekMs ?? this.position(v)
    v.loading = true
    if (o.seekMs !== undefined) v.pendingMs = o.seekMs
    if (o.newToken) this.tokens.forget()
    if (o.fallback) v.fellBack = true
    const load = ++v.loads
    const paused = o.paused ?? v.paused
    this.refresh(0)
    void (async () => {
      try {
        const d = await this.decide(v, atMs, v.playId, o)
        if (!this.current(v, load)) { this.endReplaced(d, v.decision); return }
        await this.apply(v, d, atMs, paused)
      } catch (error) {
        if (this.current(v, load)) this.fail(v, error)
      }
    })()
  }

  private current(v: Viewing, load: number) {
    return this.viewing === v && !v.stopped && !v.adminStopped && v.loads === load
  }

  private profile(v: Viewing): Profile {
    const device = v.request.kind === 'file' && v.request.diagnostics?.profile
      ? v.request.diagnostics.profile
      : v.fellBack ? fallbackProfile() : samsungProfile({ hdr10: this.ctx.settings().hdr10 })
    // Automatic sends the TV's own profile, uncapped; a step caps it.
    return applyQuality(device, qualityById(v.quality.id, Infinity))
  }

  private async decide(v: Viewing, startMs: number | undefined, playId: string | undefined, o: LoadOptions = {}): Promise<Decision> {
    const selection = v.subtitles.intent.request()
    const subtitleId = o.subtitleId ?? (selection.subtitleId || undefined)
    const chosen = subtitleId === undefined ? undefined : v.decision?.subtitles.find((s) => s.id === subtitleId)
    const body: DecisionRequest = {
      profile: this.profile(v),
      quality: v.quality.id,
      client: this.ctx.client,
      playlist: v.request.kind === 'file' && v.request.diagnostics?.playlist || requestedPlaylist(v.request.kind === 'recording' ? v.request.recordingId : undefined),
      subtitleMode: selection.subtitleMode,
      ...(startMs !== undefined && { startMs: Math.max(0, Math.floor(startMs)) }),
      ...(v.audioIndex !== undefined && { audioIndex: v.audioIndex }),
      ...(subtitleId && { subtitleId }),
      ...((o.burnIn ?? burnsIn(chosen)) && { burnIn: true }),
      ...(playId && { playId }),
    }
    v.meter.asked(this.clock.now())
    let d = await this.requestDecision(v, body)
    // A default the server chose that must be burned in needs the stream that burns it.
    const selected = subtitleId ?? d.subtitleId
    const track = d.subtitles.find((s) => s.id === selected)
    if (!body.burnIn && burnsIn(track) && d.burnIn?.id !== selected) {
      const first = d
      try { d = await this.requestDecision(v, { ...body, playId: first.playId, subtitleId: selected, burnIn: true }) }
      catch (error) { this.endReplaced(first, v.decision); throw error }
      this.endReplaced(first, d)
    }
    if (!this.tokens.fresh()) {
      const token = d.mediaToken ?? await call((signal) => this.ctx.api.POST('/api/auth/media-token', { signal }))
      this.tokens.accept(token)
    }
    return d
  }

  /** One decision; a recording that is just starting answers 409 for a few seconds, and is asked again. */
  private async requestDecision(v: Viewing, body: DecisionRequest): Promise<Decision> {
    const r = v.request
    if (r.kind !== 'recording') {
      return call((signal) => this.ctx.api.POST('/api/files/{id}/playback', { params: { path: { id: r.fileId } }, body, signal }), DECIDE_TIMEOUT_MS)
    }
    for (let attempt = 0; ; attempt++) {
      try {
        const d = await call((signal) => this.ctx.api.POST('/api/live-tv/recordings/{id}/playback', { params: { path: { id: r.recordingId } }, body, signal }), DECIDE_TIMEOUT_MS)
        v.starting = false
        return d
      } catch (error) {
        const wait = error instanceof PlayerError && error.status === 409 && !v.decision ? startRetryMs(attempt, error.retryAfter, this.clock.wall()) : undefined
        if (wait === undefined || this.viewing !== v || v.stopped) { v.starting = false; throw error }
        v.starting = true
        this.refresh(0)
        await new Promise<void>((resolve) => this.clock.setTimeout(resolve, wait))
        if (this.viewing !== v || v.stopped) throw new PlayerError('Stopped.')
      }
    }
  }

  private async apply(v: Viewing, d: Decision, requestedMs: number, paused: boolean) {
    const previous = v.decision
    const offset = timelineOffset(d) * 1000
    // A reload's old stream played on meanwhile: the new one starts where it got to.
    const start = Math.max(0, v.pendingMs ?? (previous ? this.position(v) : requestedMs))
    v.decision = d
    v.playId = d.playId
    v.receivedAt = this.clock.now()
    v.recordedEndMs = undefined
    v.audioIndex = d.audio.index
    if (v.subtitles.intent.automatic) v.subtitleId = d.subtitleId
    if (d.burnIn) v.subtitleId = d.burnIn.id
    v.subtitles.setPlay(d.playId)
    v.audioOptions = d.audioTracks.map((a) => ({ index: a.index, ...audioWords(a) }))
    v.subtitleOptions = d.subtitles.map((s) => ({ id: s.id, language: s.language, burnIn: burnsIn(s), source: s.source, ...subtitleWords(s) }))
    const trickplay = usableTrickplay(d.trickplay)
    if (trickplay?.url !== previous?.trickplay?.url || !v.thumbnails !== !trickplay) {
      v.thumbnails = trickplay ? new Thumbnails(trickplay, (path) => this.sign(path), this.ctx.loadImage ?? fetchImage, () => this.refresh(0)) : undefined
    }
    if (previous && previous.sessionId !== d.sessionId) this.endReplaced(previous, d)
    // A seek while an event session was being asked for went elsewhere: ask for one there.
    if (d.protocol === 'hls' && d.playlist !== 'vod' && v.pendingMs !== undefined && v.pendingMs !== requestedMs && Math.abs(start - d.startMs) > EVENT_START_TOLERANCE_MS) {
      this.reload(v, { seekMs: start })
      return
    }
    this.noteStepChange(v, previous, d)
    v.loading = false
    v.pendingMs = undefined
    v.error = undefined
    v.paused = paused
    v.playerState = 'buffering'
    v.playerDurationMs = 0
    this.refresh(0)
    try {
      await this.ctx.player.open(this.sign(d.url), { startMs: Math.max(0, start - offset), uhd: uhd(d), paused })
    } catch (error) {
      if (this.viewing === v && v.decision === d && !v.stopped) this.onFailure(v, openFailure(error), error)
    }
  }

  /** A media path on this server with the play's token. */
  private sign(path: string): string {
    return this.ctx.mediaUrl(path, this.tokens.token ?? '')
  }

  /** The same stream with a renewed token, where it has got to: AVPlay can't swap a token in place. */
  private reopen(v: Viewing) {
    const d = v.decision
    if (!d) return
    const offset = timelineOffset(d) * 1000
    v.meter.excuse()
    void this.ctx.player.open(this.sign(d.url), { startMs: Math.max(0, this.position(v) - offset), uhd: uhd(d), paused: v.paused })
      .catch((error: unknown) => { if (this.viewing === v && v.decision === d) this.onFailure(v, openFailure(error), error) })
  }

  private unpark(v: Viewing) {
    const at = v.parkedAtMs
    if (at === undefined) return
    v.parkedAtMs = undefined
    this.reload(v, { seekMs: at })
  }

  private fail(v: Viewing, error: unknown) {
    v.loading = false
    v.starting = false
    v.error = safeMessage(error, 'Playback couldn’t start. Try again.')
    v.failure = failureOf(error)
    this.refresh(0)
  }

  private end(state: 'stopped' | 'closed') {
    const v = this.viewing
    if (!v || v.stopped) return
    const pos = this.position(v)
    v.stopped = true
    v.loading = false
    // The player stops first, before anything awaits, so a play started next isn't stopped by this one.
    this.unlisten?.(); this.unlisten = undefined
    void this.ctx.player.stop().catch(() => undefined)
    this.stopTicker()
    this.clock.clearTimeout(v.cueTimer)
    const d = v.decision
    if (d && !v.adminStopped) {
      this.report(v, state, pos, true)
      if (d.sessionId) this.endSession(d.sessionId)
    }
    v.subtitles.close()
  }

  private goNext(v: Viewing, finished: boolean) {
    const next = v.decision?.nextEpisode
    if (!next) return
    if (finished) v.finished = true
    this.end('stopped')
    void this.begin({ kind: 'file', fileId: next.fileId, title: `${next.showTitle} · ${episodeWords(next)}` })
  }

  private autoAdvance(v: Viewing) {
    if (this.ctx.stillWatching.shouldAsk()) {
      v.askStill = true
      v.countdownMs = undefined
      this.refresh(0)
      return
    }
    this.ctx.stillWatching.noteAutoAdvance()
    this.goNext(v, true)
  }

  private exit(v: Viewing) {
    v.finished = true
    this.end('stopped')
    this.publish({ ...this.state, phase: 'ended', exited: true, skip: undefined, next: undefined, scrub: undefined })
  }

  private skipSegment(v: Viewing, segment: { id: number; startMs: number; endMs?: number | null }) {
    const duration = this.duration(v)
    const end = segment.endMs || duration
    // Skipping to the end finishes the item.
    if (end >= duration - END_SLACK_MS) { this.exit(v); return }
    this.seekWithin(v, end, false)
  }

  private seekWithin(v: Viewing, targetMs: number, byViewer: boolean) {
    const d = v.decision
    if (!d) return
    const duration = this.duration(v)
    const target = d.growing ? growingSeekMs(targetMs, duration) : Math.min(Math.max(0, targetMs), duration || targetMs)
    // Back before the credits: offer the Next episode card again.
    if (d.upNextMs > 0 && target < d.upNextMs) v.creditsDismissed = false
    if (byViewer) for (const id of segmentsAt(d.segments, target / 1000, duration / 1000)) v.seekedInto.add(id)
    v.meter.excuse()
    v.seekLanded = true
    if (v.loading) { v.pendingMs = target; this.refresh(0); return }
    const offset = timelineOffset(d) * 1000
    const whole = d.protocol === 'file' || d.playlist === 'vod'
    const local = target - offset
    // An event session plays on what it has produced; outside it, a new session there, in the same play.
    const produced = Math.max(this.ctx.player.position(), v.playerDurationMs)
    if (whole || (local >= 0 && local <= produced + EVENT_SEEK_SLACK_MS)) {
      void this.ctx.player.seek(local).then(() => v.meter.settled(), () => undefined)
      this.refresh(0)
    } else this.reload(v, { seekMs: target })
  }

  // ------------------------------------------------------------ the player's updates

  private onUpdate(v: Viewing, u: PlayerUpdate) {
    if (this.viewing !== v || v.stopped || v.adminStopped) return
    const now = this.clock.now()
    if (u.durationMs !== undefined) v.playerDurationMs = u.durationMs
    if (u.bufferedMs !== undefined) v.bufferedMs = u.bufferedMs
    if (u.state && u.state !== 'idle') {
      const was = v.playerState
      v.playerState = u.state
      if (u.state === 'playing') {
        v.paused = false
        v.meter.playing(now)
        if (!v.everPlayed) { v.everPlayed = true; this.firstFrame(v) }
      } else if (u.state === 'buffering' && was !== 'buffering') {
        // Automatic at its lowest step and still stalling: say so, once.
        if (v.meter.waiting(now) && v.quality.id === AUTO && v.decision?.auto?.step === QUALITIES[QUALITIES.length - 1].id && !v.floorNoted) {
          v.floorNoted = true
          this.showNote(SLOW_CONNECTION_NOTE)
        }
      } else if (u.state === 'paused') {
        v.paused = true
        v.meter.paused(now)
      } else if (u.state === 'ended' && v.decision?.growing) {
        // The stream's end is what a recording wrote, whether it finished or stopped early.
        v.recordedEndMs = this.position(v)
      } else if (u.state === 'error') {
        this.onFailure(v, u.failure ?? { code: 'UnknownError', kind: 'other' })
        return
      }
    }
    this.refresh(0)
  }

  /** Online subtitles and an automatic one are looked for once the video plays, so pressing Play never waits on them. */
  private firstFrame(v: Viewing) {
    const r = v.request
    if (r.kind !== 'file') return
    if (!v.searched) {
      v.searched = true
      void call<SubtitleSearch>((signal) => this.ctx.api.GET('/api/files/{id}/subtitles/search', { params: { path: { id: r.fileId } }, signal }), 20_000).then((found) => {
        if (this.viewing !== v || v.stopped) return
        v.online = { available: found.available, canDownload: found.canDownload, remaining: found.quota.remaining, attribution: found.attribution,
          error: found.error ? 'The search for subtitles online failed.' : undefined,
          results: found.results.map((result) => ({ id: result.id, trackId: result.trackId, ...onlineWords(result) })) }
        this.refresh(0)
      }, () => undefined)
    }
    const playId = v.playId
    if (!v.automaticTried && playId && !v.subtitleId && asksForAutomaticSubtitle(this.ctx.settings().subtitles)) {
      v.automaticTried = true
      void v.subtitles.begin(playId).then((result) => {
        if (!result || this.viewing !== v || v.stopped) return
        if (result.error) this.showNote(AUTOMATIC_SUBTITLE_FAILED)
        const track = result.track
        if (!track || v.subtitleId) return
        v.subtitleId = track.id
        this.reload(v, { subtitleId: track.id, burnIn: burnsIn(track) })
      }, () => { if (this.viewing === v) this.showNote(AUTOMATIC_SUBTITLE_FAILED) })
    }
  }

  private automaticSubtitle(v: Viewing, playId: string, cancel = false): Promise<{ track?: SubtitleTrack; error?: string }> {
    const r = v.request
    if (r.kind === 'recording') return Promise.resolve({})
    return call((signal) => this.ctx.api.POST('/api/files/{id}/subtitles/automatic', { params: { path: { id: r.fileId } }, body: { playId, cancel }, signal }), 30_000)
  }

  private onFailure(v: Viewing, failure: PlayerFailure, cause?: unknown) {
    const d = v.decision
    if (!d || v.loading) return
    // A format this TV turned down that the profile claimed: once a viewing, ask with the plain one.
    if (failure.kind === 'format' && !v.fellBack && d.mode !== 'transcode') { this.reload(v, { fallback: true }); return }
    if (failure.kind !== 'format' && v.recoveries < MAX_RECOVERIES) {
      v.recoveries++
      v.recoveredAt = this.clock.now()
      this.reload(v, { newToken: failure.kind === 'auth' })
      return
    }
    v.error = cause instanceof PlayerError ? cause.message : `The TV couldn’t play this (${failure.code}). Try again, or choose a lower quality.`
    v.failure = { kind: failure.kind, status: failure.httpStatus ?? (cause instanceof PlayerError ? cause.status : undefined) }
    this.refresh(0)
  }

  private cueFailed(key: string) {
    const v = this.active()
    const track = v?.decision?.subtitles.find((s) => s.url && trackKey(s.url) === key)
    if (!v || !track || v.subtitleId !== track.id) return
    v.subtitles.choose('')
    v.subtitleId = undefined
    this.showNote(SUBTITLE_FAILED_NOTE)
    this.refresh(0)
  }

  // ------------------------------------------------------------ heartbeats

  private report(v: Viewing, state: BeatState, pos: number, final = false) {
    const d = v.decision
    const playId = v.playId
    if (!d || !playId || v.adminStopped || (v.stopped && !final)) return
    // After "closed", nothing but the last report until the app is back.
    if (this.backgrounded && state !== 'closed' && state !== 'stopped') return
    const now = this.clock.now()
    v.lastReported = state
    v.lastBeatAt = now
    v.seekLanded = false
    const duration = this.duration(v)
    const body = {
      playId, state,
      positionMs: Math.round(v.finished ? duration : pos),
      durationMs: Math.round(duration),
      ...(v.bufferedMs !== undefined && { bufferedMs: Math.round(v.bufferedMs) }),
      ...v.meter.report(now),
    }
    const r = v.request
    this.send(async () => {
      try {
        const reply = await call<Progress>((signal) => r.kind === 'recording'
          ? this.ctx.api.PUT('/api/live-tv/recordings/{id}/progress', { params: { path: { id: r.recordingId } }, body, signal })
          : this.ctx.api.PUT('/api/files/{id}/progress', { params: { path: { id: r.fileId } }, body, signal }), 10_000)
        this.onReply(v, reply)
      } catch { /* The next beat says it again. */ }
    })
  }

  private onReply(v: Viewing, reply: Progress) {
    if (this.viewing !== v || v.stopped || v.adminStopped) return
    if (reply.completed) v.watched = true
    // The play had ended (timed out, server restarted): carry on under the new one.
    if (reply.playId) v.playId = reply.playId
    const directive = reply.directive
    if (directive?.action === 'stop') this.adminStop(v, directive.message)
    else if (directive?.action === 'quality') this.changeStep(v, directive)
  }

  /** Automatic: the server wants another step. Ask again in the same play; the TV's default stays. */
  private changeStep(v: Viewing, directive: Directive) {
    const d = v.decision
    const step = directive.quality
    const now = this.clock.now()
    // One step change at a time, and not again before the stream had two Automatic beats to show for it.
    if (v.quality.id !== AUTO || !d || !step || v.loading || d.auto?.step === step || now - v.steppedAt < STEP_GAP_MS) return
    v.steppedAt = now
    v.stepNote = directive.message || automaticStepNote(d.auto?.step, step)
    this.reload(v)
  }

  private noteStepChange(v: Viewing, previous: Decision | undefined, d: Decision) {
    const note = v.stepNote
    v.stepNote = undefined
    const was = previous?.auto?.step, now = d.auto?.step
    if (v.quality.id === AUTO && was && now && was !== now) this.showNote(note ?? automaticStepNote(was, now))
  }

  private adminStop(v: Viewing, message?: string) {
    v.adminStopped = true
    v.stopMessage = message || STOPPED_BY_ADMIN
    v.loading = false
    this.unlisten?.(); this.unlisten = undefined
    void this.ctx.player.stop().catch(() => undefined)
    this.stopTicker()
    this.publish({ ...this.state, phase: 'paused', adminStop: { message: v.stopMessage }, skip: undefined, next: undefined, askStillWatching: false, scrub: undefined })
  }

  private endSession(sessionId: string) {
    this.send(async () => {
      await call((signal) => this.ctx.api.DELETE('/api/playback/{session}', { params: { path: { session: sessionId } }, signal }), 5000).catch(() => undefined)
    })
  }

  private endReplaced(old: Decision | undefined, now: Decision | undefined) {
    if (old?.sessionId && old.sessionId !== now?.sessionId) this.endSession(old.sessionId)
  }

  private send(task: () => Promise<void>) {
    this.outbox = this.outbox.then(task).catch(() => undefined)
  }

  // ------------------------------------------------------------ the tick

  private startTicker() {
    this.stopTicker()
    this.lastTick = this.clock.now()
    this.ticker = this.clock.setInterval(() => {
      const now = this.clock.now()
      const dt = now - this.lastTick
      this.lastTick = now
      this.refresh(dt)
    }, TICK_MS)
  }

  private stopTicker() {
    if (this.ticker !== undefined) this.clock.clearInterval(this.ticker)
    this.ticker = undefined
  }

  private duration(v: Viewing): number {
    const d = v.decision
    if (!d) return 0
    return growingDurationMs(d.growing, d.durationMs, this.clock.now() - v.receivedAt, v.recordedEndMs)
  }

  /** The file position now: where a load will start, else the player's time mapped to the file. */
  private position(v: Viewing | undefined): number {
    if (!v) return 0
    const d = v.decision
    if (v.pendingMs !== undefined || !d) return v.pendingMs ?? 0
    const raw = timelineOffset(d) * 1000 + this.ctx.player.position()
    return Math.min(Math.max(0, raw), this.duration(v) || raw)
  }

  private showNote(text: string) { this.note = { text, until: this.clock.now() + NOTE_MS } }

  /**
   * Brings the state up to date and acts on it: heartbeats, the prompts and
   * their timers (advanced by dt), the end of the file, the token.
   */
  private refresh(dt: number) {
    const v = this.viewing
    if (!v || v.stopped || v.adminStopped) return
    const now = this.clock.now()
    const settings = this.ctx.settings()
    const d = v.decision
    const base = {
      request: v.request, title: v.request.title, quality: { id: v.quality.id, options: QUALITY_OPTIONS, autoStep: v.quality.id === AUTO ? d?.auto?.step : undefined },
      askStillWatching: v.askStill, adminStop: undefined, exited: false, note: this.note && now < this.note.until ? this.note.text : undefined,
    }
    if (!d) {
      this.publish({ ...IDLE, ...base, phase: v.error ? 'error' : 'loading', error: v.error, failure: v.error ? v.failure : undefined, positionMs: v.pendingMs ?? (v.request.kind === 'trailer' ? 0 : v.request.startMs ?? 0),
        subtitles: { ...IDLE.subtitles, size: settings.subtitleSize }, recording: v.request.kind === 'recording' ? { now: false, goToNow: false, starting: v.starting } : undefined })
      return
    }
    if (v.parkedAtMs !== undefined) return
    const pos = this.position(v)
    const duration = this.duration(v)
    const ps = v.playerState
    const replacing = ps === 'idle' || (v.loading && (ps === 'ended' || v.pendingMs !== undefined))
    const waiting: FilePhase = v.everPlayed ? 'buffering' : 'loading'
    const phase: FilePhase = v.error ? 'error' : replacing ? waiting : ps === 'ended' ? 'ended' : v.paused || ps === 'paused' ? 'paused' : ps === 'buffering' ? waiting : ps === 'playing' ? 'playing' : waiting
    const ended = phase === 'ended'
    if (phase === 'playing' && v.recoveries > 0 && now - v.recoveredAt >= RECOVERY_RESET_MS) v.recoveries = 0

    let force = false
    if (!v.watched && d.watchedAtMs > 0 && pos >= d.watchedAtMs) { v.watched = true; force = true }

    // Next episode: from upNextMs, or at the end even after Watch credits.
    const next = v.request.kind === 'file' ? d.nextEpisode : undefined
    const nextOffered = !!next && d.upNextMs > 0
    const showNext = phase !== 'error' && nextCardShowing({ hasNext: !!next, upNextMs: d.upNextMs, time: pos / 1000, ended, creditsDismissed: v.creditsDismissed, askingStill: v.askStill })
    v.countdownMs = !showNext || !settings.autoplayNext ? undefined
      : v.countdownMs === undefined ? NEXT_COUNTDOWN * 1000
      // Holds while paused; runs while playing, buffering and at the end.
      : phase === 'paused' ? v.countdownMs : Math.max(0, v.countdownMs - dt)

    // Skip: the segment playing and what the viewer's setting does with it.
    const segment = v.loading || ended || phase === 'error' || showNext ? undefined : activeSegment(d.segments, pos / 1000, duration / 1000, nextOffered)
    const mode: SkipMode = segment ? settings.skip[segment.kind] ?? 'ask' : 'none'
    const plan = skipPlan(segment, duration / 1000, mode, !!segment && v.seekedInto.has(segment.id), showNext)
    if (plan.autoAfterMs !== undefined && segment?.id === v.fillSegment) { if (phase === 'playing') v.fillMs += dt }
    else { v.fillSegment = plan.autoAfterMs !== undefined ? segment?.id : undefined; v.fillMs = 0 }
    const skip = segment && (plan.showButton || plan.autoAfterMs !== undefined)
      ? { segmentId: segment.id, kind: segment.kind, label: SKIP_LABELS[segment.kind] ?? 'Skip', ...(plan.autoAfterMs !== undefined && { progress: Math.min(1, v.fillMs / AUTO_DELAY_MS) }) }
      : undefined

    // A recording still being written grows on this TV's clock until its planned end or the stream's.
    const growing = d.growing
    const recordingNow = stillRecording(growing, d.durationMs, now - v.receivedAt, v.recordedEndMs)
    const planned = recordingNow ? plannedRecordingMs(growing) : undefined
    this.scrub.settle(pos, now)
    const scrub = this.scrub.view()
    const cues = this.cuesAt(v, d, pos, phase)

    this.publish({
      ...base,
      phase, positionMs: pos, durationMs: duration, timelineMs: Math.max(duration, planned ?? 0), bufferedMs: v.bufferedMs,
      stream: streamPill(d, v.quality.id, v.audioIndex),
      mode: d.mode,
      audio: { options: v.audioOptions, selected: v.audioIndex },
      subtitles: { options: v.subtitleOptions, selected: v.subtitleId, automatic: v.subtitles.intent.automatic, online: v.online, cues, size: settings.subtitleSize },
      skip,
      next: showNext && next ? { episode: next, heading: episodeWords(next), countdownMs: v.countdownMs, seconds: v.countdownMs === undefined ? undefined : Math.ceil(v.countdownMs / 1000) } : undefined,
      scrub: scrub && { ...scrub, step: seekStepLabel(scrub.stepMs), thumbnail: v.thumbnails?.at(scrub.targetMs) },
      trickplay: !!v.thumbnails,
      recording: v.request.kind === 'recording' ? { now: recordingNow, goToNow: showGoToNow(recordingNow, pos, duration), starting: v.starting } : undefined,
      segments: d.segments,
      upNext: next,
      error: v.error,
      failure: v.error ? v.failure : undefined,
    })

    this.heartbeat(v, phase, pos, force)

    // Actions last: each may start a new load or a new play.
    if (ended && (!nextOffered || v.request.kind !== 'file')) this.exit(v)
    else if (plan.autoNow && segment && v.autoSkipped !== segment.id) { v.autoSkipped = segment.id; this.skipSegment(v, segment) }
    else if (plan.autoAfterMs !== undefined && segment && v.fillMs >= plan.autoAfterMs) { v.fillMs = 0; this.skipSegment(v, segment) }
    else if (v.countdownMs === 0) this.autoAdvance(v)
    else if (!v.loading && this.tokens.due && phase !== 'ended' && phase !== 'error') this.renewToken(v)
  }

  /** The cues showing, and a wake-up at the next change when it comes before the next tick. */
  private cuesAt(v: Viewing, d: Decision, pos: number, phase: FilePhase): readonly Cue[] {
    this.clock.clearTimeout(v.cueTimer)
    const track = v.subtitleId === undefined ? undefined : d.subtitles.find((s) => s.id === v.subtitleId)
    if (!track?.url || burnsIn(track) || d.burnIn) return IDLE.subtitles.cues
    const t = pos - timelineOffset(d) * 1000
    const { cues, next } = this.cues.at(track.url, (url) => this.sign(url), t)
    if (phase === 'playing' && next - t < TICK_MS) v.cueTimer = this.clock.setTimeout(() => this.refresh(0), Math.max(10, next - t))
    return cues
  }

  private heartbeat(v: Viewing, phase: FilePhase, pos: number, force: boolean) {
    // None while a decision is out: the server takes the first "playing" after one as its first frame.
    if (v.loading) return
    const state: BeatState = phase === 'playing' ? 'playing' : phase === 'loading' || phase === 'buffering' ? 'buffering' : 'paused'
    const changed = state !== v.lastReported
    if (!force && !changed && !beatDue(state, v.lastBeatAt, this.clock.now(), v.quality.id === AUTO, v.seekLanded)) return
    this.report(v, state, pos)
  }

  /** The token is near its end: a new one, and the stream reopened with it where it is. */
  private renewToken(v: Viewing) {
    if (this.renewing || this.clock.now() < this.renewAfter) return
    this.renewing = true
    void call((signal) => this.ctx.api.POST('/api/auth/media-token', { signal })).then((token) => {
      this.renewing = false
      if (this.viewing !== v || v.stopped) return
      this.tokens.accept(token)
      this.reopen(v)
    }, () => {
      this.renewing = false
      this.renewAfter = this.clock.now() + TOKEN_RETRY_MS
    })
  }

  // ------------------------------------------------------------ the store

  private scrubPress(direction: 1 | -1, repeat: boolean) {
    this.noteInput()
    const v = this.active()
    if (!v?.decision) return
    const started = !this.scrub.view()
    this.scrub.press(direction, this.position(v), this.duration(v), this.clock.now(), repeat)
    const target = this.scrub.view()?.targetMs
    if (started && target !== undefined) v.thumbnails?.scrubFrom(target)
    this.refresh(0)
  }

  private publish(next: FilePlaybackState) {
    if (sameState(this.state, next)) return
    this.state = next
    for (const listener of this.listeners) listener()
  }
}

/** Why opening failed: the player's own failure, or a stream that didn't open. */
const openFailure = (error: unknown): PlayerFailure => error instanceof PlaybackFailure ? error.failure : { code: 'OPEN_FAILED', kind: 'network' }

/** A play that couldn't start, as a kind and the server's status: refused (auth), unavailable or unreachable (network), else other. */
export function failureOf(error: unknown): NonNullable<FilePlaybackState['failure']> {
  if (error instanceof PlaybackFailure) return { kind: error.failure.kind, status: error.failure.httpStatus }
  if (error instanceof PlayerError && error.status) {
    const status = error.status
    return { kind: status === 401 || status === 403 ? 'auth' : status >= 500 ? 'network' : 'other', status }
  }
  if (error instanceof TypeError || (error instanceof DOMException && (error.name === 'TimeoutError' || error.name === 'AbortError'))) return { kind: 'network' }
  return { kind: 'other' }
}

/** Equal to two levels, so a tick that changed nothing doesn't redraw the player. */
function sameState(a: FilePlaybackState, b: FilePlaybackState): boolean {
  const keys = new Set([...Object.keys(a), ...Object.keys(b)]) as Set<keyof FilePlaybackState>
  for (const key of keys) {
    const x = a[key], y = b[key]
    if (Object.is(x, y)) continue
    if (!x || !y || typeof x !== 'object' || typeof y !== 'object' || Array.isArray(x) || Array.isArray(y)) return false
    const inner = new Set([...Object.keys(x), ...Object.keys(y)])
    for (const k of inner) if (!Object.is((x as Record<string, unknown>)[k], (y as Record<string, unknown>)[k])) return false
  }
  return true
}

