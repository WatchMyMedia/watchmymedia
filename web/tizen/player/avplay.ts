import { PlayerError } from './api'

/**
 * The engine's one media backend, behind which Samsung's AVPlay stays.
 * Times are the player's own: for an event playlist that is the time since
 * the session's start, and the controllers map it to the file.
 */
export type PlayerState = 'idle' | 'buffering' | 'playing' | 'paused' | 'ended' | 'error'

/** A failure as a code (never the native message, which can hold the media URL) and what kind it is. */
export type PlayerFailure = {
  code: string
  /** network and auth can recover with a new request or token; format can't, on this stream. */
  kind: 'network' | 'auth' | 'format' | 'other'
  httpStatus?: number
}

export type PlayerTrack = {
  kind: 'video' | 'audio' | 'text'
  /** The player's own index, for selectTrack. */
  index: number
  language?: string
  codec?: string
  channels?: number
  width?: number
  height?: number
}

export type PlayerUpdate = {
  state?: PlayerState
  positionMs?: number
  durationMs?: number
  /** Buffered ahead of the position, when the player knows it (AVPlay doesn't). */
  bufferedMs?: number
  /** The video as decoded, once known. */
  video?: { width?: number; height?: number; codec?: string }
  failure?: PlayerFailure
}

export type PlayerListener = (update: PlayerUpdate) => void

/** A player operation that failed, with what kind of failure it was. */
export class PlaybackFailure extends PlayerError {
  constructor(message: string, readonly failure: PlayerFailure) { super(message) }
}

export type OpenOptions = {
  /** Where to start, in the player's time; ignored for live streams, which start at the live edge. */
  startMs?: number
  /** A stream larger than 1080p: AVPlay decodes it only in 4K mode. */
  uhd?: boolean
  /** A rolling live playlist (Live TV): starts at the live edge, and Go live seeks there. */
  live?: boolean
  /** Prepare and stay paused at startMs. */
  paused?: boolean
}

export interface Player {
  readonly name: string
  /** Updates go to every listener; the controllers drop those of plays they don't own. */
  listen(listener: PlayerListener): () => void
  open(url: string, options?: OpenOptions): Promise<void>
  pause(): Promise<void>
  resume(): Promise<void>
  seek(positionMs: number): Promise<void>
  seekToLiveEdge(): Promise<void>
  stop(): Promise<void>
  /** The position now, in the player's time. */
  position(): number
  /** A live stream's seekable window, in the player's time. */
  liveWindow(): { startMs: number; endMs: number } | undefined
  tracks(): PlayerTrack[]
  /** Switches the stream's own audio or text track; false when it can't, and the server must send it. */
  selectTrack(kind: 'audio' | 'text', index: number): Promise<boolean>
  /** The app went to the background: let go of the decoder, keeping the place. */
  suspend(): Promise<void>
  /** Back from suspend: the same media where it was, paused. */
  restore(): Promise<void>
}

type AVState = 'NONE' | 'IDLE' | 'READY' | 'PLAYING' | 'PAUSED'
type AVTrack = { index: number; type: string; extra_info: string }
type AVCallback = (success: () => void, error: (error: unknown) => void) => void

/** The parts of webapis.avplay used here (Samsung's AVPlay API reference). */
export interface AVPlayAPI {
  open(url: string): void
  close(): void
  stop(): void
  prepareAsync(...callbacks: Parameters<AVCallback>): void
  play(): void
  pause(): void
  seekTo(ms: number, success: () => void, error: (error: unknown) => void): void
  getState(): AVState
  getDuration(): number
  getCurrentTime(): number
  getVersion(): string
  getTotalTrackInfo(): AVTrack[]
  getCurrentStreamInfo(): AVTrack[]
  setSelectTrack(type: 'AUDIO' | 'TEXT', index: number): void
  setSilentSubtitle(silent: boolean): void
  getStreamingProperty(type: 'GET_LIVE_DURATION' | 'IS_LIVE'): string
  setDisplayRect(x: number, y: number, width: number, height: number): void
  setDisplayMethod(mode: 'PLAYER_DISPLAY_MODE_LETTER_BOX'): void
  setStreamingProperty(type: 'SET_MODE_4K', value: 'TRUE'): void
  suspend(): void
  restore(url: string, resumeTimeMs: number, prepare: boolean): void
  setListener(listener: {
    onbufferingstart: () => void
    onbufferingprogress?: (percent: number) => void
    onbufferingcomplete: () => void
    oncurrentplaytime: (time: number) => void
    onstreamcompleted: () => void
    onevent?: (type: string, data: string) => void
    onerror: (error: unknown) => void
    onsubtitlechange?: () => void
  }): void
}

