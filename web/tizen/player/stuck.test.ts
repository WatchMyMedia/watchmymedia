import { describe, expect, it } from 'vitest'
import { STUCK_MS, STUCK_UNSEEN_MS, StuckWatch } from './stuck'

/** Ticks of 250 ms for ms; true if any of them called it stuck. */
function still(w: StuckWatch, ms: number, positionMs: number, bufferedMs: number | undefined, moving = true): boolean {
  let stuck = false
  for (let t = 0; t < ms; t += 250) stuck = w.note(moving, positionMs, bufferedMs, 250) || stuck
  return stuck
}

describe('a stuck player', () => {
  it('is one standing still 10 s with 10 s in hand', () => {
    const w = new StuckWatch()
    w.note(true, 60_000, 20_000, 0)
    expect(still(w, STUCK_MS - 250, 60_000, 20_000)).toBe(false)
    expect(w.note(true, 60_000, 20_000, 250)).toBe(true)
  })

  it('is one standing still 30 s when the player can’t say what it holds', () => {
    const w = new StuckWatch()
    w.note(true, 60_000, undefined, 0)
    expect(still(w, STUCK_UNSEEN_MS - 250, 60_000, undefined)).toBe(false)
    expect(w.note(true, 60_000, undefined, 250)).toBe(true)
  })

  it('isn’t one waiting on the network, paused or moving', () => {
    const w = new StuckWatch()
    expect(still(w, 60_000, 60_000, 2_000)).toBe(false)
    expect(still(w, 60_000, 60_000, 20_000, false)).toBe(false)
    let pos = 60_000
    for (let t = 0; t < 60_000; t += 250) expect(w.note(true, pos += 250, 20_000, 250)).toBe(false)
  })

  it('counts again after a move, or once acted on', () => {
    const w = new StuckWatch()
    w.note(true, 60_000, 20_000, 0)
    still(w, STUCK_MS - 1_000, 60_000, 20_000)
    w.note(true, 61_000, 20_000, 250)
    expect(still(w, STUCK_MS - 250, 61_000, 20_000)).toBe(false)
    expect(w.note(true, 61_000, 20_000, 250)).toBe(true)
    w.reset()
    w.note(true, 61_000, 20_000, 0)
    expect(still(w, STUCK_MS - 250, 61_000, 20_000)).toBe(false)
  })
})
