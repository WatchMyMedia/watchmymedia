/**
 * "Are you still watching?" bookkeeping, kept per browser tab so it survives
 * moving between episodes. The player asks before starting the next episode
 * when the session is long and nobody has touched anything for a while.
 */
const KEY = 'wmm.stillWatching'
const LONG_MS = 90 * 60_000
const EPISODES_IN_A_ROW = 3

type State = { sessionStart: number; lastInput: number; episodesSinceInput: number }

function read(): State {
  try {
    const raw = sessionStorage.getItem(KEY)
    if (raw) return JSON.parse(raw) as State
  } catch {
    // Private windows and blocked storage: fall through to a fresh session.
  }
  const now = Date.now()
  const s = { sessionStart: now, lastInput: now, episodesSinceInput: 0 }
  write(s)
  return s
}

function write(s: State) {
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

/** Whether to ask before the next episode starts. */
export function shouldAskStillWatching(now = Date.now()): boolean {
  const s = read()
  return now - s.sessionStart >= LONG_MS && (s.episodesSinceInput >= EPISODES_IN_A_ROW || now - s.lastInput >= LONG_MS)
}
