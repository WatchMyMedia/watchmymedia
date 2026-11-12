import type { LiveQuality, LiveSession } from '../api/liveTV'

export type LiveMeasurement = {
  adaptiveFailed?: boolean
  renditionHeight?: number
  renditionBitrate?: number
  state?: 'playing' | 'paused' | 'buffering'
  bufferedMs?: number
  throughputBps?: number
  throughputAgeMs?: number
  stalls?: number
  switchGeneration?: number
  switchFailedGeneration?: number
}

export interface LiveTransport {
  /** Retries must preserve requestId. Network/abort/JSON errors may follow an
   * allocation; ordinary Error rejections represent definite server refusals. */
  start(channel: string, quality: LiveQuality, requestId: string): Promise<LiveSession>
  stop(id: string): Promise<void>
  status(id: string): Promise<LiveSession>
  heartbeat(id: string, paused: boolean, interacted: boolean, measurement?: LiveMeasurement): Promise<LiveSession>
}

type Selection = { channel: string; quality: LiveQuality }
type StartRequest = Selection & { requestId: string }
type Poll = { generation: number; id: string; heartbeatPending: boolean; done: Promise<void> }

function ambiguousStart(error: unknown): boolean {
  return error instanceof TypeError || error instanceof SyntaxError ||
    (error instanceof DOMException && (error.name === 'AbortError' || error.name === 'TimeoutError'))
}
export type LiveState = {
  phase: 'idle' | 'tuning' | 'playing' | 'ended' | 'error'
  session?: LiveSession
  error?: string
  lastInput: number
}

/** One serialized start/stop lane per player. Late starts are stopped before
 * a newer request can acquire a tuner, including unmount and StrictMode. */
