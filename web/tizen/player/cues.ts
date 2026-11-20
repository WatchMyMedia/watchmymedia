import type { Cue } from './vtt'

const NONE: readonly Cue[] = Object.freeze([])

/**
 * Which cues show at a time, for a track's cues in file time. A seek is just
 * another time: there is no state to drift. Overlapping cues all show, in
 * start order, and `next` says when the set changes, so the caller can wake
 * then rather than poll.
 */
export class CueSchedule {
  private readonly cues: Cue[]
  // The latest end of the cues up to each index: a backward scan stops once no earlier cue reaches t.
  private readonly reach: number[]
  private last: readonly Cue[] = NONE

  constructor(cues: readonly Cue[]) {
    this.cues = [...cues].sort((a, b) => a.startMs - b.startMs)
    let reach = -Infinity
    this.reach = this.cues.map((c) => (reach = Math.max(reach, c.endMs)))
  }

  /** The last index whose cue starts at or before t, or -1. */
  private before(t: number): number {
    let lo = 0, hi = this.cues.length - 1, found = -1
    while (lo <= hi) {
      const mid = (lo + hi) >> 1
      if (this.cues[mid].startMs <= t) { found = mid; lo = mid + 1 } else hi = mid - 1
    }
    return found
  }

  /**
   * The cues showing at t, the same array as last time when nothing changed
   * (so a store can skip an update), and the next time the set changes.
   */
  at(t: number): { cues: readonly Cue[]; next: number } {
    const i = this.before(t)
    const showing: Cue[] = []
    let next = i + 1 < this.cues.length ? this.cues[i + 1].startMs : Infinity
    for (let j = i; j >= 0 && this.reach[j] > t; j--) {
      const cue = this.cues[j]
      if (cue.endMs > t) { showing.push(cue); next = Math.min(next, cue.endMs) }
    }
    showing.reverse()
    const same = showing.length === this.last.length && showing.every((c, k) => c === this.last[k])
    if (!same) this.last = showing.length ? showing : NONE
    return { cues: this.last, next }
  }
}
