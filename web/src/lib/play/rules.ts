/**
 * The player rules of PLAN-CLIENTS.md §2.3 that the browser player and the
 * Cast receiver share, so the two cannot drift: which segment the Skip
 * button offers and when it skips by itself, when the Next Episode card
 * shows, and how often heartbeats go out. Times are seconds of the file.
 */

/** Seconds the Next Episode card counts down before playing it. */
export const NEXT_COUNTDOWN = 10
/** Milliseconds the Skip button waits in "auto-delay" mode before skipping by itself. */
export const AUTO_DELAY_MS = 3000

export const SKIP_LABELS: Record<string, string> = {
  intro: 'Skip Intro',
  recap: 'Skip Recap',
  credits: 'Skip Credits',
  preview: 'Skip Preview',
  commercial: 'Skip Ad',
}

export type SegmentLike = { id?: number; kind: string; startMs: number; endMs?: number | null; final?: boolean }

/** What the viewer set for a segment kind. */
export type SkipMode = 'none' | 'ask' | 'auto' | 'auto-delay'

export const segmentEnd = (s: SegmentLike, duration: number) => (s.endMs ? s.endMs / 1000 : duration)

/**
 * The segment playing at time, not counting the final credits when the Next
 * Episode card takes their place, nor the last half second of a segment.
 */
export function activeSegment<S extends SegmentLike>(segments: S[] | undefined, time: number, duration: number, hasNext: boolean): S | undefined {
  return segments?.find((s) => {
    const end = segmentEnd(s, duration)
    if (end - s.startMs / 1000 < 1 || time < s.startMs / 1000 || time >= end - 0.5) return false
    return !(s.kind === 'credits' && s.final && hasNext)
  })
}

/**
 * What the Skip button does for a segment: a button needs 3 s of segment,
 * skipping by itself 1 s (activeSegment's floor), and a segment the viewer
 * seeked into is never skipped by itself.
 */
export function skipPlan(segment: SegmentLike | undefined, duration: number, mode: SkipMode, seekedIn: boolean, nextCardShowing: boolean) {
  if (!segment || mode === 'none') return { showButton: false, autoAfterMs: undefined, autoNow: false }
  const len = segmentEnd(segment, duration) - segment.startMs / 1000
  return {
    showButton: len >= 3 && !(mode === 'auto' && !seekedIn),
    autoAfterMs: mode === 'auto-delay' && !seekedIn ? AUTO_DELAY_MS : undefined,
    autoNow: mode === 'auto' && !seekedIn && !nextCardShowing,
  }
}

/** IDs of the segments a seek to target lands inside. */
export function segmentsAt<S extends SegmentLike>(segments: S[], target: number, duration: number): number[] {
  return segments.filter((s) => s.id !== undefined && target >= s.startMs / 1000 && target < segmentEnd(s, duration)).map((s) => s.id as number)
}

/**
 * Whether the Next Episode card shows: from upNextMs (the final credits,
 * else shortly before the end) until "Watch credits" hides it, and always
 * at the end; never while "Still watching?" is asked.
 */
export function nextCardShowing(o: { hasNext: boolean; upNextMs: number; time: number; ended: boolean; creditsDismissed: boolean; askingStill: boolean }) {
  const at = o.upNextMs / 1000
  return o.hasNext && at > 0 && (o.ended || (o.time >= at && !o.creditsDismissed)) && !o.askingStill
}

export type BeatState = 'playing' | 'paused' | 'buffering' | 'stopped' | 'closed'

/**
 * Heartbeats go out at every state change, every 10 s while playing and
 * every 30 s otherwise. At Automatic quality they go every 5 s while
 * playing or buffering, so the server hears soon when the stream falls
 * behind (PLAN-AUTO-QUALITY.md §2).
 */
export function beatDue(state: BeatState, lastBeatAt: number, now: number, auto = false) {
  const every = auto && (state === 'playing' || state === 'buffering') ? 5_000 : state === 'playing' ? 10_000 : 30_000
  return now - lastBeatAt >= every
}

type Ranges = { length: number; start(i: number): number; end(i: number): number }

/** Holes up to this long are skipped by players (see waitingAtJoint). */
const SKIPPED_HOLE_S = 0.5

/**
 * Seconds buffered ahead of time t: to the end of the buffered range that
 * holds t, not of the last range (a seek ahead leaves the old one behind),
 * carrying on across holes a player skips, such as an encoder joint's.
 */
