import { unwrap, type ApiClient } from '../../src/api/client'
import type { components } from '../../src/api/schema'
import type { ServerInfo } from './active'
import { bearerClient } from './client'
import { MediaTokens, MediaUrls } from './media'
import type { Account, RememberedServer } from './servers'
import { serverProblem, type AppSession, type StartProblem } from './session'

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

/**
 * A remembered server other than the one the app is on, asked directly: a
 * bearer client of its own, built for its own address with its own
 * sign-in, and its own media token for its pictures. Nothing of the active
 * session goes to it, and its sign-in goes nowhere else. Before the sign-in
 * is sent the server is asked which one it is, once: another server
 * answering at a remembered address gets no token.
 */
export class PeerServer {
  readonly id: string
  readonly name: string
  readonly baseUrl: string
  readonly tokens: MediaTokens
  readonly media: MediaUrls
  private api: ApiClient
  private open: ApiClient
  private answered?: ServerInfo
  private wantsToken = true

  constructor(server: RememberedServer & { account: Account }, private hooks: PeerHooks = {}) {
    this.id = server.id
    this.name = server.name
    this.baseUrl = server.baseUrl
    this.open = bearerClient(server.baseUrl)
    this.api = bearerClient(server.baseUrl, { token: server.account.token, onUnauthorized: () => this.hooks.onSignedOut?.() })
    this.tokens = new MediaTokens(async () => unwrap(await this.api.POST('/api/auth/media-token')))
    this.media = new MediaUrls(server.baseUrl, this.tokens)
  }

  /** Its own answer to which server it is, once it has given one here: a switch to it needn't ask again. */
  get info(): ServerInfo | undefined { return this.answered }

  private async verify(signal: AbortSignal) {
    if (this.answered) return
    const info = unwrap(await this.open.GET('/api/server', { signal }))
    if (info.id !== this.id) throw new PeerError('moved')
    this.hooks.onInfo?.(info)
    const problem = serverProblem(info)
    if (problem) throw new PeerError(problem)
    this.answered = info
  }

  /** GET /api/search on this server, with its own sign-in. */
  async search(q: string, signal: AbortSignal): Promise<SearchResults> {
    await this.verify(signal)
    const results = unwrap(await this.api.GET('/api/search', { params: { query: { q } }, signal }))
    // Its pictures need its own media token: fetched once it has something to show.
    if (this.wantsToken) {
      this.wantsToken = false
      void this.tokens.start()
    }
    return results
  }

  stop() {
    this.wantsToken = false
    this.tokens.stop()
  }
}

/**
 * The other servers this TV is signed in to, in the order Servers lists
 * them (the most recently used first), each to be asked by itself. The
 * caller stops them when it is done.
 */
export function otherServers(app: AppSession): PeerServer[] {
  const state = app.snapshot()
  if (state.screen !== 'signedIn') return []
  const current = state.session.server.id
  return app.store.list()
    .filter((s): s is RememberedServer & { account: Account } => s.id !== current && !!s.account)
    .map((s) => new PeerServer(s, {
      onSignedOut: () => app.signedOutOf(s.id),
      onInfo: (info) => app.store.note(s.id, { name: info.name, version: info.version, apiLevel: info.apiLevel }),
    }))
}
