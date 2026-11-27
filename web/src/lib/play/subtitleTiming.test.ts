import { describe, expect, it, vi } from 'vitest'
import { SubtitleTiming, TIMING_SETTLE_MS, clampTiming, timingWords, type SavedTiming, type TimingTarget } from './subtitleTiming'

/** Timers the test moves by hand. */
function fakeTimers() {
  let now = 0
  let next = 1
  const due = new Map<number, { at: number; fn: () => void }>()
  return {
    setTimeout: (fn: () => void, ms: number) => { const id = next++; due.set(id, { at: now + ms, fn }); return id },
    clearTimeout: (id: unknown) => { due.delete(id as number) },
    advance(ms: number) {
      now += ms
      for (const [id, t] of [...due]) if (t.at <= now) { due.delete(id); t.fn() }
    },
  }
}

type Call = { kind: 'save' | 'reset'; target: TimingTarget; ms?: number; everyone?: boolean; resolve: (s: SavedTiming) => void; reject: (e: unknown) => void }

function setUp(admin = false) {
  const calls: Call[] = []
  const timers = fakeTimers()
  const onBurnedSaved = vi.fn()
  const t = new SubtitleTiming({
    admin,
    timers,
    onChange: () => undefined,
    onBurnedSaved,
    saver: {
      save: (target, ms, everyone) => new Promise((resolve, reject) => calls.push({ kind: 'save', target, ms, everyone, resolve, reject })),
      reset: (target) => new Promise((resolve, reject) => calls.push({ kind: 'reset', target, resolve, reject })),
    },
  })
  return { t, calls, timers, onBurnedSaved }
}

const track = { fileId: 7, trackId: 's2' }
const flush = () => new Promise((r) => setTimeout(r, 0))

describe('timing words', () => {
  it('says In step, then tenths of a second later or earlier', () => {
    expect(timingWords(0)).toBe('In step')
    expect(timingWords(400)).toBe('0.4 s later')
    expect(timingWords(-1200)).toBe('1.2 s earlier')
    expect(timingWords(12_000)).toBe('12.0 s later')
  })

  it('keeps a timing on 0.1 s steps within a minute either way', () => {
    expect(clampTiming(449)).toBe(400)
    expect(clampTiming(61_000)).toBe(60_000)
    expect(clampTiming(-70_000)).toBe(-60_000)
    expect(Object.is(clampTiming(-20), 0)).toBe(true)
  })
})

