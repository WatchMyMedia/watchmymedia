import { afterEach, describe, expect, it, vi } from 'vitest'
import { MediaTokenClock, TOKEN_MIN_LIFETIME_MS } from './tokens'
import type { Clock } from './clock'

const clock = (): Clock => ({ now: () => Date.now(), wall: () => Date.now(), setTimeout, clearTimeout: (h) => clearTimeout(h as never), setInterval, clearInterval: (h) => clearInterval(h as never) })
afterEach(() => vi.useRealTimers())

describe('media token renewal', () => {
  it('renews a minute before the end of the lifetime the server gave', () => {
    vi.useFakeTimers({ now: 0 })
    const t = new MediaTokenClock(clock())
    expect(t.fresh()).toBeUndefined()
    t.accept({ token: 'a', param: 'access_token', expiresAt: new Date(4 * 3_600_000).toISOString() })
    expect(t.fresh()).toBe('a')
    vi.advanceTimersByTime(4 * 3_600_000 - 61_000)
    expect(t.due).toBe(false)
    vi.advanceTimersByTime(2_000)
    expect(t.due).toBe(true)
    expect(t.fresh()).toBeUndefined()
    expect(t.token).toBe('a')
  })

  it('trusts a token at least half an hour whatever the clocks say', () => {
    vi.useFakeTimers({ now: 0 })
    const t = new MediaTokenClock(clock())
    t.accept({ token: 'b', param: 'access_token', expiresAt: new Date(-1000).toISOString() })
    vi.advanceTimersByTime(TOKEN_MIN_LIFETIME_MS - 61_000)
    expect(t.due).toBe(false)
    t.forget()
    expect(t.token).toBeUndefined()
    expect(t.due).toBe(false)
  })
})
