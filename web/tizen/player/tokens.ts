import type { Clock } from './clock'
import type { MediaToken } from './api'

/** Renew this long before the token runs out, or a fifth of its life if that is less. */
export const TOKEN_MARGIN_MS = 60_000
/** However the TV's clock and the server's disagree, a token is trusted this long. */
export const TOKEN_MIN_LIFETIME_MS = 30 * 60_000

/**
 * A play's media token and when to renew it, as Android's MediaTokenClock:
 * timed on the monotonic clock from the lifetime the server gave, so a TV
 * clock that disagrees with the server's can't cause a renewal loop.
 */
export class MediaTokenClock {
  private current?: string
  private renewAt = Infinity

  constructor(private clock: Clock) {}

  get token(): string | undefined { return this.current }

  /** The token while it needn't be renewed. */
  fresh(): string | undefined { return this.clock.now() < this.renewAt ? this.current : undefined }

  get due(): boolean { return this.current !== undefined && this.clock.now() >= this.renewAt }

  accept(token: MediaToken) {
    this.current = token.token
    const expires = Date.parse(token.expiresAt)
    const lifetime = Math.max(Number.isFinite(expires) ? expires - this.clock.wall() : 0, TOKEN_MIN_LIFETIME_MS)
    this.renewAt = this.clock.now() + lifetime - Math.min(TOKEN_MARGIN_MS, lifetime / 5)
  }

  forget() {
    this.current = undefined
    this.renewAt = Infinity
  }
}
