import { ApiError, unwrap, type ApiClient } from '../../src/api/client'
import type { components } from '../../src/api/schema'
import type { ServerInfo } from './active'
import { bearerClient } from './client'
import { MediaTokens, MediaUrls, tokenedUrl } from './media'
import { isPrivateNetworkHost } from './address'
import { PlayerError } from '../player'
import type { Account, RememberedServer } from './servers'
import { serverProblem, tooOldForApp, type AppSession, type StartProblem } from './session'

export type SearchResults = components['schemas']['SearchResults']

/** Why a server other than the current one wasn't asked: it isn't the one remembered, or this app can't use it. */
export class PeerError extends Error {
  constructor(readonly reason: Extract<StartProblem, 'moved' | 'tooOld' | 'notSetUp'>) {
    super(reason)
    this.name = 'PeerError'
  }
}

export type PeerHooks = {
  /** A 401 with its sign-in: the session was ended there. */
  onSignedOut?(): void
  /** What it said of itself when asked which server it is. */
  onInfo?(info: ServerInfo): void
}

/** Rejects when `signal` aborts, as a fetch would. */
function aborted(signal: AbortSignal): Promise<never> {
  return new Promise((_, reject) => {
    const fail = () => reject(signal.reason instanceof Error ? signal.reason : new DOMException('Aborted', 'AbortError'))
    if (signal.aborted) fail()
    else signal.addEventListener('abort', fail, { once: true })
  })
}

/**
 * A remembered server other than the one the app is on, asked directly: a
 * bearer client of its own, built for its own address with its own
 * sign-in, and its own media token for its pictures. Nothing of the active
 * session goes to it, and its sign-in goes nowhere else. Before the sign-in
 * goes out on any request the server is asked which one it is, once: every
 * call through `api` waits for that answer, so another server answering at
 * a remembered address gets no token, whichever screen asked.
 */
export class PeerServer {
  readonly id: string
  readonly name: string
  readonly baseUrl: string
  /** Reached beyond this network: its copies are offered last, with a note that they may be converted to keep up. */
  readonly away: boolean
  readonly tokens: MediaTokens
  readonly media: MediaUrls
  /** Its own client: each call asks which server it is first (once), then sends its sign-in. */
  readonly api: ApiClient
  private open: ApiClient
  private answered?: ServerInfo
  private checking?: Promise<void>
  private remembered?: number
  private wantsToken = true

  constructor(server: RememberedServer & { account: Account }, private hooks: PeerHooks = {}) {
    this.id = server.id
    this.name = server.name
    this.baseUrl = server.baseUrl
    this.remembered = server.apiLevel
    this.away = awayFromHome(server.baseUrl)
    this.open = bearerClient(server.baseUrl)
    this.api = bearerClient(server.baseUrl, {
      token: server.account.token,
      onUnauthorized: () => this.hooks.onSignedOut?.(),
      beforeSignIn: (signal) => this.verify(signal),
    })
    this.tokens = new MediaTokens(async () => unwrap(await this.api.POST('/api/auth/media-token')))
    this.media = new MediaUrls(server.baseUrl, this.tokens)
  }

  /** Its own answer to which server it is, once it has given one here: a switch to it needn't ask again. */
  get info(): ServerInfo | undefined { return this.answered }

  /** The API level it said it has, else the one remembered. */
  get apiLevel(): number | undefined { return this.answered?.apiLevel ?? this.remembered }

  /** Asks which server it is, once; calls made meanwhile wait for the one answer. */
  verify(signal: AbortSignal): Promise<void> {
    if (this.answered) return Promise.resolve()
    this.checking ??= (async () => {
      try {
        const info = unwrap(await this.open.GET('/api/server'))
        if (info.id !== this.id) throw new PeerError('moved')
        this.hooks.onInfo?.(info)
        const problem = serverProblem(info)
        if (problem) throw new PeerError(problem)
        this.answered = info
      } finally {
        this.checking = undefined
      }
    })()
    return Promise.race([this.checking, aborted(signal)])
  }

  /** Forgets its answer, so the next call asks again: Try again, after it didn't answer. */
  forget() { this.answered = undefined }

  /** Its pictures need its own media token: fetched once something of it is drawn. */
  showMedia() {
    if (!this.wantsToken) return
    this.wantsToken = false
    void this.tokens.start()
  }

  /** The player engine's `mediaUrl` for a play from this server: its own media paths, with the play's own token. */
  readonly playerMediaUrl = (path: string, token: string): string => {
    const url = tokenedUrl(this.baseUrl, path, token)
    if (!url) throw new PlayerError('The server sent an address this TV won’t open.')
    return url
  }

  /** GET /api/search on this server, with its own sign-in. */
  async search(q: string, signal: AbortSignal): Promise<SearchResults> {
    await this.verify(signal)
    const results = unwrap(await this.api.GET('/api/search', { params: { query: { q } }, signal }))
    this.showMedia()
    return results
  }

  stop() {
    this.wantsToken = false
    this.tokens.stop()
  }
}

/** Whether an address is beyond this network: a public name or address, not the house's own. */
export function awayFromHome(baseUrl: string): boolean {
  try { return !isPrivateNetworkHost(new URL(baseUrl).hostname) } catch { return false }
}

/**
 * The other servers this TV is signed in to and can use, in the order
 * Servers lists them (the most recently used first), each to be asked by
 * itself. The caller stops them when it is done.
 */
export function otherServers(app: AppSession): PeerServer[] {
  const state = app.snapshot()
  if (state.screen !== 'signedIn') return []
  const current = state.session.server.id
  return app.store.list()
    .filter((s): s is RememberedServer & { account: Account } => s.id !== current && !!s.account && !tooOldForApp(s))
    .map((s) => new PeerServer(s, {
      onSignedOut: () => app.signedOutOf(s.id),
      onInfo: (info) => app.store.note(s.id, { name: info.name, version: info.version, apiLevel: info.apiLevel }),
    }))
}

/** What a failed search says about a server, when asking again wouldn't help: its sign-in ended there, or it is too old for this app. */
export function leftOutReason(error: unknown): 'signedOut' | 'tooOld' | undefined {
  if (error instanceof PeerError) return error.reason === 'tooOld' ? 'tooOld' : undefined
  return error instanceof ApiError && error.status === 401 ? 'signedOut' : undefined
}

/** Why a server this TV has isn't asked: this TV is signed out of it, or it is too old for this app. */
export type LeftOut = { id: string; name: string; reason: 'signedOut' | 'tooOld' }

/** The other servers this TV has that a search doesn't ask, in Servers' order, each with why. */
export function serversLeftOut(app: AppSession): LeftOut[] {
  const state = app.snapshot()
  if (state.screen !== 'signedIn') return []
  const current = state.session.server.id
  return app.store.list().filter((s) => s.id !== current && (tooOldForApp(s) || !s.account))
    .map((s) => ({ id: s.id, name: s.name, reason: tooOldForApp(s) ? 'tooOld' as const : 'signedOut' as const }))
}
