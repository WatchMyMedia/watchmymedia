import { describe, expect, it } from 'vitest'
import { LANDED_WITHIN_MS, SETTLE_TIMEOUT_MS, Scrub, SeekAccelerator, seekStepLabel } from './seek'

describe('held seeking', () => {
  it('steps 10 s a press, and held grows to 30 s, 1 min and 2 min, at most every 200 ms', () => {
    const a = new SeekAccelerator()
    expect(a.step(0, false)).toBe(10_000)
    expect(a.step(100, true)).toBe(0)
    expect(a.step(200, true)).toBe(10_000)
    expect(a.step(2_000, true)).toBe(30_000)
    expect(a.step(4_000, true)).toBe(60_000)
    expect(a.step(7_000, true)).toBe(120_000)
    expect(a.step(60_000, true)).toBe(120_000)
    a.release()
    expect(a.step(60_100, true)).toBe(10_000)
  })

  it('moves the target without seeking until release, and keeps it until the position lands', () => {
    const s = new Scrub()
    s.press(1, 100_000, 600_000, 0, false)
    expect(s.view()).toEqual({ targetMs: 110_000, stepMs: 10_000, holding: true })
    s.press(1, 100_000, 600_000, 2_500, true)
    expect(s.view()?.targetMs).toBe(140_000)
    expect(s.release(2_600)).toBe(true)
    expect(s.send(3_000)).toBe(140_000)
    expect(s.send(3_000)).toBeUndefined()
    s.settle(100_000, 3_100)
    expect(s.view()?.holding).toBe(false)
    s.settle(140_000 - LANDED_WITHIN_MS, 3_200)
    expect(s.view()).toBeUndefined()
  })

  it('sends a run of quick presses once, to where they add up, and keeps the target until then', () => {
    const s = new Scrub()
    s.press(-1, 100_000, 600_000, 0, false)
    s.release(100)
    s.press(-1, 100_000, 600_000, 250, false)
    expect(s.send(260)).toBeUndefined()
    s.release(350)
    s.press(-1, 100_000, 600_000, 500, false)
    s.release(600)
    // Released, not sent: the position hasn't moved, and the target stays.
    s.settle(100_000, 600 + SETTLE_TIMEOUT_MS)
    expect(s.view()?.targetMs).toBe(70_000)
    expect(s.send(1_000)).toBe(70_000)
  })

  it('carries on from a target still shown, and gives up showing it after the settle timeout', () => {
    const s = new Scrub()
    s.press(-1, 100_000, 600_000, 0, false)
    s.release(10)
    expect(s.send(500)).toBe(90_000)
    s.press(-1, 100_000, 600_000, 800, false)
    expect(s.view()?.targetMs).toBe(80_000)
    expect(s.view()?.stepMs).toBe(-10_000)
    s.release(900)
    expect(s.send(1_300)).toBe(80_000)
    s.settle(100_000, 1_300 + SETTLE_TIMEOUT_MS - 1)
    expect(s.view()).toBeDefined()
    s.settle(100_000, 1_300 + SETTLE_TIMEOUT_MS)
    expect(s.view()).toBeUndefined()
  })

  it('stays within the file and releases nothing when nothing was pressed', () => {
    const s = new Scrub()
    expect(s.release(0)).toBe(false)
    expect(s.send(500)).toBeUndefined()
    s.press(-1, 3_000, 600_000, 0, false)
    expect(s.view()?.targetMs).toBe(0)
    s.clear()
    s.press(1, 595_000, 600_000, 0, false)
    expect(s.view()?.targetMs).toBe(600_000)
  })

  it('labels the step', () => {
    expect(seekStepLabel(10_000)).toBe('+10 s')
    expect(seekStepLabel(-30_000)).toBe('−30 s')
    expect(seekStepLabel(60_000)).toBe('+1 min')
    expect(seekStepLabel(-120_000)).toBe('−2 min')
  })
})
