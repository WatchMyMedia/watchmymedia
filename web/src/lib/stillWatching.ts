/**
 * "Are you still watching?". The player asks before
 * starting the next episode by itself when the session has run 90 minutes
 * and either 3 episodes have played without input or there has been no
 * input for 90 minutes. The rule is shared; where the counts live is not:
 * the browser keeps them per tab (below), so they survive moving between
 * episodes, and the Samsung TV keeps one StillWatchingSession for the app.
 */
const KEY = 'wmm.stillWatching'
export const STILL_WATCHING_LONG_MS = 90 * 60_000
export const STILL_WATCHING_EPISODES = 3

export type StillWatchingCounts = { sessionStart: number; lastInput: number; episodesSinceInput: number }

/** Whether to ask before the next episode starts. */
export function stillWatchingDue(s: StillWatchingCounts, now: number): boolean {
  return now - s.sessionStart >= STILL_WATCHING_LONG_MS && (s.episodesSinceInput >= STILL_WATCHING_EPISODES || now - s.lastInput >= STILL_WATCHING_LONG_MS)
}

/** The counts in memory, on a clock of the caller's: the session starts with the first play. */
export class StillWatchingSession {
  private counts?: StillWatchingCounts
  constructor(private now: () => number = Date.now) {}

  notePlay() {
    if (!this.counts) { const now = this.now(); this.counts = { sessionStart: now, lastInput: now, episodesSinceInput: 0 } }
  }

  /** A key, click or seek: the viewer is there. */
  noteInput() {
    if (this.counts) this.counts = { ...this.counts, lastInput: this.now(), episodesSinceInput: 0 }
  }

  /** The player moved on to another episode by itself. */
  noteAutoAdvance() {
    if (this.counts) this.counts = { ...this.counts, episodesSinceInput: this.counts.episodesSinceInput + 1 }
  }

  shouldAsk(): boolean {
    return !!this.counts && stillWatchingDue(this.counts, this.now())
  }
}

function read(): StillWatchingCounts {
  try {
    const raw = sessionStorage.getItem(KEY)
    if (raw) return JSON.parse(raw) as StillWatchingCounts
  } catch {
    // Private windows and blocked storage: fall through to a fresh session.
  }
  const now = Date.now()
  const s = { sessionStart: now, lastInput: now, episodesSinceInput: 0 }
  write(s)
  return s
}

function write(s: StillWatchingCounts) {
  try {
    sessionStorage.setItem(KEY, JSON.stringify(s))
  } catch {
    // Nothing to do: without storage the prompt never appears, which is the safe default.
  }
}

/** A key, click or seek: the viewer is there. */
export function noteInput() {
  const s = read()
  write({ ...s, lastInput: Date.now(), episodesSinceInput: 0 })
}

/** The player moved on to another episode by itself. */
export function noteAutoAdvance() {
  const s = read()
  write({ ...s, episodesSinceInput: s.episodesSinceInput + 1 })
}

/** Whether to ask before the next episode starts, for this tab. */
export function shouldAskStillWatching(now = Date.now()): boolean {
  return stillWatchingDue(read(), now)
}
