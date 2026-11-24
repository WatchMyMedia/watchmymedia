import type { LiveProfile, LiveQuality } from '../../src/api/liveTV'
import { LIVE_QUALITIES, liveFailure, nextLiveChannel, liveViewingPrompt, pauseExpired } from '../../src/lib/liveTV'
import { takeoverWords } from '../../src/lib/liveRecordings'
import { LiveTVController, type LiveState, type LiveTransport } from '../../src/lib/liveTVController'
import { withRequestTimeout } from '../../src/lib/requestTimeout'
import { call, reasonOf, type LiveCapabilities, type LiveChannel, type LiveSession, type PlayerApi } from './api'
import type { Player, PlayerFailure, PlayerUpdate } from './player'
import { randomId, systemClock, type Clock } from './clock'
import { MediaTokenClock } from './tokens'

// ---------------------------------------------------------------- the transport

/** A server's refusal of a live request, as its reason code (tuners_busy, no_signal), or none. */
export class LiveRefused extends Error {
  constructor(readonly reason: string) { super(reason) }
}

/** While a channel is starting, the server holds a status read this long until it plays (API level 28). */
export const STATUS_WAIT_MS = 5_000

/**
 * The live session routes for the TV, modelled on the receiver's
 * castLiveTransport: the start carries the TV's live profile, and Automatic
 * only when the server offers it. A refusal becomes its reason code alone,
 * never the server's sentence. Network failures are thrown as they are, so
 * the controller retries an ambiguous start with the same request ID.
 */
export function tvLiveTransport(api: PlayerApi, clientName: string, profile: LiveProfile, automatic: () => boolean): LiveTransport {
  const refused = (error: unknown, status: number) => new LiveRefused(reasonOf(error) ?? (status === 403 ? 'not_allowed' : status === 503 ? 'tuner_unavailable' : ''))
  const reply = async <T>(request: (signal: AbortSignal) => Promise<{ data?: T; error?: unknown; response: Response }>, ms?: number): Promise<T> => {
    const res = await withRequestTimeout(request, ms)
    if (!res.response.ok) throw refused(res.error, res.response.status)
    return res.data as T
  }
  return {
    async start(channelId, quality, requestId) {
      const auto = quality === 'auto' && automatic()
      const body = { channelId, requestId, clientName, quality: quality === 'auto' && !auto ? '1080' as const : quality, profile, ...(auto && { autoRecovery: true }) }
      const start = () => reply((signal) => api.POST('/api/live-tv/sessions', { body, signal }))
      // A lost reply is retried once with the same request ID, which can't allocate a second tuner.
      try { return await start() } catch (error) {
        if (error instanceof TypeError || (error instanceof DOMException && error.name === 'TimeoutError')) return start()
        throw error
      }
    },
    async stop(id) {
      await reply((signal) => api.DELETE('/api/live-tv/sessions/{id}', { params: { path: { id } }, keepalive: true, signal }))
    },
    status: (id) => reply((signal) => api.GET('/api/live-tv/sessions/{id}', { params: { path: { id }, query: { waitMs: STATUS_WAIT_MS } }, signal }), STATUS_WAIT_MS + 10_000),
    heartbeat: (id, paused, interacted, measurement) => reply((signal) => api.PUT('/api/live-tv/sessions/{id}', { params: { path: { id } }, body: { paused, interacted, ...measurement }, signal })),
  }
}

// ---------------------------------------------------------------- typing a channel number

/** A bounded entry with one subchannel dot; Backspace (\b) takes the last character off. */
export function editChannelNumber(number: string, key: string): string {
  if (key === '\b') return number.slice(0, -1)
  if (number.length >= 10) return number
  if (/^\d$/.test(key)) return number + key
  if (key === '.' && number && !number.includes('.')) return `${number}.`
  return number
}

/** The one available channel with exactly this number. */
export function exactChannel(channels: readonly LiveChannel[], number: string): LiveChannel | undefined {
  const matches = channels.filter((c) => c.available && c.number === number)
  return matches.length === 1 ? matches[0] : undefined
}

/** What the number entry says under the digits (Android TV's ChannelNumberDialog). */
export function channelNumberHint(channels: readonly LiveChannel[], number: string): string {
  const match = exactChannel(channels, number)
  if (!number) return 'Enter the channel number, including its dot.'
  if (match) return `${match.number} · ${match.name}`
  if (channels.filter((c) => c.available && c.number === number).length > 1) return `More than one channel uses ${number}. Choose one from Channels.`
  if (channels.some((c) => c.number.startsWith(number))) return 'Keep entering the full channel number.'
  return `No channel ${number}. Check the number and try again.`
}

