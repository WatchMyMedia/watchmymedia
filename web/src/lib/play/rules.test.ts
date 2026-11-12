import { describe, expect, it } from 'vitest'
import { StallMeter, activeSegment, beatDue, bufferedAhead, nextCardShowing, segmentsAt, skipPlan, waitingAtJoint } from './rules'

const intro = { id: 1, kind: 'intro', startMs: 10_000, endMs: 70_000 }
const credits = { id: 2, kind: 'credits', startMs: 1_700_000, final: true }
const short = { id: 3, kind: 'recap', startMs: 100_000, endMs: 102_000 }

describe('activeSegment', () => {
  it('finds the segment playing, stopping half a second before its end', () => {
    expect(activeSegment([intro], 30, 1800, false)).toBe(intro)
    expect(activeSegment([intro], 9.9, 1800, false)).toBeUndefined()
    expect(activeSegment([intro], 69.6, 1800, false)).toBeUndefined()
  })
  it('leaves the final credits to the Next Episode card', () => {
    expect(activeSegment([credits], 1750, 1800, true)).toBeUndefined()
    expect(activeSegment([credits], 1750, 1800, false)).toBe(credits)
  })
})

describe('skipPlan', () => {
  it('asks with a button only for segments of 3 s or more', () => {
    expect(skipPlan(intro, 1800, 'ask', false, false)).toMatchObject({ showButton: true, autoNow: false })
    expect(skipPlan(short, 1800, 'ask', false, false).showButton).toBe(false)
  })
  it('skips by itself unless the viewer seeked in', () => {
    expect(skipPlan(intro, 1800, 'auto', false, false)).toMatchObject({ showButton: false, autoNow: true })
    expect(skipPlan(intro, 1800, 'auto', true, false)).toMatchObject({ showButton: true, autoNow: false })
    expect(skipPlan(intro, 1800, 'auto', false, true).autoNow).toBe(false)
  })
  it('waits 3 s in auto-delay mode', () => {
    expect(skipPlan(intro, 1800, 'auto-delay', false, false)).toMatchObject({ showButton: true, autoAfterMs: 3000 })
    expect(skipPlan(intro, 1800, 'auto-delay', true, false).autoAfterMs).toBeUndefined()
  })
  it('does nothing when off', () => {
    expect(skipPlan(intro, 1800, 'none', false, false)).toMatchObject({ showButton: false, autoNow: false })
  })
})

describe('segmentsAt', () => {
  it('lists the segments a seek lands in', () => {
    expect(segmentsAt([intro, credits], 20, 1800)).toEqual([1])
    expect(segmentsAt([intro, credits], 1790, 1800)).toEqual([2])
    expect(segmentsAt([intro, credits], 100, 1800)).toEqual([])
  })
})

describe('nextCardShowing', () => {
  const base = { hasNext: true, upNextMs: 1_700_000, time: 1750, ended: false, creditsDismissed: false, askingStill: false }
  it('shows from upNextMs until dismissed', () => {
    expect(nextCardShowing(base)).toBe(true)
    expect(nextCardShowing({ ...base, time: 1600 })).toBe(false)
    expect(nextCardShowing({ ...base, creditsDismissed: true })).toBe(false)
    expect(nextCardShowing({ ...base, creditsDismissed: true, ended: true })).toBe(true)
  })
  it('never shows without a next episode or while asking', () => {
    expect(nextCardShowing({ ...base, hasNext: false })).toBe(false)
    expect(nextCardShowing({ ...base, upNextMs: 0 })).toBe(false)
    expect(nextCardShowing({ ...base, askingStill: true })).toBe(false)
  })
})

describe('beatDue', () => {
  it('beats every 10 s playing and every 30 s otherwise', () => {
    expect(beatDue('playing', 0, 9_999)).toBe(false)
    expect(beatDue('playing', 0, 10_000)).toBe(true)
    expect(beatDue('paused', 0, 29_999)).toBe(false)
    expect(beatDue('buffering', 0, 30_000)).toBe(true)
  })
  it('beats every 5 s playing or buffering at Automatic', () => {
    expect(beatDue('playing', 0, 4_999, true)).toBe(false)
    expect(beatDue('playing', 0, 5_000, true)).toBe(true)
    expect(beatDue('buffering', 0, 5_000, true)).toBe(true)
    expect(beatDue('paused', 0, 29_999, true)).toBe(false)
  })
})

const ranges = (...r: [number, number][]) => ({ length: r.length, start: (i: number) => r[i][0], end: (i: number) => r[i][1] })