describe('subtitle timing', () => {
  it('moves at once, and saves a second after the last press', async () => {
    const { t, calls, timers } = setUp()
    t.track(track, {})
    t.step(1)
    t.step(1)
    expect(t.view()).toMatchObject({ ms: 200, words: '0.2 s later', status: 'changed', canReset: true })
    timers.advance(TIMING_SETTLE_MS - 1)
    t.step(1)
    timers.advance(TIMING_SETTLE_MS - 1)
    expect(calls).toHaveLength(0)
    timers.advance(1)
    expect(calls).toMatchObject([{ kind: 'save', target: track, ms: 300, everyone: false }])
    expect(t.view().status).toBe('saving')
    calls[0].resolve({ timingMs: 300, timingShared: false })
    await flush()
    expect(t.view()).toMatchObject({ ms: 300, status: 'idle', canReset: true, setForEveryone: false })
  })

  it('goes earlier past 0, and holds at a minute', () => {
    const { t } = setUp()
    t.track(track, { timingMs: 100 })
    t.step(-1)
    t.step(-1)
    expect(t.view().words).toBe('0.1 s earlier')
    t.track({ fileId: 7, trackId: 's9' }, { timingMs: 59_900 })
    t.step(1)
    t.step(1)
    expect(t.view().ms).toBe(60_000)
  })

  it('keeps a failed save for this play and saves it again on Try again', async () => {
    const { t, calls, timers } = setUp()
    t.track(track, {})
    t.step(1)
    timers.advance(TIMING_SETTLE_MS)
    calls[0].reject(new Error('offline'))
    await flush()
    expect(t.view()).toMatchObject({ ms: 100, status: 'failed' })
    // A reload of the same play keeps the change rather than the decision's old value.
    t.track(track, { timingMs: 0 })
    expect(t.view().ms).toBe(100)
    t.retry()
    expect(calls[1]).toMatchObject({ kind: 'save', ms: 100 })
  })

  it('shows a shared timing as set for everyone, and Reset goes back to it', async () => {
    const { t, calls } = setUp()
    t.track(track, { timingMs: 800, timingShared: true })
    expect(t.view()).toMatchObject({ ms: 800, words: '0.8 s later', setForEveryone: true, canReset: false, canShare: false })
    t.step(1)
    expect(t.view()).toMatchObject({ ms: 900, setForEveryone: false, canReset: true })
    t.reset()
    // At once, before the server answers: back to the shared timing.
    expect(t.view()).toMatchObject({ ms: 800, setForEveryone: true, canReset: false, status: 'saving' })
    expect(calls).toMatchObject([{ kind: 'reset', target: track }])
    calls[0].resolve({ timingMs: 800, timingShared: true })
    await flush()
    expect(t.view()).toMatchObject({ ms: 800, setForEveryone: true, status: 'idle' })
  })

  it('takes Reset’s answer when the shared timing wasn’t known', async () => {
    const { t, calls } = setUp()
    t.track(track, { timingMs: -500 })
    t.reset()
    expect(t.view().ms).toBe(0)
    calls[0].resolve({ timingMs: 1500, timingShared: true })
    await flush()
    expect(t.view()).toMatchObject({ ms: 1500, setForEveryone: true, canReset: false })
  })

  it('hides Reset when it would do nothing', () => {
    const { t } = setUp()
    t.track(track, {})
    expect(t.view().canReset).toBe(false)
    t.step(1)
    t.step(-1)
    expect(t.view().canReset).toBe(false)
  })

  it('lets an admin save their timing for everyone', async () => {
    const { t, calls, timers } = setUp(true)
    t.track(track, {})
    expect(t.view().canShare).toBe(false)
    t.step(1)
    expect(t.view().canShare).toBe(true)
    t.share()
    // The waiting own save is dropped: saving for everyone replaces it.
    timers.advance(TIMING_SETTLE_MS)
    expect(calls).toMatchObject([{ kind: 'save', ms: 100, everyone: true }])
    calls[0].resolve({ timingMs: 100, timingShared: true })
    await flush()
    expect(t.view()).toMatchObject({ ms: 100, status: 'shared', setForEveryone: true, canShare: false, canReset: false })
  })

  it('never offers Save for everyone to a person who isn’t an admin', () => {
    const { t } = setUp(false)
    t.track(track, {})
    t.step(1)
    expect(t.view().canShare).toBe(false)
  })

  it('restarts a burned-in play once its timing is saved', async () => {
    const { t, calls, timers, onBurnedSaved } = setUp(true)
    t.track(track, {}, true)
    t.step(1)
    expect(t.view()).toMatchObject({ burned: true, canShare: false })
    timers.advance(TIMING_SETTLE_MS)
    calls[0].resolve({ timingMs: 100, timingShared: false })
    await flush()
    expect(onBurnedSaved).toHaveBeenCalledWith(track)
  })

  it('saves a waiting change for its own track when the play moves to another', () => {
    const { t, calls } = setUp()
    t.track(track, {})
    t.step(-1)
    t.track({ fileId: 7, trackId: 's3' }, { timingMs: 300 })
    expect(calls).toMatchObject([{ kind: 'save', target: track, ms: -100 }])
    expect(t.view()).toMatchObject({ ms: 300, status: 'idle' })
  })

  it('does nothing without a track', () => {
    const { t, calls, timers } = setUp(true)
    t.track(undefined, undefined)
    t.step(1)
    t.share()
    t.reset()
    timers.advance(TIMING_SETTLE_MS)
    expect(calls).toHaveLength(0)
    expect(t.view().ms).toBe(0)
  })
})
