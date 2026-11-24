import { describe, expect, it } from 'vitest'
import type { Segment } from '../../player'
import { atRecordingEdge, bands, percent, scrubberView } from './scrubber'

const segment = (kind: Segment['kind'], startMs: number, endMs?: number): Segment => ({ id: startMs, kind, startMs, endMs, final: true, confidence: 1, source: 'manual' })

describe('the scrubber', () => {
  it('places what has played, what is buffered and the thumb', () => {
    expect(scrubberView({ positionMs: 750_000, durationMs: 3_130_000, timelineMs: 3_130_000, bufferedMs: 30_000 })).toMatchObject({
      played: percent(750_000, 3_130_000), buffered: percent(780_000, 3_130_000), thumb: percent(750_000, 3_130_000), seeking: false, gap: undefined, edge: undefined,
    })
    expect(percent(5, 0)).toBe(0)
    expect(percent(20, 10)).toBe(100)
  })

  it('moves the thumb to a seek’s target and shades the way there, either way', () => {
    const view = (targetMs: number) => scrubberView({ positionMs: 500, durationMs: 1000, timelineMs: 1000, scrub: { targetMs, stepMs: 10_000, step: '+10 s', holding: true } })
    expect(view(700)).toMatchObject({ played: 50, thumb: 70, gap: { left: 50, width: 20 }, seeking: true })
    expect(view(200)).toMatchObject({ thumb: 20, gap: { left: 20, width: 30 } })
  })

  it('draws a growing recording on its planned length, with where the written part ends', () => {
    const v = scrubberView({ positionMs: 490_000, durationMs: 24 * 60_000, timelineMs: 33 * 60_000, recording: { now: true } })
    expect(v.edge).toBeCloseTo(72.7, 1)
    expect(v.played).toBeCloseTo(24.7, 1)
    expect(atRecordingEdge({ now: true, goToNow: false, starting: false })).toBe(true)
    expect(atRecordingEdge({ now: true, goToNow: true, starting: false })).toBe(false)
    expect(atRecordingEdge({ now: false, goToNow: false, starting: false })).toBe(false)
  })

  it('bands the intro and recap in gold and the rest light, running an open segment to the end', () => {
    expect(bands([segment('intro', 0, 92_000), segment('credits', 2_985_000)], 3_130_000, 3_130_000)).toEqual([
      { key: 'intro-0', left: 0, width: percent(92_000, 3_130_000), opening: true },
      { key: 'credits-2985000', left: percent(2_985_000, 3_130_000), width: 100 - percent(2_985_000, 3_130_000), opening: false },
    ])
    expect(bands([segment('commercial', 500, 400)], 1000, 1000)).toEqual([])
    expect(bands(undefined, 1000, 1000)).toEqual([])
  })
})
