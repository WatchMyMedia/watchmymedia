import { afterEach, describe, expect, it, vi } from 'vitest'
import { forgetGrant, GRANT_MS, grantUntil, minutesLeft, noteConfirmed, parseGrant, subscribeGrant } from './passwordGrant'

afterEach(() => forgetGrant())

describe('password grant', () => {
  it('lasts ten minutes from this page’s confirmation and tells listeners', () => {
    const listener = vi.fn()
    const stop = subscribeGrant(listener)
    noteConfirmed(1_000)
    expect(grantUntil()).toBe(1_000 + GRANT_MS)
    expect(listener).toHaveBeenCalledTimes(1)
    stop()
    forgetGrant()
    expect(listener).toHaveBeenCalledTimes(1)
    expect(grantUntil()).toBe(0)
  })
  it('rounds minutes up, so the chip shows 1 min until it ends', () => {
    expect(minutesLeft(10 * 60_000, 0)).toBe(10)
    expect(minutesLeft(60_000, 59_999)).toBe(1)
    expect(minutesLeft(60_000, 60_000)).toBe(0)
    expect(minutesLeft(0, 5)).toBe(0)
  })
  it('reads the server’s time and ignores what it can’t read', () => {
    expect(parseGrant('2026-11-14T10:00:00Z')).toBe(Date.parse('2026-11-14T10:00:00Z'))
    expect(parseGrant(undefined)).toBe(0)
    expect(parseGrant('soon')).toBe(0)
  })
})
