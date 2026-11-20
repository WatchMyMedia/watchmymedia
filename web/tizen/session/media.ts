import { img, type ImageSize } from '../../src/lib/images'

/**
 * The routes a player or an <img> fetches straight from the server, where
 * a media token may ride in the query string. It follows the server's
 * `mediaPath` (server/internal/api/auth.go): streams, playback sessions,
 * subtitles, TMDB images, Live TV art, seek-thumbnail sheets and live HLS.
 * Anything else never gets a token in its address.
 */
const MEDIA_PATHS = [
  /^\/api\/files\/\d+\/stream$/,
  /^\/api\/playback\/[^/]+\/.+$/,
  /^\/api\/files\/\d+\/subtitles\/[^/]+\.(vtt|ass)$/,
  /^\/api\/images\/.+$/,
  /^\/api\/live-tv\/art\/.+$/,
  /^\/api\/files\/\d+\/trickplay\/[^/]+\/[^/]+\.jpg$/,
  /^\/api\/live-tv\/sessions\/[^/]+\/hls\/[^/]+$/,
]

/**
 * `path` on the server at `baseUrl` with `?access_token=`, or undefined when
 * it is not one of the media routes on that same server. Never pass a
 * session token: only a media token (POST /api/auth/media-token, or a
 * decision's `mediaToken`) belongs in an address.
 */
export function tokenedUrl(baseUrl: string, path: string, token: string): string | undefined {
  let url: URL, base: string
  try {
    base = new URL(baseUrl).origin
    url = new URL(path, `${base}/`)
  } catch { return undefined }
  if (url.origin !== base || url.username || url.password || !MEDIA_PATHS.some((re) => re.test(url.pathname))) return undefined
  url.hash = ''
  url.searchParams.set('access_token', token)
  return url.href
}

export type MediaToken = { token: string; expiresAt: number }

type Timers = {
  now(): number
  setTimeout(fn: () => void, ms: number): ReturnType<typeof setTimeout>
  clearTimeout(id: ReturnType<typeof setTimeout>): void
}
const realTimers: Timers = { now: () => Date.now(), setTimeout: (fn, ms) => setTimeout(fn, ms), clearTimeout: (id) => clearTimeout(id) }

/** How long before a token runs out the next is fetched. */
export const REFRESH_BEFORE_MS = 10 * 60_000
/** Addresses made with a token are reused until this long before it runs out. */
export const REUSE_UNTIL_MS = 5 * 60_000
const RETRY_MS = 30_000

/**
 * The session's media token (POST /api/auth/media-token): fetched once,
 * kept, and fetched again before it runs out (or on `check()` after the TV
 * wakes). A failure is retried; a 401 is the client's to report.
 */
export class MediaTokens {
  private token?: MediaToken
  private timer?: ReturnType<typeof setTimeout>
  private pending?: Promise<void>
  private stopped = false
  private listeners = new Set<() => void>()
  private version = 0

  constructor(private request: () => Promise<{ token: string; expiresAt: string }>, private timers: Timers = realTimers) {}

  /** Fetches the first token; resolves when there is one or the fetch failed (and will be retried). */
  start(): Promise<void> { return this.refresh() }

  /** The token while it is still good. */
  current(): MediaToken | undefined {
    return this.token && this.token.expiresAt > this.timers.now() ? this.token : undefined
  }

  /** Fetches a new token now when the one held is close to running out, as after a suspend. */
  check(): Promise<void> {
    const t = this.current()
    return !t || t.expiresAt - this.timers.now() <= REFRESH_BEFORE_MS ? this.refresh() : Promise.resolve()
  }

  readonly subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener) } }
  /** Changes with each new token, for React's useSyncExternalStore. */
  readonly snapshot = () => this.version

  stop() {
    this.stopped = true
    if (this.timer !== undefined) this.timers.clearTimeout(this.timer)
    this.listeners.clear()
  }

  private refresh(): Promise<void> {
    if (this.stopped) return Promise.resolve()
    this.pending ??= (async () => {
      try {
        const reply = await this.request()
        if (this.stopped) return
        const expiresAt = Date.parse(reply.expiresAt)
        this.token = { token: reply.token, expiresAt: Number.isFinite(expiresAt) ? expiresAt : this.timers.now() + 60 * 60_000 }
        this.version++
        for (const l of this.listeners) l()
        const life = this.token.expiresAt - this.timers.now()
        this.schedule(Math.max(1000, life - Math.min(REFRESH_BEFORE_MS, life / 4)))
      } catch {
        if (!this.stopped) this.schedule(RETRY_MS)
      } finally {
        this.pending = undefined
      }
    })()
    return this.pending
  }

  private schedule(ms: number) {
    if (this.timer !== undefined) this.timers.clearTimeout(this.timer)
    this.timer = this.timers.setTimeout(() => { this.timer = undefined; void this.refresh() }, ms)
  }
}

/**
 * Image and media addresses for the signed-in server. An address made with
 * a token is kept until shortly before that token runs out, so a new token
 * doesn't change every picture's address (and fetch every picture again).
 * The cache is bounded.
 */
export class MediaUrls {
  private cache = new Map<string, { url: string; until: number }>()

  constructor(readonly baseUrl: string, private tokens: MediaTokens, private now: () => number = () => Date.now(), private max = 1500) {}

  // Arrow properties, so a screen can take them out of the object
  // (`const { imageUrl } = useMediaUrls()`) and keep `this`.

  /** A media route on this server (`/api/images/…`, a logo, a trickplay sheet) with the token; undefined without one. */
  mediaUrl = (path: string | null | undefined): string | undefined => {
    if (!path) return undefined
    const t = this.now()
    const hit = this.cache.get(path)
    if (hit && t < hit.until) return hit.url
    const token = this.tokens.current()
    if (!token) return undefined
    const url = tokenedUrl(this.baseUrl, path, token.token)
    if (!url) return undefined
    this.cache.delete(path)
    if (this.cache.size >= this.max) this.cache.delete(this.cache.keys().next().value as string)
    this.cache.set(path, { url, until: token.expiresAt - REUSE_UNTIL_MS })
    return url
  }

  /** A TMDB image path ("/abc.jpg") at a size (`src/lib/images.ts`), with the token. */
  imageUrl = (path: string | null | undefined, size: ImageSize): string | undefined => this.mediaUrl(img(path ?? undefined, size))
}
