import Hls from 'hls.js'
import { bufferedAhead } from '../../src/lib/play/rules'
import { PlayerError } from './api'
import type { OpenOptions, Player, PlayerListener, PlayerUpdate } from './avplay'

/**
 * Development preview only: an HTML video with hls.js, behind the same
 * interface as AVPlay. Passing here is not an AVPlay test, and the
 * production bundle never includes it.
 */
export class BrowserPlayer implements Player {
  readonly name = 'Browser preview · not AVPlay'
  private hls?: Hls
  private generation = 0
  private active = false
  private live = false
  private cancelLoad?: () => void
  private listeners = new Set<PlayerListener>()

  constructor(private video: HTMLVideoElement) {
    const emit = (update: PlayerUpdate) => { if (this.active) for (const listener of this.listeners) listener(update) }
    video.addEventListener('timeupdate', () => emit({ positionMs: video.currentTime * 1000, bufferedMs: bufferedAhead(video.buffered, video.currentTime) * 1000 }))
    video.addEventListener('playing', () => emit({ state: 'playing' }))
    video.addEventListener('pause', () => emit({ state: 'paused' }))
    video.addEventListener('waiting', () => emit({ state: 'buffering' }))
    video.addEventListener('ended', () => emit({ state: 'ended' }))
    video.addEventListener('durationchange', () => { if (Number.isFinite(video.duration)) emit({ durationMs: video.duration * 1000 }) })
    video.addEventListener('error', () => emit({ state: 'error', failure: { code: 'BROWSER_MEDIA_ERROR', kind: 'network' } }))
  }

  listen(listener: PlayerListener) {
    this.listeners.add(listener)
    return () => { this.listeners.delete(listener) }
  }

  private reset() {
    this.active = false
    this.cancelLoad?.(); this.cancelLoad = undefined
    this.hls?.destroy(); this.hls = undefined
    this.video.pause(); this.video.removeAttribute('src'); this.video.load()
  }

  async open(url: string, options: OpenOptions = {}) {
    const generation = ++this.generation
    this.reset()
    this.live = !!options.live
    const ready = new Promise<void>((resolve, reject) => {
      const cleanup = () => { clearTimeout(timeout); this.video.removeEventListener('loadedmetadata', done); this.video.removeEventListener('error', failed); this.cancelLoad = undefined }
      const done = () => { cleanup(); resolve() }
      const failed = () => { cleanup(); reject(new PlayerError('The browser preview couldn’t open this. That says nothing about the TV.')) }
      const timeout = setTimeout(failed, 25_000)
      this.cancelLoad = () => { cleanup(); reject(new DOMException('Cancelled', 'AbortError')) }
      this.video.addEventListener('loadedmetadata', done, { once: true })
      this.video.addEventListener('error', failed, { once: true })
    })
    try {
      if (new URL(url).pathname.endsWith('.m3u8') && Hls.isSupported()) {
        this.hls = new Hls(this.live ? { liveSyncDurationCount: 3 } : {})
        this.hls.on(Hls.Events.ERROR, (_event, data) => { if (data.fatal && generation === this.generation) this.video.dispatchEvent(new Event('error')) })
        this.hls.loadSource(url); this.hls.attachMedia(this.video)
      } else { this.video.src = url }
      await ready
      if (generation !== this.generation) return
      this.active = true
      if (!this.live) this.video.currentTime = (options.startMs ?? 0) / 1000
      if (options.paused) { for (const listener of this.listeners) listener({ state: 'paused' }); return }
      await this.video.play()
    } catch (error) { if (generation === this.generation) { this.reset(); throw error } }
  }

  async pause() { this.video.pause() }
  async resume() { await this.video.play() }
  async seek(ms: number) { this.video.currentTime = ms / 1000 }
  position() { return this.video.currentTime * 1000 }
  liveWindow() {
    const s = this.video.seekable
    return this.live && s.length ? { startMs: s.start(0) * 1000, endMs: s.end(s.length - 1) * 1000 } : undefined
  }
  async seekToLiveEdge() {
    const window = this.liveWindow()
    if (window) this.video.currentTime = Math.max(window.startMs, window.endMs - 3000) / 1000
  }
  // The preview leaves track changes to the server, as a TV without them would.
  tracks() { return [] }
  async selectTrack() { return false }
  async stop() { ++this.generation; this.reset(); for (const listener of this.listeners) listener({ state: 'idle' }) }
}
