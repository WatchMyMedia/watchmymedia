import { describe, expect, it } from 'vitest'
import { STILL_WATCHING_LONG_MS, StillWatchingSession, stillWatchingDue } from './stillWatching'

describe('Still watching', () => {
  it('asks once the session is long and three episodes went by, or nobody pressed anything for as long', () => {
    const long = STILL_WATCHING_LONG_MS
    expect(stillWatchingDue({ sessionStart: 0, lastInput: long - 1, episodesSinceInput: 3 }, long)).toBe(true)
    expect(stillWatchingDue({ sessionStart: 0, lastInput: long - 1, episodesSinceInput: 2 }, long)).toBe(false)
    expect(stillWatchingDue({ sessionStart: 0, lastInput: 0, episodesSinceInput: 0 }, long)).toBe(true)
    expect(stillWatchingDue({ sessionStart: 1, lastInput: 0, episodesSinceInput: 9 }, long)).toBe(false)
  })

  it('keeps the counts in memory from the first play, on the caller’s clock', () => {
    let now = 0
    const s = new StillWatchingSession(() => now)
    s.noteAutoAdvance()
    now = STILL_WATCHING_LONG_MS
    expect(s.shouldAsk()).toBe(false)
    s.notePlay()
    for (let i = 0; i < 3; i++) s.noteAutoAdvance()
    now += STILL_WATCHING_LONG_MS
    expect(s.shouldAsk()).toBe(true)
    s.noteInput()
    expect(s.shouldAsk()).toBe(false)
    s.notePlay()
    now += STILL_WATCHING_LONG_MS
    expect(s.shouldAsk()).toBe(true)
  })
})
