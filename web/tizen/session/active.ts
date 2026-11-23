import { QueryClient } from '@tanstack/react-query'
import { api as currentApi, setApiClient, unwrap, type ApiClient } from '../../src/api/client'
import type { components } from '../../src/api/schema'
import { changeAuthScope } from '../../src/lib/authScope'
import { eventInvalidations, invalidateAfterReconnect } from '../../src/api/eventInvalidation'
import { bearerClient } from './client'
import { MediaTokens, MediaUrls, tokenedUrl } from './media'
import { PlayerError } from '../player'
import { EventStream } from './events'
import type { Account } from './servers'
import type { ClientInfo } from './device'

export type ServerInfo = components['schemas']['ServerInfo']

let serials = 0

/** The cache's defaults on the TV: server events keep it fresh, so it refetches less than the web. */
export function tvQueryClient() {
  return new QueryClient({ defaultOptions: { queries: { staleTime: 30_000, retry: 1 } } })
}

/**
 * One signed-in account on one server, for as long as it lasts: the bearer
 * client every query uses, a query cache of its own (so nothing crosses
 * servers or accounts), the media token and the event stream. Made by
 * `AppSession`; screens reach it with `useSession()`.
 */
export class ActiveSession {
  readonly api: ApiClient
  readonly queryClient = tvQueryClient()
  readonly tokens: MediaTokens
  readonly media: MediaUrls
  private events: EventStream
  private listeners = new Map<string, Set<(data: unknown) => void>>()
  private ended = false
  private attached = false
  private online = true
  private onlineListeners = new Set<() => void>()
  /** A number per session, so the shell starts afresh for each one. */
  readonly serial = ++serials

  constructor(readonly server: ServerInfo, readonly baseUrl: string, readonly account: Account, readonly client: ClientInfo, onUnauthorized: () => void) {
    this.api = bearerClient(baseUrl, { token: account.token, onUnauthorized })
    this.tokens = new MediaTokens(async () => unwrap(await this.api.POST('/api/auth/media-token')))
    this.media = new MediaUrls(baseUrl, this.tokens)
    const invalidate = eventInvalidations(this.queryClient)
    this.events = new EventStream({
      url: `${baseUrl}/api/events`,
      token: account.token,
      onUnauthorized,
      onOpen: (reopened) => {
        this.setOnline(true)
        if (reopened) invalidateAfterReconnect(this.queryClient)
      },
      onDrop: () => this.setOnline(false),
      onEvent: ({ event, data }) => {
        let parsed: unknown
        try { parsed = JSON.parse(data) } catch { return }
        invalidate[event]?.(parsed)
        this.listeners.get(event)?.forEach((l) => l(parsed))
      },
    })
  }

  get isAdmin() { return this.account.isAdmin }

  /**
   * The player engine's `mediaUrl` (player/README.md): a media path on this
   * server with the play's own token, which the engine holds and renews.
   * Streams, playlists, subtitle files, seek-thumbnail sheets and Live TV
   * playlists pass; any other path is refused.
   */
  readonly playerMediaUrl = (path: string, token: string): string => {
    const url = tokenedUrl(this.baseUrl, path, token)
    if (!url) throw new PlayerError('The server sent an address this TV won’t open.')
    return url
  }

  /** Whether the event stream reaches the server: false from a drop until it is open again (the offline banner). */
  readonly connection = {
    subscribe: (listener: () => void) => { this.onlineListeners.add(listener); return () => { this.onlineListeners.delete(listener) } },
    snapshot: () => this.online,
    /** Try again now: opens the stream again at once rather than at the next backoff. */
    retry: () => { if (this.ended || !this.attached) return; this.events.stop(); this.events.start() },
  }

  private setOnline(online: boolean) {
    if (online === this.online) return
    this.online = online
    for (const l of this.onlineListeners) l()
  }

  /**
   * Gets this session ready without touching the one in use: resolves once
   * its first media token is in (or a few seconds pass; pictures appear when
   * it comes). A switch waits here while the old session still runs.
   */
  ready(): Promise<void> {
    return Promise.race([this.tokens.start(), new Promise<void>((r) => setTimeout(r, 3000))])
  }

  /**
   * Makes this the session every query and mutation uses: the web's `api`
   * becomes this bearer client and the auth scope moves on, so a reply from
   * before can't fill this cache. The session before it is detached after,
   * and leaves this client in place.
   */
  attach() {
    if (this.ended) return
    this.attached = true
    setApiClient(this.api)
    changeAuthScope()
    this.events.start()
  }

  /** Ends this session here: closes the stream, drops the cache and the token. Does not sign out on the server. */
  detach() {
    if (this.ended) return
    this.ended = true
    this.events.stop()
    this.tokens.stop()
    this.listeners.clear()
    this.onlineListeners.clear()
    void this.queryClient.cancelQueries()
    this.queryClient.clear()
    if (!this.attached) return
    changeAuthScope()
    // Only while the queries still use this client: after a switch they already use the next session's.
    if (currentApi === this.api) setApiClient(bearerClient(this.baseUrl))
  }

  get detached() { return this.ended }

  /** The app went to the background: close the stream. */
  suspend() { if (!this.ended) this.events.stop() }

  /** Back in front: reopen the stream (refetching what may have changed) and renew the token if it's due. */
  resume() {
    if (this.ended) return
    this.events.start()
    void this.tokens.check()
  }

  /** Calls `fn` with each `name` event from the server (GET /api/events), after the cache was invalidated. */
  onServerEvent(name: string, fn: (data: unknown) => void): () => void {
    let set = this.listeners.get(name)
    if (!set) this.listeners.set(name, set = new Set())
    set.add(fn)
    return () => { set.delete(fn) }
  }
}