export type SamsungWindow = Window & {
  webapis?: {
    avplay?: AVPlayAPI
    avinfo?: { isHdrTvSupport?(): boolean }
    productinfo?: { isUdPanelSupported?(): boolean }
    appcommon?: {
      AppCommonScreenSaverState: { SCREEN_SAVER_ON: number; SCREEN_SAVER_OFF: number }
      setScreenSaver(value: number, success?: () => void, failure?: () => void): void
    }
  }
  tizen?: {
    tvinputdevice: { registerKey(name: string): void }
    application: { getCurrentApplication(): { exit(): void } }
  }
}

/**
 * What the screen says of itself: an HDR panel and a UHD one. A claim to
 * check, not proof the player shows HDR10 (PLAN-SAMSUNG.md §3).
 */
export function samsungDisplay(w: SamsungWindow): { hdr: boolean; uhd: boolean } {
  const ask = (q?: () => boolean) => { try { return !!q?.() } catch { return false } }
  return { hdr: ask(w.webapis?.avinfo?.isHdrTvSupport), uhd: ask(w.webapis?.productinfo?.isUdPanelSupported) }
}

/** A native error's code: a bare identifier, or UnknownError. Messages are never kept. */
export function errorCode(error: unknown): string {
  const code = typeof error === 'string' ? error : typeof error === 'object' && error && 'name' in error ? String(error.name) : ''
  return /^[A-Za-z_]{1,80}$/.test(code) ? code : 'UnknownError'
}

/** How a failure can recover: AVPlay's codes, and the HTTP status it last reported. */
export function failureOf(code: string, httpStatus?: number): PlayerFailure {
  const kind: PlayerFailure['kind'] = httpStatus === 401 || httpStatus === 403 ? 'auth'
    : /NOT_SUPPORTED|UNSUPPORTED|INVALID_URI|NO_SUCH_FILE/.test(code) ? 'format'
    : /CONNECTION|NETWORK|TIMEOUT|GENEREIC|GENERIC|UnknownError|HTTP/.test(code) || (httpStatus ?? 0) >= 500 ? 'network'
    : 'other'
  return { code, kind, ...(httpStatus ? { httpStatus } : {}) }
}

function json(text: string): Record<string, unknown> {
  try { const value: unknown = JSON.parse(text); return value && typeof value === 'object' ? value as Record<string, unknown> : {} } catch { return {} }
}
const num = (value: unknown) => { const n = Number(value); return Number.isFinite(n) && n > 0 ? n : undefined }
const str = (value: unknown) => typeof value === 'string' && value && value !== 'und' ? value : undefined

/** AVPlay's track list: its extra_info is JSON that varies by firmware, read field by field. */
export function readTracks(tracks: AVTrack[]): PlayerTrack[] {
  const out: PlayerTrack[] = []
  for (const t of tracks ?? []) {
    const type = String(t.type).toUpperCase()
    const kind = type === 'VIDEO' ? 'video' : type === 'AUDIO' ? 'audio' : type === 'TEXT' ? 'text' : undefined
    if (!kind || !Number.isInteger(t.index)) continue
    const info = json(t.extra_info)
    out.push({ kind, index: t.index, language: str(info.language ?? info.track_lang), codec: str(info.fourCC)?.toLowerCase(),
      channels: num(info.channels), width: num(info.Width ?? info.width), height: num(info.Height ?? info.height) })
  }
  return out
}

/**
 * AVPlay is one stateful native player. Operations run one at a time; each
 * open starts a generation, and a callback or event from an older one is
 * dropped, so a late prepare can't restart a stopped play.
 */
