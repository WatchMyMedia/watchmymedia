import { parseVtt, type Cue } from './vtt'

const NONE: readonly Cue[] = Object.freeze([])

/**
 * Subtitles a player draws itself, as every app does: the web player, the
 * Cast receiver and the TV app read a track's WebVTT file once, look its
 * cues up by time, and draw them in their own layer. Drawing them is what
 * lets a subtitle timing move them at once and a size scale them.
 */

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

/** The cue file's address without its token or query, so a renewed token doesn't fetch it again. */
export function trackKey(url: string): string {
  return url.split('#')[0]
}

/**
 * The text tracks' cues, fetched once per address. The server sends WebVTT
 * for every text track (ASS converted when asked for .vtt), in the stream's
 * time: an event playlist's cues are already shifted to its start (?shift=),
 * so a track is read at the player's time, not the file's.
 */
export class CueTracks {
  private tracks = new Map<string, CueSchedule | 'loading' | 'failed'>()
  private closed = false

  constructor(
    private fetchText: (url: string) => Promise<string>,
    private onLoad: () => void,
    private onFail: (key: string) => void,
  ) {}

  /** The cues showing at the stream's time t for the track at url, fetching it the first time; and when they next change. */
  at(url: string, sign: (url: string) => string, t: number): { cues: readonly Cue[]; next: number } {
    const key = trackKey(url)
    const schedule = this.tracks.get(key)
    if (schedule instanceof CueSchedule) return schedule.at(t)
    if (!schedule) this.fetch(key, sign(url))
    return { cues: NONE, next: Infinity }
  }

  /** Whether the track at url has its cues, so a caller can tell loading from a quiet moment. */
  has(url: string): boolean {
    return this.tracks.get(trackKey(url)) instanceof CueSchedule
  }

  private fetch(key: string, signed: string) {
    this.tracks.set(key, 'loading')
    this.fetchText(signed).then((text) => {
      if (this.closed) return
      this.tracks.set(key, new CueSchedule(parseVtt(text)))
      this.onLoad()
    }, () => {
      if (this.closed) return
      this.tracks.set(key, 'failed')
      this.onFail(key)
    })
  }

  close() {
    this.closed = true
    this.tracks.clear()
  }
}

/**
 * Where cues go: those the file placed at the top of the picture above,
 * the rest at the foot, each block in start order.
 */
export function cueBlocks(cues: readonly Cue[]): { top: readonly Cue[]; bottom: readonly Cue[] } {
  const top = cues.filter((c) => c.top)
  return { top: top.length ? top : NONE, bottom: top.length ? cues.filter((c) => !c.top) : cues }
}

/** The time a track's cues are looked up at for a subtitle timing: positive shows them later, so earlier in the track. */
export const cueTime = (playerMs: number, timingMs: number) => playerMs - timingMs