export function bufferedAhead(buffered: Ranges, t: number): number {
  for (let i = 0; i < buffered.length; i++) {
    // A range can start a hair after the position it was fetched for.
    if (buffered.start(i) <= t + 0.25 && t <= buffered.end(i)) {
      let end = buffered.end(i)
      for (let j = i + 1; j < buffered.length && buffered.start(j) - end <= SKIPPED_HOLE_S; j++) end = Math.max(end, buffered.end(j))
      return Math.max(0, end - t)
    }
  }
  return 0
}

/**
 * Whether buffering is a pause at a joint rather than the link running dry: a
 * hole of at most half a second in what is buffered, within half a second of
 * the position, with at least a second buffered past it. A transcode's encoder
 * restart leaves such a hole at its first segment (30 ms of AAC audio was
 * seen); the player waits there a moment with tens of seconds buffered beyond.
 * Buffering with the buffer in one piece is a stall, however much it holds:
 * a progressive file waits with seconds in hand when its download falls behind.
 */
export function waitingAtJoint(buffered: Ranges, t: number): boolean {
  for (let i = 1; i < buffered.length; i++) {
    const holeStart = buffered.end(i - 1)
    const holeEnd = buffered.start(i)
    if (holeEnd - holeStart <= SKIPPED_HOLE_S && Math.abs(holeStart - t) <= 0.5 && buffered.end(i) - Math.max(t, holeEnd) >= 1) return true
  }
  return false
}

/**
 * Counts a player's stalls for the heartbeat (PLAN-AUTO-QUALITY.md §3.1):
 * buffering after the stream's first frame that no seek, reload or resume
 * caused, and the time they took; and, once, how long the first stream took
 * from asking for its decision to its first frame. Times are milliseconds.
 */
export class StallMeter {
  private stalls = 0
  private stallMs = 0
  private stallSince = 0
  private stallEnded = 0
  private played = false
  // A seek or a resume is under way: the buffering it brings is not a stall.
  private excused = false
  private askedAt: number | undefined
  private startupMs = 0
  private startupSent = false

  /**
   * A new decision was asked for, or its stream replaced the old one: the
   * count starts afresh, and the startup clock runs from the first ask.
   */
  asked(now: number) {
    if (this.askedAt === undefined && !this.played && !this.startupMs) this.askedAt = now
    this.stalls = this.stallMs = this.stallSince = this.stallEnded = 0
    this.played = this.excused = false
  }

  /** The first frame will not be timed (playback waits on the viewer). */
  untimed() {
    this.askedAt = undefined
  }

  playing(now: number) {
    if (this.stallSince) {
      this.stallMs += now - this.stallSince
      this.stallEnded = now
    }
    this.stallSince = 0
    this.excused = false
    if (!this.played && this.askedAt !== undefined && !this.startupMs) this.startupMs = Math.max(1, now - this.askedAt)
    this.askedAt = undefined
    this.played = true
  }

  /**
   * Buffering began; true when it counts as a new stall. Buffering again
   * within a second of the last stall ending is the same stall (players
   * flicker out of buffering and back).
   */
  waiting(now: number): boolean {
    if (!this.played || this.excused || this.stallSince) return false
    this.stallSince = now
    if (this.stallEnded && now - this.stallEnded < 1000) return false
    this.stalls++
    return true
  }

  /** A seek or a press of play: the buffering that follows is theirs. */
  excuse() {
    this.excused = true
  }

  /**
   * A seek finished with enough to play on. A seek inside the buffer may
   * bring no buffering and so no new "playing"; buffering from here is a stall.
   */
  settled() {
    if (this.played) this.excused = false
  }

  paused(now: number) {
    if (this.stallSince) this.stallMs += now - this.stallSince
    this.stallSince = 0
    this.excused = true
  }

  /** The heartbeat's fields; startupMs goes out once. */
  report(now: number): { stalls: number; stallMs: number; startupMs?: number } {
    const out: { stalls: number; stallMs: number; startupMs?: number } = {
      stalls: this.stalls,
      stallMs: Math.round(this.stallMs + (this.stallSince ? now - this.stallSince : 0)),
    }
    if (this.startupMs && !this.startupSent) {
      out.startupMs = Math.round(this.startupMs)
      this.startupSent = true
    }
    return out
  }
}

/** "S1 · E2 · Title" */
export const episodeLine = (season: number, episode: number, title: string) => `S${season} · E${episode} · ${title}`