export class AVPlayPlayer implements Player {
  readonly name = 'Samsung AVPlay'
  private generation = 0
  private queue: Promise<void> = Promise.resolve()
  private paused = false
  private started = false
  private durationMs = 0
  private live = false
  private lastPosition = 0
  private httpStatus?: number
  private source?: { url: string; options: OpenOptions }
  private suspendedAt?: number
  private listeners = new Set<PlayerListener>()

  constructor(private api: AVPlayAPI, private options: { timeoutMs?: number; width?: number; height?: number } = {}) {}

  listen(listener: PlayerListener) {
    this.listeners.add(listener)
    return () => { this.listeners.delete(listener) }
  }

  private emit(generation: number, update: PlayerUpdate) {
    if (generation !== this.generation) return
    if (update.positionMs !== undefined) this.lastPosition = update.positionMs
    for (const listener of this.listeners) listener(update)
  }

  private serial(operation: () => Promise<void> | void): Promise<void> {
    const next = this.queue.then(operation)
    this.queue = next.catch(() => undefined)
    return next
  }

  private callback(operation: AVCallback): Promise<void> {
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => reject(new PlaybackFailure('The TV’s player didn’t answer. Stop and try again.', { code: 'TIMEOUT', kind: 'network' })), this.options.timeoutMs ?? 25_000)
      const success = () => { clearTimeout(timeout); resolve() }
      const failure = (error: unknown) => {
        clearTimeout(timeout)
        const code = errorCode(error)
        reject(new PlaybackFailure(`The TV couldn’t play this (${code}).`, failureOf(code, this.httpStatus)))
      }
      try { operation(success, failure) } catch (error) { failure(error) }
    })
  }

  private close() {
    try { if (this.api.getState() !== 'NONE') this.api.stop() }
    finally { this.api.close() }
  }

  private listenNative(generation: number) {
    const emit = (update: PlayerUpdate) => this.emit(generation, update)
    this.api.setListener({
      onbufferingstart: () => emit({ state: 'buffering' }),
      onbufferingcomplete: () => { if (this.started) emit({ state: this.paused ? 'paused' : 'playing' }) },
      oncurrentplaytime: (time) => emit({ positionMs: time }),
      onstreamcompleted: () => emit({ state: 'ended' }),
      onevent: (type, data) => {
        // The status of a refused request, as a number: never the message around it.
        if (type === 'PLAYER_MSG_HTTP_ERROR_CODE') this.httpStatus = num(data)
      },
      onerror: (error) => emit({ state: 'error', failure: failureOf(errorCode(error), this.httpStatus) }),
    })
  }

  open(url: string, options: OpenOptions = {}): Promise<void> {
    const generation = ++this.generation
    return this.serial(async () => {
      if (generation !== this.generation) return
      this.close()
      this.paused = !!options.paused
      this.started = false
      this.durationMs = 0
      this.live = !!options.live
      this.httpStatus = undefined
      this.suspendedAt = undefined
      this.source = { url, options }
      try {
        this.api.open(url)
        // AVPlay decodes UHD only in 4K mode, which must be set between open and prepare.
        if (options.uhd) this.api.setStreamingProperty('SET_MODE_4K', 'TRUE')
        this.api.setDisplayRect(0, 0, this.options.width ?? 1920, this.options.height ?? 1080)
        this.api.setDisplayMethod('PLAYER_DISPLAY_MODE_LETTER_BOX')
        this.listenNative(generation)
        const startMs = this.live ? 0 : Math.max(0, options.startMs ?? 0)
        this.emit(generation, { state: 'buffering', positionMs: startMs })
        await this.callback((ok, fail) => this.api.prepareAsync(ok, fail))
        if (generation !== this.generation) return
        this.durationMs = this.api.getDuration()
        // Subtitles are drawn by the engine from the server's WebVTT, never by AVPlay.
        try { this.api.setSilentSubtitle(true) } catch { /* Not every firmware has it; the engine draws over it. */ }
        this.emit(generation, { durationMs: this.durationMs, video: this.videoInfo() })
        if (startMs > 0) await this.callback((ok, fail) => this.api.seekTo(this.target(startMs), ok, fail))
        if (generation !== this.generation) return
        this.started = true
        if (this.paused) { this.emit(generation, { state: 'paused', positionMs: startMs }); return }
        this.api.play()
        this.emit(generation, { state: 'playing' })
      } catch (error) {
        this.close()
        if (generation === this.generation) throw error
      }
    })
  }

  private videoInfo() {
    try {
      const video = readTracks(this.api.getCurrentStreamInfo()).find((t) => t.kind === 'video')
      return video ? { width: video.width, height: video.height, codec: video.codec } : undefined
    } catch { return undefined }
  }

  private target(ms: number) {
    // Samsung's seek API excludes exactly zero and the end of the video.
    return Math.round(Math.max(1, Math.min(ms, this.durationMs > 1 ? this.durationMs - 1 : ms)))
  }

  seek(ms: number): Promise<void> {
    const generation = this.generation
    return this.serial(async () => {
      if (generation !== this.generation || !this.started) return
      const target = this.live ? Math.round(Math.max(0, ms)) : this.target(ms)
      await this.callback((ok, fail) => this.api.seekTo(target, ok, fail))
      this.emit(generation, { positionMs: target, state: this.paused ? 'paused' : 'playing' })
    })
  }

  liveWindow() {
    if (!this.live || !this.started) return undefined
    try {
      const [start, end] = this.api.getStreamingProperty('GET_LIVE_DURATION').split('|').map(Number)
      return Number.isFinite(start) && Number.isFinite(end) && end >= start ? { startMs: start, endMs: end } : undefined
    } catch { return undefined }
  }

  seekToLiveEdge(): Promise<void> {
    const window = this.liveWindow()
    // Three seconds back from the newest segment, as the web player's Go live is.
    return window ? this.seek(Math.max(window.startMs, window.endMs - 3000)) : Promise.resolve()
  }

  pause(): Promise<void> {
    const generation = this.generation
    return this.serial(() => {
      if (generation !== this.generation || this.api.getState() !== 'PLAYING') return
      this.api.pause(); this.paused = true; this.emit(generation, { state: 'paused' })
    })
  }

  resume(): Promise<void> {
    const generation = this.generation
    return this.serial(() => {
      const state = this.api.getState()
      if (generation !== this.generation || (state !== 'PAUSED' && state !== 'READY')) return
      this.api.play(); this.paused = false; this.emit(generation, { state: 'playing' })
    })
  }

  position() {
    try { return this.started ? this.api.getCurrentTime() : this.lastPosition } catch { return this.lastPosition }
  }

  tracks(): PlayerTrack[] {
    try { return this.started ? readTracks(this.api.getTotalTrackInfo()) : [] } catch { return [] }
  }

  selectTrack(kind: 'audio' | 'text', index: number): Promise<boolean> {
    const generation = this.generation
    let done = false
    return this.serial(() => {
      if (generation !== this.generation || !this.started) return
      if (!this.tracks().some((t) => t.kind === kind && t.index === index)) return
      try { this.api.setSelectTrack(kind === 'audio' ? 'AUDIO' : 'TEXT', index); done = true } catch { done = false }
    }).then(() => done)
  }

  suspend(): Promise<void> {
    const generation = this.generation
    return this.serial(() => {
      if (generation !== this.generation || !this.started || this.suspendedAt !== undefined) return
      this.suspendedAt = this.position()
      if (this.api.getState() === 'PLAYING') this.api.pause()
      this.paused = true
      try { this.api.suspend() } catch { /* The native player let go already. */ }
      this.emit(generation, { state: 'paused' })
    })
  }

  restore(): Promise<void> {
    const generation = this.generation
    return this.serial(() => {
      const source = this.source, at = this.suspendedAt
      if (generation !== this.generation || !source || at === undefined) return
      this.suspendedAt = undefined
      try { this.api.restore(source.url, Math.round(at), true) }
      catch (error) { this.emit(generation, { state: 'error', failure: failureOf(errorCode(error), this.httpStatus) }); return }
      this.emit(generation, { state: 'paused', positionMs: at })
    })
  }

  stop(): Promise<void> {
    const generation = ++this.generation
    return this.serial(() => {
      this.close()
      this.started = false
      this.source = undefined
      this.suspendedAt = undefined
      for (const listener of this.listeners) if (generation === this.generation) listener({ state: 'idle' })
    })
  }
}