// ---------------------------------------------------------------- the controller

export type LiveContext = {
  api: PlayerApi
  player: Player
  mediaUrl: (path: string, token: string) => string
  /** Named in Activity: "Living room TV". */
  clientName: string
  /** What this TV plays live (the platform's). */
  profile: LiveProfile
  /** The TV's Live TV quality, and saving a pick. */
  quality?: () => LiveQuality
  saveQuality?: (quality: LiveQuality) => void
  clock?: Clock
  requestId?: () => string
}

export type LivePhase = 'idle' | 'loading' | 'tuning' | 'playing' | 'ended' | 'error'

export type LivePlaybackState = {
  phase: LivePhase
  channel?: LiveChannel
  /** In the server's order, which channel up and down follow. */
  channels: readonly LiveChannel[]
  session?: LiveSession
  paused: boolean
  /** Paused, or more than 7 s behind the broadcast: Go live is offered. */
  behind: boolean
  /** While paused: when the server gives the tuner back. */
  pauseLeftSeconds?: number
  /** Digits typed and not yet tuned; OK tunes the match. */
  number?: { digits: string; match?: LiveChannel; hint: string }
  previousChannelId?: string
  quality: LiveQuality
  qualities: readonly LiveQuality[]
  /** Automatic's current step: "720p", "Original", "Starting". */
  autoStep?: string
  askStillWatching: boolean
  /** Why it stopped or couldn't start; recordingId when a recording took the tuner and can be watched from the start. */
  failure?: { reason?: string; title: string; detail: string; recordingId?: string; recordingTitle?: string }
  note?: string
  /** A recording will take this tuner soon. */
  takeover?: { words: string; recordingId?: string; title?: string; canCancel: boolean }
}

const BEHIND_MS = 7_000
const TICK_MS = 1_000
const HEARTBEAT_TICKS = 10
const NOTE_MS = 5_000
const CHANNELS_FRESH_MS = 60_000
const TOKEN_RETRY_MS = 30_000
const MAX_RETRIES = 2

const IDLE: LivePlaybackState = { phase: 'idle', channels: [], paused: false, behind: false, quality: '1080', qualities: LIVE_QUALITIES.filter((q) => q !== 'auto'), askStillWatching: false }

/**
 * A Live TV player for the TV: the shared LiveTVController
 * owns the tuner (one start/stop lane, idempotent starts, heartbeats), and
 * this puts its session on the Player, follows the channel list's order,
 * keeps the previous channel and a typed number, pauses within the server's
 * buffer, asks Still watching after 4 hours and gives the tuner back on stop.
 */
export class LivePlayback {
  private state: LivePlaybackState = IDLE
  private listeners = new Set<() => void>()
  private readonly clock: Clock
  private readonly controller: LiveTVController
  private readonly tokens: MediaTokenClock
  private capabilities?: LiveCapabilities
  private channelsAt = 0
  private loading?: Promise<void>
  private channelId?: string
  private quality: LiveQuality
  private ticks = 0
  private ticker?: unknown
  private unlisten?: () => void
  private loaded?: { session: string; url: string }
  private pendingSwitch?: { session: string; generation: number; previousUrl: string }
  private playerPaused = false
  private pausedAt?: number
  private everPlayed = false
  private waiting = false
  private stalls = 0
  private dismissedTakeover = ''
  private note?: { text: string; until: number }
  private suspendedChannel?: string
  private renewing = false
  private renewAfter = 0
  private released = false
  private opening = false
  private retries = 0
  private ownFailure?: LivePlaybackState['failure']

