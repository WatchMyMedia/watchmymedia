import type { components } from '../src/api/schema'
import { ApiError, result, safeError, serverUrl, timed, UserError, type TVClient } from './server'
import { clearSession, deviceInfo, pair, readSession, restoreSession, saveSession } from './session'
import { PlaybackController, type PlaybackState } from './playback'
import type { Player } from './avplay'
import type { MediaFile, PairingStart, SavedSession } from './types'
import type { ProfileId } from './profile'

type Library = components['schemas']['Library']
export type AppState = {
  screen: 'server' | 'pair' | 'library' | 'player'
  busy: boolean
  address: string
  serverName: string
  error?: string
  notice?: string
  canRestore: boolean
  pairing?: Pick<PairingStart, 'code' | 'linkPath' | 'expiresAt'>
  libraries: Library[]
  libraryId?: number
  files: MediaFile[]
  total: number
  offset: number
  focus: string
  profile: ProfileId
  playlist: 'vod' | 'event'
  playback: PlaybackState
}
export const PAGE_SIZE = 6

/** Owns an account's lifetime, without exposing its credentials to React. */
export class AppModel {
  private state: AppState
  private listeners = new Set<() => void>()
  private saved?: SavedSession
  private api?: TVClient
  private authRequest?: AbortController
  private pageRequest?: AbortController
  private generation = 0
  private playing = 0
  private playback?: PlaybackController
  private unsubscribe?: () => void
  private closing: Promise<void> = Promise.resolve()
  private lastFile?: MediaFile

