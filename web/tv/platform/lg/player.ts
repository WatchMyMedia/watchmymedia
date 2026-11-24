import Hls, { type HlsConfig } from 'hls.js'
import { bufferedAhead } from '../../../src/lib/play/rules'
import { PlaybackFailure, type OpenOptions, type Player, type PlayerFailure, type PlayerListener, type PlayerTrack, type PlayerUpdate } from '../../player'

/** The parts of hls.js used here, so tests can stand in for it. */
export type HlsLike = {
  loadSource(url: string): void
  attachMedia(media: HTMLMediaElement): void
  destroy(): void
  on(event: typeof Hls.Events.ERROR, listener: (event: string, data: HlsError) => void): void
}
export type HlsError = { type: string; details: string; fatal: boolean; response?: { code?: number } }
export type HlsMaker = { isSupported(): boolean; create(config: Partial<HlsConfig>): HlsLike }

const realHls: HlsMaker = {
  isSupported: () => Hls.isSupported(),
  create: (config) => new Hls(config) as unknown as HlsLike,
}

/**
 * hls.js's buffer on a TV: half a minute ahead, a minute at most, and no
 * more than 60 MB, so a 4K stream doesn't take the TV's memory. Back buffer
 * is let go after 30 s.
 */
export const HLS_CONFIG: Partial<HlsConfig> = {
  maxBufferLength: 30,
  maxMaxBufferLength: 60,
  maxBufferSize: 60 * 1000 * 1000,
  backBufferLength: 30,
}

/** A failure of the video element, as a code and kind: never its message, which can hold the address. */
export function videoFailure(code: number | undefined): PlayerFailure {
  switch (code) {
    case 2: return { code: 'MEDIA_ERR_NETWORK', kind: 'network' }
    case 3: return { code: 'MEDIA_ERR_DECODE', kind: 'format' }
    case 4: return { code: 'MEDIA_ERR_SRC_NOT_SUPPORTED', kind: 'format' }
    default: return { code: 'MEDIA_ERR_ABORTED', kind: 'other' }
  }
}

/** A fatal hls.js error as a failure: refused requests are auth, codecs the TV turned down are format. */
export function hlsFailure(data: Pick<HlsError, 'type' | 'details' | 'response'>): PlayerFailure {
  const status = data.response?.code
  const details = /^[A-Za-z]{1,60}$/.test(data.details) ? data.details : 'unknown'
  const code = `HLS_${details}`
  const httpStatus = status && status >= 100 ? status : undefined
  const kind: PlayerFailure['kind'] = status === 401 || status === 403 ? 'auth'
    : /IncompatibleCodecs|AddCodec|bufferAppendError|fragParsingError/.test(details) || data.type === 'mediaError' ? 'format'
    : data.type === 'networkError' ? 'network'
    : 'other'
  return { code, kind, ...(httpStatus ? { httpStatus } : {}) }
}

const isHls = (url: string) => { try { return new URL(url).pathname.endsWith('.m3u8') } catch { return false } }

/**
 * The LG TV's player: its one HTML video, with HLS through hls.js on Media
 * Source Extensions and files given to the video directly. webOS plays one
 * video at a time, so this is the app's only one. hls.js rather than
 * webOS's own HLS: the server's playlists use tags LG lists as unsupported
 * (independent segments, the discontinuity sequence), and hls.js reports
 * what Automatic quality reads. Track switching is left to the server, so
 * another audio track comes as a new stream.
 */
export class WebOSPlayer implements Player {
  readonly name = 'LG webOS'
  private hls?: HlsLike
  private generation = 0
  private active = false
  private failed = false
  private live = false
  private cancelLoad?: () => void
  private listeners = new Set<PlayerListener>()

  constructor(private video: HTMLVideoElement, private hlsMaker: HlsMaker = realHls, private timeoutMs = 25_000) {
    const v = video
    v.addEventListener('timeupdate', () => this.emit({ positionMs: v.currentTime * 1000, bufferedMs: bufferedAhead(v.buffered, v.currentTime) * 1000 }))
    v.addEventListener('playing', () => this.emit({ state: 'playing' }))
    v.addEventListener('pause', () => { if (!v.ended) this.emit({ state: 'paused' }) })
    v.addEventListener('waiting', () => this.emit({ state: 'buffering' }))
    v.addEventListener('ended', () => this.emit({ state: 'ended' }))
    v.addEventListener('durationchange', () => { if (Number.isFinite(v.duration)) this.emit({ durationMs: v.duration * 1000 }) })
    v.addEventListener('resize', () => this.emit({ video: this.videoInfo() }))
    v.addEventListener('error', () => this.fail(videoFailure(v.error?.code)))
  }

