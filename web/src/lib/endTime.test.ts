import { describe, expect, it } from 'vitest'
import { endsAt, endsWords, leftAndEnds, leftWords, minutesWords, percentWatched } from './endTime'

const now = new Date(2026, 10, 23, 20, 12, 0).getTime()

describe('end times', () => {
  it('says the clock time something ends at', () => {
    expect(endsAt(29 * 60_000, now, 'en-US')).toBe('8:41 PM')
    expect(endsAt(29 * 60_000, now, 'en-GB')).toBe('20:41')
    expect(endsAt(-5, now, 'en-US')).toBe('8:12 PM')
  })

  it('words a length, rounding up to the minute', () => {
    expect(minutesWords(30_000)).toBe('1 min')
    expect(minutesWords(29 * 60_000)).toBe('29 min')
    expect(minutesWords(72 * 60_000)).toBe('1 h 12 min')
    expect(minutesWords(120 * 60_000)).toBe('2 h')
    expect(minutesWords(119 * 60_000 + 1)).toBe('2 h')
  })

  it('says what is left of a play, and nothing when it is unknown or done', () => {
    expect(leftWords(10 * 60_000, 39 * 60_000)).toBe('29 min left')
    expect(leftWords(10, undefined)).toBeUndefined()
    expect(leftWords(40 * 60_000, 39 * 60_000)).toBeUndefined()
    expect(leftAndEnds(10 * 60_000, 39 * 60_000, now, 'en-US')).toBe('29 min left · ends 8:41 PM')
    expect(leftAndEnds(0, 0, now, 'en-US')).toBeUndefined()
  })

  it('says when a title started now would end', () => {
    expect(endsWords(118, now, 'en-US')).toBe('Ends 10:10 PM')
    expect(endsWords(undefined, now)).toBeUndefined()
    expect(endsWords(0, now)).toBeUndefined()
  })

  it('keeps progress between 0 and 100', () => {
    expect(percentWatched(45, 100)).toBe(45)
    expect(percentWatched(150, 100)).toBe(100)
    expect(percentWatched(10, 0)).toBe(0)
  })
})
