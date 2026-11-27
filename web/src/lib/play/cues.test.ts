import { describe, expect, it } from 'vitest'
import { CueSchedule, cueBlocks, cueTime } from './cues'
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

describe('subtitle timing in the lookup', () => {
  const s = () => new CueSchedule([cue(0, 1000, 3000), cue(1, 3000, 4000), cue(2, 6000, 7000)])

  it('shows a cue later by looking it up earlier in the track, across a cue boundary', () => {
    // 0.4 s later: at 3.2 s the track is read at 2.8 s, still the first cue.
    expect(s().at(cueTime(3200, 400)).cues.map((c) => c.id)).toEqual([0])
    expect(s().at(cueTime(3400, 400)).cues.map((c) => c.id)).toEqual([1])
    // Nothing yet at 1.2 s, which plays the track's 0.8 s.
    expect(s().at(cueTime(1200, 400)).cues).toEqual([])
  })

  it('shows a cue earlier by looking it up later, and a timing back to 0 reads it as the file has it', () => {
    expect(s().at(cueTime(5000, -1200)).cues.map((c) => c.id)).toEqual([2])
    expect(s().at(cueTime(2900, -200)).cues.map((c) => c.id)).toEqual([1])
    expect(s().at(cueTime(2900, 0)).cues.map((c) => c.id)).toEqual([0])
  })

  it('puts cues the file placed at the top above, the rest at the foot', () => {
    const top = { ...cue(5, 0, 1), top: true }
    const foot = cue(6, 0, 1)
    expect(cueBlocks([top, foot])).toEqual({ top: [top], bottom: [foot] })
    expect(cueBlocks([foot]).top).toEqual([])
  })
})