  constructor(private storage: Storage, readonly player: Player, initialAddress = '') {
    this.saved = readSession(storage)
    this.state = { screen: 'server', address: this.saved?.baseUrl ?? initialAddress, canRestore: !!this.saved,
      serverName: '', busy: false, libraries: [], files: [], total: 0, offset: 0, focus: 'address',
      profile: 'safe', playlist: 'vod', playback: { state: 'idle', positionMs: 0 } }
  }
  readonly snapshot = () => this.state
  readonly subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener) } }
  private update(patch: Partial<AppState>) { this.state = { ...this.state, ...patch }; for (const listener of this.listeners) listener() }
  readonly onPlayerUpdate: PlaybackController['onPlayerUpdate'] = (event) => this.playback?.onPlayerUpdate(event)

  start() { if (this.saved) void this.connect(this.saved.baseUrl) }
  editAddress(address: string) { if (!this.state.busy) this.update({ address }) }

  async connect(input: string, fresh = false) {
    this.authRequest?.abort()
    const generation = ++this.generation
    const request = new AbortController(); this.authRequest = request
    this.update({ busy: true, error: undefined, notice: undefined, pairing: undefined })
    try {
      const address = serverUrl(input)
      this.update({ address })
      if (fresh && this.saved) { clearSession(this.storage); this.saved = undefined; this.update({ canRestore: false }) }
      if (this.saved?.baseUrl === address) {
        const restored = await restoreSession(this.saved, request.signal)
        if (generation !== this.generation) return
        await this.accept(this.saved, restored.api, restored.server.name)
      } else {
        this.update({ screen: 'pair', focus: 'cancel-pair' })
        const linked = await pair(address, deviceInfo(this.storage), request.signal, (pairing) => {
          if (generation === this.generation) this.update({ pairing })
        })
        if (generation !== this.generation) { await this.revoke(linked.api); return }
        try { saveSession(this.storage, linked.saved) }
        catch { await this.revoke(linked.api); throw new UserError('This device cannot save its sign-in. Enable app storage and try again.') }
        this.saved = linked.saved
        await this.accept(linked.saved, linked.api, linked.server.name)
      }
    } catch (error) {
      if (generation !== this.generation) return
      if (error instanceof ApiError && error.status === 401) this.forget()
      const screen = this.state.screen === 'pair' && !this.state.pairing ? 'server' : this.state.screen
      this.update({ screen, busy: false, pairing: undefined, error: safeError(error), focus: screen === 'pair' ? 'new-code' : 'connect' })
    }
  }

  private async accept(saved: SavedSession, api: TVClient, serverName: string) {
    this.api = api
    api.use({ onResponse: ({ response }) => {
      if (response.status === 401 && this.api === api) this.expire()
      return response
    } })
    this.unsubscribe?.()
    this.playback = new PlaybackController(api, saved.baseUrl, deviceInfo(this.storage), this.player)
    this.unsubscribe = this.playback.subscribe(() => {
      const playback = this.playback!.snapshot()
      this.update({ playback, ...(['playing', 'paused', 'error'].includes(playback.state) ? { busy: false } : {}) })
    })
    this.update({ screen: 'library', serverName, canRestore: true, pairing: undefined,
      busy: false, error: undefined, notice: undefined, focus: 'refresh', libraries: [], files: [], total: 0, offset: 0 })
    await this.loadFiles()
  }

  cancelPair() {
    ++this.generation; this.authRequest?.abort()
    this.update({ screen: 'server', busy: false, pairing: undefined, error: undefined, focus: 'address' })
  }

  async loadFiles(libraryId = this.state.libraryId, offset = 0) {
    const api = this.api
    if (!api) return
    this.pageRequest?.abort()
    const request = new AbortController(); this.pageRequest = request
    this.update({ busy: true, error: undefined })
    try {
      const libraries = this.state.libraries.length ? this.state.libraries : result(await timed((signal) => api.GET('/api/libraries', { signal }), request.signal)).items
      if (request.signal.aborted || api !== this.api) return
      const id = libraries.some((lib) => lib.id === libraryId) ? libraryId : libraries[0]?.id
      if (id === undefined) { this.update({ libraries, libraryId: undefined, files: [], total: 0, busy: false, focus: 'refresh' }); return }
      const page = result(await timed((signal) => api.GET('/api/libraries/{id}/files', {
        signal, params: { path: { id }, query: { status: 'present', limit: PAGE_SIZE, offset } },
      }), request.signal))
      if (request.signal.aborted || api !== this.api) return
      this.update({ libraries, libraryId: id, files: page.items, total: page.total, offset: page.offset, busy: false,
        focus: page.items.length ? `file-${page.items[0].id}` : 'refresh' })
    } catch (error) {
      if (request.signal.aborted || api !== this.api) return
      this.update({ busy: false, error: safeError(error), focus: 'refresh' })
    }
  }

  setOptions(profile: ProfileId, playlist: 'vod' | 'event') { this.update({ profile, playlist }) }

  async play(file: MediaFile) {
    const player = this.playback
    if (!player) return
    const playing = ++this.playing
    this.lastFile = file
    this.update({ screen: 'player', busy: true, notice: undefined, error: undefined, focus: 'stop', playback: { state: 'buffering', file, positionMs: 0 } })
    await this.closing
    if (playing !== this.playing || player !== this.playback) return
    await player.play(file, this.state.profile, this.state.playlist)
    if (playing === this.playing && player === this.playback) this.update({ busy: false, focus: player.snapshot().error ? 'replay' : 'pause' })
  }
  replay() { if (this.lastFile) void this.play(this.lastFile) }
  pause() { void this.playback?.togglePause() }
  seek(delta: number) { void this.playback?.seek(delta) }

  stopToFiles() {
    ++this.playing
    this.closing = this.playback?.stop() ?? Promise.resolve()
    this.update({ screen: 'library', busy: false, notice: undefined, error: undefined, focus: this.lastFile ? `file-${this.lastFile.id}` : 'refresh' })
  }

  suspend() {
    if (this.state.screen === 'pair') {
      ++this.generation; this.authRequest?.abort()
      this.update({ busy: false, pairing: undefined, error: 'Linking was interrupted. Get a new code to continue.', focus: 'new-code' })
    }
    if (this.state.screen === 'player') {
      ++this.playing
      this.closing = this.playback?.stop('closed') ?? Promise.resolve()
      this.update({ busy: false, notice: 'Playback stopped while you were away. Resume when you’re ready.', focus: 'replay' })
    }
  }

  shutdown() {
    ++this.generation; ++this.playing
    this.authRequest?.abort(); this.pageRequest?.abort()
    this.api = undefined; this.unsubscribe?.(); this.unsubscribe = undefined
    this.closing = this.playback?.stop('closed') ?? Promise.resolve()
    this.playback = undefined
    this.update({ playback: { state: 'idle', positionMs: 0 } })
    return this.closing
  }

  private forget() {
    this.saved = undefined
    try { clearSession(this.storage) } catch { /* No token is used after this point. */ }
    this.update({ canRestore: false })
  }

  private detach() {
    ++this.generation; ++this.playing
    this.authRequest?.abort(); this.pageRequest?.abort()
    this.api = undefined; this.unsubscribe?.(); this.unsubscribe = undefined
    this.closing = this.playback?.stop('closed') ?? Promise.resolve()
    this.playback = undefined; this.lastFile = undefined
    this.forget()
    this.update({ screen: 'server', busy: false, libraries: [], files: [], total: 0, libraryId: undefined,
      pairing: undefined, playback: { state: 'idle', positionMs: 0 }, focus: 'connect' })
  }

  private expire() {
    this.detach()
    this.update({ error: 'This TV was signed out. Link it again to continue.' })
  }

  private async revoke(api: TVClient) {
    result(await timed((signal) => api.POST('/api/auth/logout', { signal }), undefined, 5000))
  }

  async signOut() {
    const api = this.api
    this.detach()
    const generation = this.generation
    this.update({ error: undefined, notice: 'Signed out on this device.' })
    await this.closing
    if (api) {
      try { await this.revoke(api) }
      catch { if (generation === this.generation) this.update({ notice: 'Sign-in removed here. The server could not confirm sign-out; remove this TV in Account → Devices when connected.' }) }
    }
  }
}