  readonly subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener) } }
  readonly snapshot = (): LivePlaybackState => this.state

  constructor(private ctx: LiveContext) {
    this.clock = ctx.clock ?? systemClock
    this.tokens = new MediaTokenClock(this.clock)
    this.quality = ctx.quality?.() ?? '1080'
    this.controller = new LiveTVController(tvLiveTransport(ctx.api, ctx.clientName, ctx.profile, () => !!this.capabilities?.automatic), ctx.requestId ?? randomId)
    this.controller.subscribe(() => this.sync())
  }

  // ------------------------------------------------------------ commands

  /** Tunes a channel: the first call reads what the server allows and its channels. */
  async open(channelId: string): Promise<void> {
    if (this.released) return
    this.listen()
    this.ownFailure = undefined
    this.opening = true
    this.refresh()
    try { await this.load() } finally { this.opening = false }
    if (!this.capabilities) { this.fail({ title: 'Live TV couldn’t load', detail: 'Check the connection to the server and try again.' }); return }
    await this.tune(channelId)
  }

  channelUp() { this.step(1) }
  channelDown() { this.step(-1) }

  /** Back to the channel watched before this one. */
  previousChannel() {
    const previous = this.state.previousChannelId
    if (previous) void this.tune(previous)
  }

  /** A digit or the dot of a channel number; nothing tunes until OK. */
  typeDigit(key: string) {
    this.noteInput()
    const digits = editChannelNumber(this.state.number?.digits ?? '', key)
    this.publishNumber(digits)
  }

  deleteDigit() { this.typeDigit('\b') }

  /** OK: tunes the channel the number names exactly; a partial number never changes channel. */
  commitNumber(): boolean {
    const digits = this.state.number?.digits ?? ''
    const match = exactChannel(this.state.channels, digits)
    if (!match) return false
    this.publish({ ...this.state, number: undefined })
    void this.tune(match.id)
    return true
  }

  clearNumber() { this.publish({ ...this.state, number: undefined }) }

  pause() {
    this.noteInput()
    const session = this.controller.getSnapshot().session
    if (this.controller.getSnapshot().phase !== 'playing' || !session) return
    // A switch waiting for its first frame isn't shown while paused: back to what played.
    if (this.pendingSwitch) this.rejectSwitch()
    this.pausedAt ??= this.clock.now()
    this.playerPaused = true
    void this.ctx.player.pause().catch(() => undefined)
    this.controller.setPaused(session.id, true)
    this.refresh()
  }

  resume() {
    this.noteInput()
    const live = this.controller.getSnapshot()
    if (live.phase === 'ended' || live.phase === 'error') { this.retry(); return }
    const session = live.session
    if (live.phase !== 'playing' || !session) return
    // A pause longer than the server keeps, or out of the window: on at live.
    const held = this.pausedAt === undefined ? 0 : this.clock.now() - this.pausedAt
    const window = this.ctx.player.liveWindow()
    const position = this.ctx.player.position()
    if (held >= (session.bufferSeconds || this.capabilities?.bufferSeconds || 60) * 1000 || (window && pauseExpired(position, window.startMs, window.endMs))) {
      void this.ctx.player.seekToLiveEdge().catch(() => undefined)
      this.showNote('Your pause passed the buffer. Resuming at live.')
    }
    this.pausedAt = undefined
    this.playerPaused = false
    void this.ctx.player.resume().catch(() => undefined)
    this.controller.setPaused(session.id, false)
    this.refresh()
  }

  togglePause() { if (this.playerPaused) this.resume(); else this.pause() }

  goLive() {
    this.noteInput()
    const live = this.controller.getSnapshot()
    if (live.phase === 'ended' || live.phase === 'error') { this.retry(); return }
    if (live.phase !== 'playing' || !live.session) return
    void this.ctx.player.seekToLiveEdge().catch(() => undefined)
    if (this.playerPaused) {
      this.playerPaused = false
      this.pausedAt = undefined
      void this.ctx.player.resume().catch(() => undefined)
      this.controller.setPaused(live.session.id, false)
    }
    this.showNote('Playing live.')
    this.refresh()
  }

  setQuality(quality: LiveQuality) {
    this.noteInput()
    // Before the server said whether it does Automatic, a pick waits for the answer.
    if (!(this.capabilities ? this.state.qualities : LIVE_QUALITIES).includes(quality) || quality === this.quality) return
    this.quality = quality
    this.ctx.saveQuality?.(quality)
    if (this.channelId) void this.switch(this.channelId)
    else this.refresh()
  }

  /** Still watching: yes. (No is stop.) */
  keepWatching() {
    this.noteInput()
    void this.controller.poll(true)
  }

  /** Any key: the viewer is there, which keeps the tuner past four hours. */
  noteInput() { this.controller.noteInput() }

  retry() {
    if (this.channelId) void this.switch(this.channelId)
  }

  dismissTakeover() {
    const session = this.controller.getSnapshot().session
    if (session?.takeoverAt) this.dismissedTakeover = takeoverKey(session)
    this.refresh()
  }

  /** Leaves Live TV: the tuner is given back. Resolves once the server has it. */
  stop(): Promise<void> {
    this.channelId = undefined
    this.ownFailure = undefined
    this.detach()
    this.stopTicker()
    this.unlisten?.(); this.unlisten = undefined
    const done = this.controller.stop()
    this.publish({ ...this.state, phase: 'idle', session: undefined, askStillWatching: false, number: undefined, failure: undefined, takeover: undefined })
    return done
  }

  /** The app went to the background: the tuner is given back, and the channel tuned again on return. */
  suspend() {
    if (!this.channelId) return
    this.suspendedChannel = this.channelId
    void this.stop()
  }

  restore() {
    const channel = this.suspendedChannel
    this.suspendedChannel = undefined
    if (channel) void this.open(channel)
  }

  release(): Promise<void> {
    this.released = true
    return this.stop()
  }

  // ------------------------------------------------------------ tuning

  private async load() {
    if (this.capabilities && this.clock.now() - this.channelsAt < CHANNELS_FRESH_MS) return
    this.loading ??= (async () => {
      try {
        const [caps, channels] = await Promise.all([
          call<LiveCapabilities>((signal) => this.ctx.api.GET('/api/live-tv/capabilities', { signal })),
          call((signal) => this.ctx.api.GET('/api/live-tv/channels', { signal })),
        ])
        this.capabilities = caps
        this.channelsAt = this.clock.now()
        const qualities = LIVE_QUALITIES.filter((q) => q !== 'auto' || caps.automatic)
        if (!qualities.includes(this.quality)) this.quality = '1080'
        this.publish({ ...this.state, channels: channels.items, qualities, quality: this.quality })
      } finally { this.loading = undefined }
    })()
    try { await this.loading } catch { /* The last list stays; open says when there is none. */ }
  }

  private step(direction: 1 | -1) {
    this.noteInput()
    const from = this.channelId ?? this.state.channel?.id
    const next = from ? nextLiveChannel([...this.state.channels], from, direction) : undefined
    if (next) void this.tune(next.id)
  }

  private async tune(channelId: string) {
    this.noteInput()
    const caps = this.capabilities
    if (!caps) return
    const channel = this.state.channels.find((c) => c.id === channelId)
    if (!caps.enabled || !caps.allowed || !caps.playback) {
      this.fail({ reason: 'not_allowed', title: 'Live TV isn’t available to you', detail: 'An admin can let everyone watch Live TV on this server.' }, channel)
      return
    }
    if (!channel || !channel.available) {
      // Watching another: it plays on, and a note says why nothing changed.
      const phase = this.controller.getSnapshot().phase
      if (phase === 'playing' || phase === 'tuning') { this.showNote(`${channel?.number ?? 'That channel'} isn’t available right now.`); this.refresh(); return }
      this.fail({ title: 'This channel isn’t available', detail: 'Choose another channel from the list.' }, channel)
      return
    }
    // The channel left behind is the previous one, once it had played.
    const leaving = this.channelId
    const previous = leaving && leaving !== channelId && this.everPlayed ? leaving : this.state.previousChannelId
    this.publish({ ...this.state, channel, previousChannelId: previous === channelId ? leaving : previous, number: undefined, failure: undefined })
    await this.switch(channelId)
  }

  private fail(failure: NonNullable<LivePlaybackState['failure']>, channel?: LiveChannel) {
    this.ownFailure = failure
    this.publish({ ...this.state, channel: channel ?? this.state.channel })
    this.refresh()
  }

  private switch(channelId: string): Promise<void> {
    this.ownFailure = undefined
    this.channelId = channelId
    this.listen()
    this.startTicker()
    this.detach()
    const selected: LiveQuality = this.quality === 'auto' && !this.capabilities?.automatic ? '1080' : this.quality
    return this.controller.switchTo(channelId, selected)
  }

  private listen() {
    this.unlisten ??= this.ctx.player.listen((update) => this.onUpdate(update))
  }

  /** The player lets go of the stream; the tuner is the controller's. */
  private detach() {
    if (this.loaded || this.pendingSwitch) void this.ctx.player.stop().catch(() => undefined)
    this.loaded = undefined
    this.pendingSwitch = undefined
    this.playerPaused = false
    this.pausedAt = undefined
    this.everPlayed = false
    this.waiting = false
    this.stalls = 0
  }

  /** The controller's state changed: put its session on the player, or take it off. */
  private sync() {
    const live = this.controller.getSnapshot()
    const session = live.session
    if (live.phase === 'playing' && session) {
      const url = session.automatic && session.directive?.url ? session.directive.url : session.url
      if (this.loaded?.session !== session.id) {
        this.detach()
        this.retries = 0
        this.load_(session, url)
      } else if (this.loaded.url !== url && !this.pendingSwitch && session.directive) {
        // Automatic prepared another output: it plays from its first frame, or goes back.
        this.pendingSwitch = { session: session.id, generation: session.directive.generation, previousUrl: this.loaded.url }
        this.load_(session, url)
      }
    } else if (live.phase !== 'tuning') this.detach()
    this.refresh()
  }

  private load_(session: LiveSession, url: string) {
    this.loaded = { session: session.id, url }
    void this.withToken().then((token) => {
      if (this.loaded?.session !== session.id || this.loaded.url !== url) return
      return this.ctx.player.open(this.ctx.mediaUrl(url, token), { live: true })
    }).catch(() => this.failed(session.id))
  }


  private async withToken(): Promise<string> {
    const fresh = this.tokens.fresh()
    if (fresh) return fresh
    this.tokens.accept(await call((signal) => this.ctx.api.POST('/api/auth/media-token', { signal })))
    return this.tokens.token!
  }

  private onUpdate(u: PlayerUpdate) {
    const live = this.controller.getSnapshot()
    const session = live.session
    if (!session || this.loaded?.session !== session.id) return
    if (u.state === 'playing') {
      this.everPlayed = true
      this.waiting = false
      this.playerPaused = false
      const pending = this.pendingSwitch
      if (pending?.session === session.id) {
        this.pendingSwitch = undefined
        void this.controller.settleOutput(session.id, pending.generation, false)
      }
      this.controller.setPaused(session.id, false)
      this.measure(session.id, 'playing')
    } else if (u.state === 'buffering') {
      if (this.everPlayed && !this.waiting) this.stalls++
      this.waiting = true
      this.measure(session.id, 'buffering')
    } else if (u.state === 'paused') {
      this.playerPaused = true
      this.controller.setPaused(session.id, true)
      this.measure(session.id, 'paused')
    } else if (u.state === 'error' || u.state === 'ended') {
      this.failed(session.id, u.state === 'error' ? u.failure : undefined)
      return
    }
    this.refresh()
  }

  private measure(id: string, state: 'playing' | 'paused' | 'buffering') {
    // What the TV knows truthfully: its state and its rebuffers. AVPlay gives no transfer timing.
    this.controller.reportPlayback(id, { state, stalls: this.stalls })
  }

  /**
   * The stream failed or ended: a switch goes back; a dropped connection or a
   * refused token opens it again, twice a session; otherwise ask the server
   * why before calling it a failure.
   */
  private failed(id: string, failure?: PlayerFailure) {
    if (this.pendingSwitch?.session === id) { this.rejectSwitch(); return }
    const session = this.controller.getSnapshot().session
    if (failure && failure.kind !== 'format' && this.retries < MAX_RETRIES && session?.id === id && this.loaded?.session === id) {
      this.retries++
      if (failure.kind === 'auth') this.tokens.forget()
      this.load_(session, this.loaded.url)
      return
    }
    void this.controller.poll(false).then(() => {
      const current = this.controller.getSnapshot()
      if (current.session?.id === id && (current.phase === 'playing' || current.phase === 'tuning')) void this.controller.stop('encoder_failed')
    })
  }

  private rejectSwitch() {
    const pending = this.pendingSwitch
    const session = this.controller.getSnapshot().session
    if (!pending || !session || session.id !== pending.session) return
    this.pendingSwitch = undefined
    void this.controller.settleOutput(pending.session, pending.generation, true)
    this.loaded = { session: pending.session, url: pending.previousUrl }
    this.load_(session, pending.previousUrl)
  }

  // ------------------------------------------------------------ the tick

  private startTicker() {
    if (this.ticker !== undefined) return
    this.ticks = 0
    this.ticker = this.clock.setInterval(() => this.tick(), TICK_MS)
  }

  private stopTicker() {
    if (this.ticker !== undefined) this.clock.clearInterval(this.ticker)
    this.ticker = undefined
  }

  private tick() {
    this.ticks++
    const live = this.controller.getSnapshot()
    const heartbeat = this.ticks % HEARTBEAT_TICKS === 0
    if (live.phase === 'tuning' || heartbeat) void this.controller.poll(heartbeat)
    // Four hours without a key: ask, and a minute later give the tuner back.
    const prompt = liveViewingPrompt(live.lastInput, this.clock.wall())
    if (prompt === 'expired' && (live.phase === 'playing' || live.phase === 'tuning')) void this.controller.stop('inactive')
    // The token runs out under a playing stream: a new one, and the stream opened again at live.
    if (this.tokens.due && this.loaded && live.session && !this.renewing && this.clock.now() >= this.renewAfter) this.renew(live.session)
    // The programme on now ended: the list's now and next are read again.
    const ends = Date.parse(this.state.channel?.now?.end ?? '')
    if (Number.isFinite(ends) && ends <= this.clock.wall() && this.clock.now() - this.channelsAt >= CHANNELS_FRESH_MS) void this.load()
    this.refresh()
  }

  private renew(session: LiveSession) {
    this.renewing = true
    void call((signal) => this.ctx.api.POST('/api/auth/media-token', { signal })).then((token) => {
      this.renewing = false
      this.tokens.accept(token)
      if (this.loaded?.session === session.id) this.load_(session, this.loaded.url)
    }, () => {
      this.renewing = false
      this.renewAfter = this.clock.now() + TOKEN_RETRY_MS
    })
  }

  private showNote(text: string) { this.note = { text, until: this.clock.now() + NOTE_MS } }

  private refresh() {
    const live: LiveState = this.controller.getSnapshot()
    const session = live.session
    const wall = this.clock.wall()
    const now = this.clock.now()
    const channel = this.state.channels.find((c) => c.id === (session?.channel.id ?? this.channelId)) ?? this.state.channel
    const window = this.ctx.player.liveWindow()
    const behind = this.playerPaused || (!!window && window.endMs - this.ctx.player.position() > BEHIND_MS)
    const pauseLeftSeconds = session?.paused
      ? Math.max(0, session.pauseRemainingSeconds - Math.max(0, Math.floor((wall - Date.parse(session.lastSeenAt)) / 1000)))
      : undefined
    const adaptive = session?.directive ? session.directive.adaptive : session?.adaptive
    const autoStep = !session?.automatic ? undefined : adaptive ? 'Starting'
      : session.actualQuality === 'original' ? 'Original' : session.actualQuality ? `${session.actualQuality}p` : 'Starting'
    const key = session?.takeoverAt ? takeoverKey(session) : ''
    const takeover = session?.takeoverAt && live.phase === 'playing' && key !== this.dismissedTakeover
      ? { words: `${takeoverWords(session.takeoverAt, session.takeoverTitle ?? 'a programme', wall)} You can watch the recording from the start.`, recordingId: session.takeoverRecordingId, title: session.takeoverTitle, canCancel: !!session.takeoverCanCancel }
      : undefined
    const own = live.phase === 'idle' || live.phase === 'ended' ? this.ownFailure : undefined
    const phase: LivePhase = own ? 'error' : live.phase === 'idle' && this.opening ? 'loading' : live.phase
    const failure = own ?? (live.phase === 'ended' || live.phase === 'error' ? endedWords(live.error, session) : undefined)
    this.publish({
      ...this.state,
      phase, channel, session, behind, pauseLeftSeconds, autoStep, takeover, failure,
      paused: this.playerPaused,
      quality: this.quality,
      askStillWatching: live.phase === 'playing' && liveViewingPrompt(live.lastInput, wall) === 'ask',
      note: this.note && now < this.note.until ? this.note.text : undefined,
    })
  }

  private publishNumber(digits: string) {
    this.publish({ ...this.state, number: { digits, match: exactChannel(this.state.channels, digits), hint: channelNumberHint(this.state.channels, digits) } })
  }

  private publish(next: LivePlaybackState) {
    const keys = Object.keys(next) as (keyof LivePlaybackState)[]
    if (keys.length === Object.keys(this.state).length && keys.every((k) => Object.is(next[k], this.state[k]))) return
    this.state = next
    for (const listener of this.listeners) listener()
  }
}

/** Why the channel stopped, in the shared words; a code they don't know reads as the general failure, never as itself. */
function endedWords(error: string | undefined, session: LiveSession | undefined): NonNullable<LivePlaybackState['failure']> {
  const reason = error && /^[a-z_]+$/.test(error) ? error : undefined
  const known = liveFailure(reason)
  const words = known.detail === reason ? liveFailure(undefined) : known
  return { reason, ...words, ...(reason === 'recording' && session?.takeoverRecordingId ? { recordingId: session.takeoverRecordingId, recordingTitle: session.takeoverTitle } : {}) }
}

const takeoverKey = (s: LiveSession) => `${s.takeoverRecordingId ?? ''}@${s.takeoverAt ?? ''}`