  listen(listener: PlayerListener) {
    this.listeners.add(listener)
    return () => { this.listeners.delete(listener) }
  }

  private emit(update: PlayerUpdate) {
    if (this.active) for (const listener of this.listeners) listener(update)
  }

  /** The first failure of a play goes to the engine, which recovers; the rest are the same failure. */
  private fail(failure: PlayerFailure) {
    if (!this.active || this.failed) return
    this.failed = true
    this.emit({ state: 'error', failure })
  }

  private videoInfo() {
    const { videoWidth: width, videoHeight: height } = this.video
    return width && height ? { width, height } : undefined
  }

  private reset() {
    this.active = false
    this.failed = false
    this.cancelLoad?.(); this.cancelLoad = undefined
    try { this.hls?.destroy() } catch { /* Already gone. */ }
    this.hls = undefined
    this.video.pause()
    this.video.removeAttribute('src')
    this.video.load()
  }

  async open(url: string, options: OpenOptions = {}) {
    const generation = ++this.generation
    this.reset()
    this.live = !!options.live
    const startMs = this.live ? 0 : Math.max(0, options.startMs ?? 0)
    let failure: PlayerFailure | undefined
    const ready = new Promise<void>((resolve, reject) => {
      const cleanup = () => { clearTimeout(timeout); this.video.removeEventListener('loadedmetadata', done); this.video.removeEventListener('error', failed); this.cancelLoad = undefined }
      const done = () => { cleanup(); resolve() }
      const failed = () => {
        cleanup()
        const f = failure ?? (this.video.error ? videoFailure(this.video.error.code) : { code: 'TIMEOUT', kind: 'network' as const })
        reject(new PlaybackFailure(`The TV couldn’t play this (${f.code}).`, f))
      }
      const timeout = setTimeout(failed, this.timeoutMs)
      this.cancelLoad = () => { cleanup(); reject(new DOMException('Cancelled', 'AbortError')) }
      this.video.addEventListener('loadedmetadata', done, { once: true })
      this.video.addEventListener('error', failed, { once: true })
    })
    try {
      if (isHls(url) && this.hlsMaker.isSupported()) {
        // A resume starts loading where it resumes, rather than at 0 and then seeking.
        const hls = this.hlsMaker.create({ ...HLS_CONFIG, ...(this.live ? { liveSyncDurationCount: 3 } : { startPosition: startMs / 1000 }) })
        this.hls = hls
        hls.on(Hls.Events.ERROR, (_event, data) => {
          if (!data.fatal || generation !== this.generation) return
          if (this.active) { this.fail(hlsFailure(data)); return }
          failure = hlsFailure(data)
          this.video.dispatchEvent(new Event('error'))
        })
        hls.loadSource(url)
        hls.attachMedia(this.video)
      } else {
        this.video.src = url
      }
      await ready
      if (generation !== this.generation) return
      this.active = true
      this.emit({ state: 'buffering', positionMs: startMs, video: this.videoInfo() })
      if (Number.isFinite(this.video.duration)) this.emit({ durationMs: this.video.duration * 1000 })
      if (startMs > 0 && Math.abs(this.video.currentTime * 1000 - startMs) > 500) this.video.currentTime = startMs / 1000
      if (options.paused) { this.emit({ state: 'paused', positionMs: startMs }); return }
      await this.video.play()
    } catch (error) {
      if (generation !== this.generation) return
      this.reset()
      throw error instanceof PlaybackFailure || (error instanceof DOMException && error.name === 'AbortError') ? error
        : new PlaybackFailure('The TV couldn’t start this.', { code: 'PLAY_REFUSED', kind: 'other' })
    }
  }

  async pause() { if (this.active) this.video.pause() }
  async resume() { if (this.active) await this.video.play() }

  async seek(ms: number) {
    if (!this.active) return
    const window = this.liveWindow()
    const target = window ? Math.min(Math.max(ms, window.startMs), window.endMs) : Math.max(0, ms)
    this.video.currentTime = target / 1000
    this.emit({ positionMs: target })
  }

  position() { return this.video.currentTime * 1000 }

  liveWindow() {
    const s = this.video.seekable
    return this.live && s.length ? { startMs: s.start(0) * 1000, endMs: s.end(s.length - 1) * 1000 } : undefined
  }

  async seekToLiveEdge() {
    const window = this.liveWindow()
    // Three seconds back from the newest segment, as the web player's Go live is.
    if (window) await this.seek(Math.max(window.startMs, window.endMs - 3000))
  }

  tracks(): PlayerTrack[] { return [] }
  async selectTrack() { return false }

  async stop() {
    ++this.generation
    this.reset()
    for (const listener of this.listeners) listener({ state: 'idle' })
  }
}
