import { describe, expect, it } from 'vitest'
import { endsAtWords, leftWords, remaining } from './timeWords'

const MIN = 60_000

describe('time words', () => {
  it('says what is left in minutes and hours, never zero', () => {
    expect(leftWords(29 * MIN)).toBe('29 min left')
    expect(leftWords(72 * MIN)).toBe('1 h 12 min left')
    expect(leftWords(60 * MIN)).toBe('1 h left')
    expect(leftWords(10_000)).toBe('1 min left')
  })

  it('says when it ends from now', () => {
    const now = new Date(2026, 10, 23, 20, 12).getTime()
    expect(endsAtWords(now, 29 * MIN, 'en-US')).toBe('Ends at 8:41 PM')
  })

  it('works out what is left from a place and a length', () => {
    expect(remaining(18 * MIN, 47 * MIN)).toBe(29 * MIN)
    expect(remaining(undefined, 47 * MIN)).toBe(47 * MIN)
    expect(remaining(50 * MIN, 47 * MIN)).toBe(0)
    expect(remaining(5, 0)).toBeUndefined()
  })
})