export class LiveTVController {
  private generation = 0
  private current?: LiveSession
  private selection?: Selection
  private unresolved?: StartRequest
  private lane: Promise<void> = Promise.resolve()
  private listeners = new Set<() => void>()
  private state: LiveState = { phase: 'idle', lastInput: Date.now() }
  private interacted = false
  private paused = false
  private polling?: Poll
  private measurement: LiveMeasurement = {}
  private switchOutcome?: { generation: number; failed: boolean }
  constructor(private transport: LiveTransport, private requestId: () => string = () => crypto.randomUUID()) {}
  getSnapshot = (): LiveState => this.state
  subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener) } }
  private publish(state: Partial<LiveState>) { this.state = { ...this.state, ...state }; this.listeners.forEach((listener) => listener()) }
  noteInput = () => { this.interacted = true; this.publish({ lastInput: Date.now() }) }
  private async start(request: StartRequest) {
    try {
      const session = await this.transport.start(request.channel, request.quality, request.requestId)
      this.unresolved = undefined
      this.current = session
      return session
    } catch (error) {
      // A response lost after allocation is still owned by this player. Resolve
      // its original key before retuning or stopping; never allocate beside it.
      if (ambiguousStart(error)) this.unresolved = request
      throw error
    }
  }
  private async release() {
    if (this.unresolved) await this.start(this.unresolved)
    if (!this.current) return
    await this.transport.stop(this.current.id)
    this.current = undefined
  }
  /** Repeated active selections share their operation. An ended/error selection
   * retries; an explicit restart of active playback requires Stop first. */
  switchTo(channel: string, quality: LiveQuality): Promise<void> {
    if (this.selection?.channel === channel && this.selection.quality === quality &&
      (this.state.phase === 'tuning' || this.state.phase === 'playing')) return this.lane
    const generation = ++this.generation
    this.selection = { channel, quality }
    this.paused = false
    this.measurement = {}
    this.switchOutcome = undefined
    this.noteInput()
    this.publish({ phase: 'tuning', session: undefined, error: undefined })
    const requestId = this.requestId()
    this.lane = this.lane.then(async () => {
      if (generation !== this.generation) return
      // Retry the same ambiguous selection in place. A different selection must
      // first recover and release it, just like a known session.
      if (this.unresolved?.channel !== channel || this.unresolved.quality !== quality) await this.release()
      if (generation !== this.generation) return
      const session = await this.start(this.unresolved ?? { channel, quality, requestId })
      if (generation !== this.generation) { await this.release(); return }
      this.accept(session)
    }).catch((error: unknown) => {
      if (generation === this.generation) this.publish({ phase: 'error', error: error instanceof Error ? error.message : 'Live TV could not start.' })
    })
    return this.lane
  }
  stop(reason?: string): Promise<void> {
    const generation = ++this.generation
    this.selection = undefined
    this.paused = false
    this.measurement = {}
    this.switchOutcome = undefined
    this.publish({ phase: reason ? 'ended' : 'idle', error: reason, session: undefined })
    this.lane = this.lane.then(() => this.release()).catch((error: unknown) => {
      if (generation === this.generation) this.publish({ phase: 'error', error: error instanceof Error ? error.message : 'The tuner could not be released.' })
    })
    return this.lane
  }
  private accept(session: LiveSession) {
    this.current = session
    const phase = session.state === 'playing' ? 'playing' : session.state === 'starting' ? 'tuning' : 'ended'
    if (!session.adaptive) this.measurement.adaptiveFailed = false
    this.publish({ session, phase, error: session.reason })
  }
  private active(generation: number, id: string) {
    return generation === this.generation && this.current?.id === id && this.state.session?.id === id &&
      (this.state.phase === 'tuning' || this.state.phase === 'playing')
  }
  setPaused(id: string, paused: boolean) {
    if (!this.active(this.generation, id) || this.paused === paused) return
    this.paused = paused
    void this.poll(true)
  }
  reportPlayback(id: string, measurement: LiveMeasurement) {
    if (!this.active(this.generation, id) || !this.current?.automatic) return
    this.measurement = { ...measurement, adaptiveFailed: this.measurement.adaptiveFailed || measurement.adaptiveFailed }
  }
  settleOutput(id: string, generation: number, failed: boolean): Promise<void> {
    const session = this.current
    if (!session || session.id !== id || !session.automatic || !session.directive ||
      session.directive.generation !== generation || generation <= (session.outputGeneration ?? 0)) return Promise.resolve()
    this.switchOutcome = { generation, failed }
    return this.poll(true)
  }
  /** Poll status while tuning and heartbeat every 10 s while mounted. Concurrent
   * calls coalesce; a heartbeat queued behind status/heartbeat is never dropped. */
  poll(heartbeat: boolean): Promise<void> {
    const session = this.current
    if (!session || !this.active(this.generation, session.id)) return Promise.resolve()
    if (this.polling?.generation === this.generation && this.polling.id === session.id) {
      this.polling.heartbeatPending ||= heartbeat
      return this.polling.done
    }
    const poll: Poll = { generation: this.generation, id: session.id, heartbeatPending: false, done: Promise.resolve() }
    this.polling = poll
    poll.done = this.runPoll(poll, heartbeat)
    return poll.done
  }
  private async runPoll(poll: Poll, heartbeat: boolean): Promise<void> {
    try {
      do {
        poll.heartbeatPending = false
        const interacted = heartbeat && this.interacted
        if (heartbeat) this.interacted = false
        try {
          const outcome = this.switchOutcome
          const measurement = this.current?.automatic ? {
            ...this.measurement,
            ...(outcome ? { [outcome.failed ? 'switchFailedGeneration' : 'switchGeneration']: outcome.generation } : {}),
          } : undefined
          const update = await (heartbeat
            ? measurement ? this.transport.heartbeat(poll.id, this.paused, interacted, measurement) : this.transport.heartbeat(poll.id, this.paused, interacted)
            : this.transport.status(poll.id))
          if (!this.active(poll.generation, poll.id)) return
          if (update.id !== poll.id) throw new Error('The server returned a different Live TV session.')
          if (heartbeat && outcome === this.switchOutcome) this.switchOutcome = undefined
          this.accept(update)
        } catch (error) {
          if (!this.active(poll.generation, poll.id)) return
          if (interacted) this.interacted = true
          // Detach media on a connection failure so media reads cannot renew a
          // tuner forever behind an error screen. Retain failed cleanup for Retry.
          const cleanup = this.lane = this.lane.then(async () => {
            if (this.current?.id === poll.id) await this.release()
          }).catch(() => { /* release keeps ownership until Stop succeeds */ })
          this.publish({ phase: 'error', session: undefined, error: error instanceof Error ? error.message : 'Connection to the server was lost.' })
          await cleanup
          return
        }
        heartbeat = true
      } while (poll.heartbeatPending && this.active(poll.generation, poll.id))
    } finally {
      // Clear before resolving done, so a heartbeat in the next microtask cannot
      // queue behind an already completed request. An old poll owns only itself.
      if (this.polling === poll) this.polling = undefined
    }
  }
}
