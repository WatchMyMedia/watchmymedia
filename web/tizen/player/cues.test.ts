import { describe, expect, it } from 'vitest'
import { CueSchedule } from './cues'
import type { Cue } from './vtt'

const cue = (id: number, startMs: number, endMs: number): Cue => ({ id, startMs, endMs, lines: [`cue ${id}`], top: false })

describe('cue schedule', () => {
  const schedule = () => new CueSchedule([cue(0, 1000, 3000), cue(1, 2000, 2500), cue(2, 10_000, 60_000), cue(3, 20_000, 21_000)])

  it('shows the cues at a time, overlapping ones in start order, and when the set next changes', () => {
    const s = schedule()
    expect(s.at(500)).toEqual({ cues: [], next: 1000 })
    expect(s.at(1000).cues.map((c) => c.id)).toEqual([0])
    expect(s.at(2200)).toEqual({ cues: [cue(0, 1000, 3000), cue(1, 2000, 2500)], next: 2500 })
    expect(s.at(2500)).toEqual({ cues: [cue(0, 1000, 3000)], next: 3000 })
    expect(s.at(3000)).toEqual({ cues: [], next: 10_000 })
  })

  it('finds a long cue that started well before through seeks either way', () => {
    const s = schedule()
    expect(s.at(20_500).cues.map((c) => c.id)).toEqual([2, 3])
    expect(s.at(1500).cues.map((c) => c.id)).toEqual([0])
    expect(s.at(45_000)).toEqual({ cues: [cue(2, 10_000, 60_000)], next: 60_000 })
    expect(s.at(70_000)).toEqual({ cues: [], next: Infinity })
  })

  it('gives the same array while nothing changes', () => {
    const s = schedule()
    const first = s.at(1100).cues
    expect(s.at(1900).cues).toBe(first)
    expect(s.at(2100).cues).not.toBe(first)
    expect(s.at(5000).cues).toBe(s.at(6000).cues)
  })

  it('copes with no cues', () => {
    expect(new CueSchedule([]).at(0)).toEqual({ cues: [], next: Infinity })
  })
})
