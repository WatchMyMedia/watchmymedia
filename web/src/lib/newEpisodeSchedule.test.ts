import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { SETTLE_MS } from './newEpisodes'
import { createCheckSchedule } from './newEpisodeSchedule'

// The schedule with the clock and timers faked, and the last change kept in
// a variable that stands for the browser's storage, shared by every tab.

let shared: number | undefined
beforeEach(() => {
  vi.useFakeTimers()
  vi.setSystemTime(new Date('2026-11-16T12:00:00Z'))
  shared = undefined
})
afterEach(() => { vi.useRealTimers() })

function schedule() {
  return createCheckSchedule({
    now: () => Date.now(),
    setTimer: (run, ms) => setTimeout(run, ms),
    clearTimer: (t) => clearTimeout(t as ReturnType<typeof setTimeout>),
    lastChange: () => shared,
    noteChange: (at) => { shared = at },
  })
}
const flush = () => vi.advanceTimersByTimeAsync(0)

describe('when the holding tab checks', () => {
  it('checks when it starts holding the lock, and 3 minutes after the last change', async () => {
    const s = schedule(), check = vi.fn(async () => {})
    s.start(check)
    await flush()
    expect(check).toHaveBeenCalledTimes(1)
    s.libraryChanged('changed')
    await vi.advanceTimersByTimeAsync(60_000)
    s.libraryChanged('changed')
    await vi.advanceTimersByTimeAsync(SETTLE_MS - 1)
    expect(check).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(1)
    expect(check).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(10 * 60_000)
    expect(check).toHaveBeenCalledTimes(2)
  })

  it('checks again once the window has passed when it starts inside one: a tab that just took the lock, or a redrawn or reloaded page', async () => {
    // Another tab (or this page before it was redrawn) heard the change 1 minute ago.
    const other = schedule()
    other.libraryChanged('changed')
    await vi.advanceTimersByTimeAsync(60_000)
    const s = schedule(), check = vi.fn(async () => {})
    s.start(check)
    await flush()
    expect(check).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(SETTLE_MS - 60_000)
    expect(check).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(10 * 60_000)
    expect(check).toHaveBeenCalledTimes(2)
  })

  it('does not check again for a change whose window passed before it started', async () => {
    schedule().libraryChanged('changed')
    await vi.advanceTimersByTimeAsync(SETTLE_MS + 1)
    const s = schedule(), check = vi.fn(async () => {})
    s.start(check)
    await vi.advanceTimersByTimeAsync(10 * 60_000)
    expect(check).toHaveBeenCalledTimes(1)
  })

  it('checks at once when the event stream comes back, and again after the window', async () => {
    const s = schedule(), check = vi.fn(async () => {})
    s.start(check)
    await flush()
    s.libraryChanged('reopened')
    await flush()
    expect(check).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(SETTLE_MS)
    expect(check).toHaveBeenCalledTimes(3)
  })

  it('runs a check asked for while one runs once that one finishes', async () => {
    const s = schedule()
    let finish: () => void = () => {}
    const check = vi.fn(() => new Promise<void>((resolve) => { finish = resolve }))
    s.start(check)
    await flush()
    void s.checkNow()
    void s.checkNow()
    expect(check).toHaveBeenCalledTimes(1)
    finish()
    await flush()
    expect(check).toHaveBeenCalledTimes(2)
    finish()
    await flush()
    expect(check).toHaveBeenCalledTimes(2)
  })

  it('stops with the lock: a tab that let it go checks nothing, but still records changes', async () => {
    const s = schedule(), check = vi.fn(async () => {})
    s.start(check)
    await flush()
    s.libraryChanged('changed')
    s.stop()
    await vi.advanceTimersByTimeAsync(10 * 60_000)
    expect(check).toHaveBeenCalledTimes(1)
    s.libraryChanged('changed')
    expect(shared).toBe(Date.now())
    expect(check).toHaveBeenCalledTimes(1)
  })

  it('keeps going after a check that throws', async () => {
    const s = schedule(), check = vi.fn(async () => { throw new Error('offline') })
    s.start(check)
    await flush()
    s.libraryChanged('changed')
    await vi.advanceTimersByTimeAsync(SETTLE_MS)
    expect(check).toHaveBeenCalledTimes(2)
  })
})
