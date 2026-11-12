import Hls from 'hls.js'
import type { Player, PlayerEvents } from './avplay'
import { UserError } from './server'

/** Development preview only. Passing this backend is not an AVPlay test. */
export class BrowserPlayer implements Player {
  readonly name = 'Browser preview · not AVPlay'
  private hls?: Hls
  private generation = 0
  private active = false
  private cancelLoad?: () => void
  constructor(private video: HTMLVideoElement, events: PlayerEvents) {
    const emit: PlayerEvents = (update) => { if (this.active) events(update) }
    video.addEventListener('timeupdate', () => emit({ positionMs: video.currentTime * 1000 }))
    video.addEventListener('playing', () => emit({ state: 'playing' }))
    video.addEventListener('pause', () => emit({ state: 'paused' }))
    video.addEventListener('waiting', () => emit({ state: 'buffering' }))
    video.addEventListener('ended', () => emit({ state: 'ended' }))
    video.addEventListener('error', () => emit({ state: 'error', error: 'Browser playback failed. This does not establish TV compatibility.' }))
  }
  private reset() {
    this.active = false
    this.cancelLoad?.(); this.cancelLoad = undefined
    this.hls?.destroy(); this.hls = undefined
    this.video.pause(); this.video.removeAttribute('src'); this.video.load()
  }
  async open(url: string, startMs: number) {
    const generation = ++this.generation
    this.reset()
    const ready = new Promise<void>((resolve, reject) => {
      const cleanup = () => { clearTimeout(timeout); this.video.removeEventListener('loadedmetadata', done); this.video.removeEventListener('error', failed); this.cancelLoad = undefined }
      const done = () => { cleanup(); resolve() }
      const failed = () => { cleanup(); reject(new UserError('Browser preview could not open this media. Try H.264 + AAC.')) }
      const timeout = setTimeout(failed, 25_000)
      this.cancelLoad = () => { cleanup(); reject(new DOMException('Cancelled', 'AbortError')) }
      this.video.addEventListener('loadedmetadata', done, { once: true })
      this.video.addEventListener('error', failed, { once: true })
    })
    try {
      if (new URL(url).pathname.endsWith('.m3u8') && Hls.isSupported()) {
        this.hls = new Hls()
        this.hls.on(Hls.Events.ERROR, (_event, data) => { if (data.fatal && generation === this.generation) this.video.dispatchEvent(new Event('error')) })
        this.hls.loadSource(url); this.hls.attachMedia(this.video)
      } else { this.video.src = url }
      await ready
      if (generation !== this.generation) return
      this.active = true
      this.video.currentTime = startMs / 1000
      await this.video.play()
    } catch (error) { if (generation === this.generation) { this.reset(); throw error } }
  }
  async pause() { this.video.pause() }
  async resume() { await this.video.play() }
  async seek(ms: number) { this.video.currentTime = ms / 1000 }
  async stop() { ++this.generation; this.reset() }
}
