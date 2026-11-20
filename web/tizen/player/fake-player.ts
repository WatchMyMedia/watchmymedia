import type { OpenOptions, Player, PlayerListener, PlayerState, PlayerTrack, PlayerUpdate } from './avplay'

/**
 * A Player for tests and screen previews: it does what it's told at once,
 * records each call, and plays only when advance() moves its time.
 */
export class FakePlayer implements Player {
  readonly name = 'Fake player'
  readonly calls: string[] = []
  readonly opened: { url: string; options: OpenOptions }[] = []
  positionMs = 0
  state: PlayerState = 'idle'
  window?: { startMs: number; endMs: number }
  trackList: PlayerTrack[] = []
  /** Whether selectTrack switches. */
  selects = true
  /** Opening reaches playing (or paused) by itself. */
  autoPlay = true
  /** Runs inside open, before it resolves: a test's hold or failure. */
  openHook?: (url: string, options: OpenOptions) => Promise<void> | void
  private listeners = new Set<PlayerListener>()

  listen(listener: PlayerListener) {
    this.listeners.add(listener)
    return () => { this.listeners.delete(listener) }
  }

  emit(update: PlayerUpdate) {
    if (update.positionMs !== undefined) this.positionMs = update.positionMs
    if (update.state) this.state = update.state
    for (const listener of [...this.listeners]) listener(update)
  }

  get listening() { return this.listeners.size }
  get lastUrl() { return this.opened.at(-1)?.url }

  async open(url: string, options: OpenOptions = {}) {
    this.calls.push('open')
    this.opened.push({ url, options })
    this.emit({ state: 'buffering', positionMs: options.live ? 0 : options.startMs ?? 0 })
    await this.openHook?.(url, options)
    if (this.autoPlay && this.opened.at(-1)?.url === url) this.emit({ state: options.paused ? 'paused' : 'playing' })
  }

  async pause() { this.calls.push('pause'); this.emit({ state: 'paused' }) }
  async resume() { this.calls.push('resume'); this.emit({ state: 'playing' }) }
  async seek(ms: number) { this.calls.push(`seek:${Math.round(ms)}`); this.emit({ positionMs: ms }) }
  async seekToLiveEdge() {
    this.calls.push('live-edge')
    if (this.window) this.emit({ positionMs: this.window.endMs - 3000 })
  }
  async stop() { this.calls.push('stop'); this.emit({ state: 'idle' }) }
  position() { return this.positionMs }
  liveWindow() { return this.window }
  tracks() { return this.trackList }
  async selectTrack(kind: 'audio' | 'text', index: number) { this.calls.push(`track:${kind}:${index}`); return this.selects }
  async suspend() { this.calls.push('suspend'); this.emit({ state: 'paused' }) }
  async restore() { this.calls.push('restore'); this.emit({ state: 'paused' }) }

  /** Plays on for ms: the position moves while playing. */
  advance(ms: number) {
    if (this.state === 'playing') this.emit({ positionMs: this.positionMs + ms })
  }
}
