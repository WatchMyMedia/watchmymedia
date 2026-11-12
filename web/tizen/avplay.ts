import { UserError } from './server'

export type PlayerState = 'idle' | 'buffering' | 'playing' | 'paused' | 'ended' | 'error'
export type PlayerUpdate = { state?: PlayerState; positionMs?: number; durationMs?: number; error?: string }
export type PlayerEvents = (update: PlayerUpdate) => void
export type OpenOptions = { uhd?: boolean }
export interface Player {
  readonly name: string
  open(url: string, startMs: number, options?: OpenOptions): Promise<void>
  pause(): Promise<void>
  resume(): Promise<void>
  seek(positionMs: number): Promise<void>
  stop(): Promise<void>
}

export interface AVPlayAPI {
  open(url: string): void
  close(): void
  stop(): void
  prepareAsync(success: () => void, error: (error: unknown) => void): void
  play(): void
  pause(): void
  seekTo(ms: number, success: () => void, error: (error: unknown) => void): void
  getState(): 'NONE' | 'IDLE' | 'READY' | 'PLAYING' | 'PAUSED'
  getDuration(): number
  getVersion(): string
  setDisplayRect(x: number, y: number, width: number, height: number): void
  setDisplayMethod(mode: 'PLAYER_DISPLAY_MODE_LETTER_BOX'): void
  setStreamingProperty(type: 'SET_MODE_4K', value: 'TRUE'): void
  setListener(listener: {
    onbufferingstart: () => void
    onbufferingcomplete: () => void
    oncurrentplaytime: (time: number) => void
    onstreamcompleted: () => void
    onerror: (error: unknown) => void
  }): void
}

export type SamsungWindow = Window & {
  webapis?: { avplay?: AVPlayAPI; appcommon?: {
    AppCommonScreenSaverState: { SCREEN_SAVER_ON: number; SCREEN_SAVER_OFF: number }
    setScreenSaver(value: number, success?: () => void, failure?: () => void): void
  } }
  tizen?: {
    tvinputdevice: { registerKey(name: string): void }
    application: { getCurrentApplication(): { exit(): void } }
  }
}

function nativeError(error: unknown): Error {
  const code = typeof error === 'string' ? error : typeof error === 'object' && error && 'name' in error ? String(error.name) : ''
  // Only codes, never native error messages which can contain the source URL.
  const safe = /^[A-Za-z_]{1,80}$/.test(code) ? code : 'UnknownError'
  return new UserError(`AVPlay: ${safe}. Try the H.264 + AAC profile or another test file.`)
}

export class AVPlayPlayer implements Player {
  readonly name = 'Samsung AVPlay'
  private generation = 0
  private queue: Promise<void> = Promise.resolve()
  private paused = false
  private started = false
  private durationMs = 0

  constructor(private api: AVPlayAPI, private events: PlayerEvents, private timeoutMs = 25_000) {}

  private serial(operation: () => Promise<void> | void): Promise<void> {
    const next = this.queue.then(operation)
    this.queue = next.catch(() => undefined)
    return next
  }

  private callback(operation: (success: () => void, failure: (error: unknown) => void) => void): Promise<void> {
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => reject(new UserError('AVPlay timed out. Stop playback and try again.')), this.timeoutMs)
      const success = () => { clearTimeout(timeout); resolve() }
      const failure = (error: unknown) => { clearTimeout(timeout); reject(nativeError(error)) }
      try { operation(success, failure) } catch (error) { failure(error) }
    })
  }

  private close() {
    try { if (this.api.getState() !== 'NONE') this.api.stop() }
    finally { this.api.close() }
  }

  open(url: string, startMs: number, options: OpenOptions = {}): Promise<void> {
    const generation = ++this.generation
    return this.serial(async () => {
      if (generation !== this.generation) return
      this.close()
      this.paused = false
      this.started = false
      this.durationMs = 0
      const emit: PlayerEvents = (update) => { if (generation === this.generation) this.events(update) }
      try {
        this.api.open(url)
        // AVPlay decodes UHD only in 4K mode, which must be set between open and prepare.
        if (options.uhd) this.api.setStreamingProperty('SET_MODE_4K', 'TRUE')
        this.api.setDisplayRect(0, 0, 1920, 1080)
        this.api.setDisplayMethod('PLAYER_DISPLAY_MODE_LETTER_BOX')
        this.api.setListener({
          onbufferingstart: () => emit({ state: 'buffering' }),
          onbufferingcomplete: () => { if (this.started) emit({ state: this.paused ? 'paused' : 'playing' }) },
          oncurrentplaytime: (time) => emit({ positionMs: time }),
          onstreamcompleted: () => emit({ state: 'ended' }),
          onerror: (error) => emit({ state: 'error', error: nativeError(error).message }),
        })
        emit({ state: 'buffering', positionMs: startMs })
        await this.callback((ok, fail) => this.api.prepareAsync(ok, fail))
        if (generation !== this.generation) return
        this.durationMs = this.api.getDuration()
        emit({ durationMs: this.durationMs })
        if (startMs > 0) await this.callback((ok, fail) => this.api.seekTo(this.target(startMs), ok, fail))
        if (generation !== this.generation) return
        this.api.play()
        this.started = true
        emit({ state: 'playing' })
      } catch (error) {
        this.close()
        if (generation === this.generation) throw error
      }
    })
  }

  private target(ms: number) {
    // Samsung's seek API excludes exactly zero and the end of the video.
    return Math.round(Math.max(1, Math.min(ms, this.durationMs > 1 ? this.durationMs - 1 : ms)))
  }

  seek(ms: number): Promise<void> {
    const generation = this.generation
    return this.serial(async () => {
      if (generation !== this.generation) return
      await this.callback((ok, fail) => this.api.seekTo(this.target(ms), ok, fail))
      if (generation === this.generation) this.events({ positionMs: this.target(ms), state: this.paused ? 'paused' : 'playing' })
    })
  }

  pause(): Promise<void> {
    const generation = this.generation
    return this.serial(() => {
      if (generation !== this.generation || this.api.getState() !== 'PLAYING') return
      this.api.pause(); this.paused = true; this.events({ state: 'paused' })
    })
  }

  resume(): Promise<void> {
    const generation = this.generation
    return this.serial(() => {
      if (generation !== this.generation || this.api.getState() !== 'PAUSED') return
      this.api.play(); this.paused = false; this.events({ state: 'playing' })
    })
  }

  stop(): Promise<void> {
    ++this.generation
    return this.serial(() => { this.close(); this.events({ state: 'idle' }) })
  }
}