describe('bufferedAhead', () => {
  it('measures the range holding the position, not the last one', () => {
    expect(bufferedAhead(ranges([0, 40], [300, 360]), 30)).toBe(10)
    expect(bufferedAhead(ranges([0, 40], [300, 360]), 310)).toBe(50)
  })
  it('carries on across a hole the player skips, not a wider one', () => {
    // Recorded 10 s before a family's encoder joint: 2 s reported, 48 s held.
    expect(bufferedAhead(ranges([648.07, 684.02], [684.04, 732]), 682)).toBeCloseTo(50)
    expect(bufferedAhead(ranges([0, 40], [41, 60]), 30)).toBe(10)
  })
  it('is 0 outside every range', () => {
    expect(bufferedAhead(ranges([0, 40], [300, 360]), 100)).toBe(0)
    expect(bufferedAhead(ranges(), 5)).toBe(0)
  })
  it('takes a range that starts just after the position', () => {
    expect(bufferedAhead(ranges([300.1, 330]), 300)).toBe(30)
  })
})

describe('StallMeter', () => {
  it('counts buffering after the first frame, and its time', () => {
    const m = new StallMeter()
    m.asked(0)
    m.waiting(100) // before the first frame: startup, not a stall
    m.playing(1_500)
    m.waiting(10_000)
    expect(m.report(11_000)).toEqual({ stalls: 1, stallMs: 1_000, startupMs: 1_500 })
    m.playing(12_000)
    m.waiting(20_000)
    m.playing(20_500)
    expect(m.report(30_000)).toEqual({ stalls: 2, stallMs: 2_500 })
  })
  it('sends the startup time once, for the first stream only', () => {
    const m = new StallMeter()
    m.asked(0)
    m.playing(800)
    expect(m.report(1_000).startupMs).toBe(800)
    expect(m.report(2_000).startupMs).toBeUndefined()
    m.asked(5_000)
    m.playing(9_000)
    expect(m.report(10_000).startupMs).toBeUndefined()
  })
  it('does not time a first frame that waited on the viewer', () => {
    const m = new StallMeter()
    m.asked(0)
    m.untimed()
    m.playing(60_000)
    expect(m.report(61_000).startupMs).toBeUndefined()
  })
  it('excuses buffering after a seek or a resume until playing again', () => {
    const m = new StallMeter()
    m.asked(0)
    m.playing(1_000)
    m.excuse()
    m.waiting(2_000)
    m.playing(3_000)
    m.paused(4_000)
    m.excuse()
    m.waiting(5_000)
    m.playing(6_000)
    expect(m.report(7_000)).toMatchObject({ stalls: 0, stallMs: 0 })
    m.waiting(8_000)
    expect(m.report(8_500)).toMatchObject({ stalls: 1, stallMs: 500 })
  })
  it('counts again once a seek inside the buffer settles', () => {
    const m = new StallMeter()
    m.asked(0)
    m.playing(1_000)
    m.excuse()
    m.settled()
    m.waiting(2_000)
    expect(m.report(2_000).stalls).toBe(1)
  })
  it('starts over with each decision', () => {
    const m = new StallMeter()
    m.asked(0)
    m.playing(1_000)
    m.waiting(2_000)
    m.asked(3_000)
    m.waiting(3_500) // the new stream's start
    expect(m.report(4_000)).toMatchObject({ stalls: 0, stallMs: 0 })
  })
  it('takes buffering again within a second for the same stall', () => {
    const m = new StallMeter()
    m.asked(0)
    m.playing(1_000)
    expect(m.waiting(10_000)).toBe(true)
    m.playing(10_200)
    expect(m.waiting(10_700)).toBe(false)
    m.playing(20_700)
    expect(m.report(21_000)).toMatchObject({ stalls: 1, stallMs: 10_200 })
    expect(m.waiting(30_000)).toBe(true)
  })
  it('stops the stall clock while paused', () => {
    const m = new StallMeter()
    m.asked(0)
    m.playing(1_000)
    m.waiting(2_000)
    m.paused(3_000)
    expect(m.report(60_000)).toMatchObject({ stalls: 1, stallMs: 1_000 })
  })
})

describe('waitingAtJoint', () => {
  it('excuses a wait at an encoder joint with media buffered past it', () => {
    // Recorded on a family's restarted run: a 30 ms hole, 48 s beyond it,
    // and the second wait just after the player stepped over it.
    expect(waitingAtJoint(ranges([246.07, 282.01], [282.04, 330]), 281.95)).toBe(true)
    expect(waitingAtJoint(ranges([246.07, 282.01], [282.04, 330]), 282.14)).toBe(true)
  })

  it('counts waits with the buffer in one piece, a wide hole or nothing past it', () => {
    // A progressive file waited with three seconds buffered (recorded).
    expect(waitingAtJoint(ranges([0, 10.32]), 7.34)).toBe(false)
    expect(waitingAtJoint(ranges([50, 100.02], [101, 160]), 100)).toBe(false)
    expect(waitingAtJoint(ranges([50, 100.02], [100.1, 100.6]), 100)).toBe(false)
    expect(waitingAtJoint(ranges([50, 100.02], [100.05, 160]), 120)).toBe(false)
    expect(waitingAtJoint(ranges(), 0)).toBe(false)
  })
})
