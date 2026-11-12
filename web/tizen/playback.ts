import { beatDue, type BeatState } from '../src/lib/play/rules'
import type { ClientInfo, Decision, MediaFile, ProgressBody } from './types'
import { ApiError, mediaUrl, result, safeError, timed, type TVClient } from './server'
import { filePosition, playerPosition, prototypeProfile, uhd, type ProfileId } from './profile'
import type { Player, PlayerState, PlayerUpdate } from './avplay'

export type PlaybackState = {
  state: PlayerState
  file?: MediaFile
  decision?: Decision
  positionMs: number
  error?: string
  reportError?: string
}

/** One controller belongs to one server/account. Recreate it when either changes. */
export class PlaybackController {
  private generation = 0
  private intent = 0
  private request?: AbortController
  private timer?: ReturnType<typeof setInterval>
  private lastBeat = 0
  private beat: Promise<void> = Promise.resolve()
  private profile: ProfileId = 'safe'
  private playlist: 'vod' | 'event' = 'vod'
  private state: PlaybackState = { state: 'idle', positionMs: 0 }
  private listeners = new Set<() => void>()
  readonly subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener) } }
  readonly snapshot = () => this.state

  constructor(private api: TVClient, private baseUrl: string, private client: ClientInfo, private player: Player) {}

  private update(patch: Partial<PlaybackState>) { this.state = { ...this.state, ...patch }; for (const listener of this.listeners) listener() }

  readonly onPlayerUpdate = (event: PlayerUpdate) => {
    const decision = this.state.decision
    if (!decision || event.state === 'idle') return
    const oldState = this.state.state
    this.update({ ...event, positionMs: event.positionMs === undefined ? this.state.positionMs : filePosition(decision, event.positionMs) })
    if (event.state === 'ended') { this.update({ positionMs: decision.durationMs }); void this.stop(); return }
    if (event.state === 'error') { void this.stop('stopped', event.error); return }
    if (event.state && event.state !== oldState && ['playing', 'paused', 'buffering'].includes(event.state)) {
      void this.report(event.state as BeatState)
    }
  }

  async play(file: MediaFile, profile: ProfileId, playlist: 'vod' | 'event') {
    const intent = ++this.intent
    await this.halt()
    if (intent !== this.intent) return
    this.profile = profile; this.playlist = playlist
    this.update({ file, error: undefined, reportError: undefined, positionMs: 0 })
    await this.load(undefined)
  }

  private async load(startMs: number | undefined) {
    const file = this.state.file
    if (!file) return
    const generation = ++this.generation
    this.request?.abort()
    const request = new AbortController(); this.request = request
    const prior = this.state.decision
    const wasPaused = this.state.state === 'paused'
    this.update({ state: 'buffering', error: undefined })
    try {
      await this.player.stop()
      if (generation !== this.generation) return
      let decision = result(await timed((signal) => this.api.POST('/api/files/{id}/playback', {
        params: { path: { id: file.id } }, signal,
        body: { profile: prototypeProfile(this.profile), quality: this.profile === 'transcode' ? '480' : 'original',
          playlist: this.playlist, client: this.client, ...(startMs !== undefined && { startMs }), ...(prior && { playId: prior.playId }) },
      }), request.signal, 45_000))
      if (generation !== this.generation) { await this.release(decision); return }
      this.update({ decision })
      const resume = startMs ?? (decision.resumeMs > 5000 ? decision.resumeMs : 0)
      // Event playlists start at the requested position; a first decision may
      // discover resume after starting at zero. Restart that same viewing there.
      if (decision.protocol === 'hls' && decision.playlist !== 'vod' && resume > decision.startMs + 1000) {
        const previous = decision
        decision = result(await timed((signal) => this.api.POST('/api/files/{id}/playback', {
          params: { path: { id: file.id } }, signal,
          body: { profile: prototypeProfile(this.profile), quality: this.profile === 'transcode' ? '480' : 'original',
            playlist: this.playlist, client: this.client, startMs: resume, playId: decision.playId },
        }), request.signal, 45_000))
        if (previous.sessionId !== decision.sessionId) await this.release(previous)
      }
      if (generation !== this.generation) { await this.release(decision); return }
      this.update({ decision, positionMs: resume })
      const token = result(await timed((signal) => this.api.POST('/api/auth/media-token', { signal }), request.signal))
      if (generation !== this.generation) return
      const url = mediaUrl(this.baseUrl, decision.url, token.token)
      await this.player.open(url, playerPosition(decision, resume), { uhd: uhd(decision) })
      if (generation !== this.generation) return
      if (wasPaused) await this.player.pause()
      this.update({ state: wasPaused ? 'paused' : 'playing' })
      this.lastBeat = 0
      clearInterval(this.timer)
      this.timer = setInterval(() => {
        const state = this.state.state
        if ((state === 'playing' || state === 'paused' || state === 'buffering') && beatDue(state, this.lastBeat, Date.now())) void this.report(state)
      }, 1000)
      await this.report(wasPaused ? 'paused' : 'playing')
    } catch (error) {
      if (generation !== this.generation) return
      await this.stop('stopped', safeError(error))
    }
  }

  async togglePause() {
    const generation = this.generation
    try {
      if (this.state.state === 'paused') await this.player.resume()
      else if (this.state.state === 'playing') await this.player.pause()
    } catch (error) {
      if (generation === this.generation) await this.stop('stopped', safeError(error))
    }
  }

  async seek(deltaMs: number) {
    const decision = this.state.decision
    if (!decision || !['playing', 'paused'].includes(this.state.state)) return
    const generation = this.generation
    const target = Math.max(0, Math.min(this.state.positionMs + deltaMs, decision.durationMs - 1000))
    try {
      if (decision.protocol === 'hls' && decision.playlist !== 'vod') {
        await this.report('paused')
        if (generation === this.generation) await this.load(target)
      } else {
        await this.player.seek(playerPosition(decision, target))
        if (generation !== this.generation) return
        this.update({ positionMs: target })
        await this.report(this.state.state === 'paused' ? 'paused' : 'playing')
      }
    } catch (error) {
      if (generation === this.generation) await this.stop('stopped', safeError(error))
    }
  }

  private report(state: BeatState) {
    const { decision, file, positionMs } = this.state
    if (!decision || !file) return Promise.resolve()
    this.lastBeat = Date.now()
    const generation = this.generation
    const body: ProgressBody = { playId: decision.playId, positionMs: Math.round(positionMs), durationMs: decision.durationMs, state }
    // Serialize reports so a delayed playing heartbeat cannot overwrite stop.
    this.beat = this.beat.then(async () => {
      if (generation !== this.generation && state !== 'stopped' && state !== 'closed') return
      try {
        const progress = result(await timed((signal) => this.api.PUT('/api/files/{id}/progress', { params: { path: { id: file.id } }, body, signal }), undefined, 5000))
        if (generation !== this.generation || state === 'stopped' || state === 'closed') return
        this.update({ reportError: undefined, decision: progress.playId ? { ...decision, playId: progress.playId } : this.state.decision })
        if (progress.directive?.action === 'stop') void this.stop('stopped', 'Playback was stopped by the server.')
      } catch (error) {
        if (generation !== this.generation) return
        this.update({ reportError: 'Progress could not be saved. Check the server connection.' })
        if (error instanceof ApiError && error.status === 401) void this.stop('stopped', error.message)
      }
    })
    return this.beat
  }

  private async release(decision?: Decision) {
    if (decision?.sessionId) await timed((signal) => this.api.DELETE('/api/playback/{session}', { params: { path: { session: decision.sessionId! } }, signal }), undefined, 5000).catch(() => undefined)
  }

  async stop(state: 'stopped' | 'closed' = 'stopped', error?: string) {
    ++this.intent
    await this.halt(state, error)
  }

  private async halt(state: 'stopped' | 'closed' = 'stopped', error?: string) {
    const decision = this.state.decision
    ++this.generation
    this.request?.abort(); this.request = undefined
    clearInterval(this.timer); this.timer = undefined
    const report = this.report(state)
    this.update({ state: error ? 'error' : 'idle', decision: undefined, error })
    await this.player.stop().catch(() => undefined)
    await report
    await this.release(decision)
  }
}
