import { SETTLE_MS } from './newEpisodes'

/**
 * When the tab holding the new episodes' lock checks:
 * at once when it starts holding it, and once SETTLE_MS have passed
 * since the last library.changed. The server holds an arrival back 2
 * minutes, so a check inside that window can miss it: any check that starts
 * before the window has passed is followed by one once it has. The time of
 * the last change is kept outside the tab (lastChange/noteChange, the
 * browser's storage), so a tab that takes the lock, or a page that was
 * redrawn or reloaded, still checks when it ends. A check asked for while
 * one runs runs again when that one finishes.
 */
export type ScheduleDeps = {
  now: () => number
  setTimer: (run: () => void, ms: number) => unknown
  clearTimer: (timer: unknown) => void
  /** When a library last changed, in this browser's shared record. */
  lastChange: () => number | undefined
  noteChange: (at: number) => void
}

export type CheckSchedule = {
  /** This tab holds the lock: check now, then as the window says. */
  start: (check: () => Promise<void>) => void
  /** This tab let the lock go. */
  stop: () => void
  /**
   * A library changed (every tab records it, holding the lock or not); or
   * the event stream came back after a drop, which may have hidden one, so
   * the holder checks at once as well.
   */
  libraryChanged: (reason: 'changed' | 'reopened') => void
  /** Checks now, or after the running check when one runs. */
  checkNow: () => Promise<void>
}

export function createCheckSchedule(deps: ScheduleDeps): CheckSchedule {
  let check: (() => Promise<void>) | undefined
  let timer: unknown
  let running = false
  let again = false

  const settled = () => {
    const at = deps.lastChange()
    return at === undefined ? undefined : at + SETTLE_MS
  }
  const clear = () => {
    if (timer !== undefined) deps.clearTimer(timer)
    timer = undefined
  }
  /** A check at the end of the window, when one that started before the end needs it. */
  const after = (started: number) => {
    const due = settled()
    if (!check || due === undefined || started >= due) return
    clear()
    timer = deps.setTimer(() => {
      timer = undefined
      void checkNow()
    }, Math.max(due - deps.now(), 0))
  }

  async function checkNow(): Promise<void> {
    if (!check) return
    if (running) {
      again = true
      return
    }
    running = true
    const started = deps.now()
    try {
      await check()
    } catch {
      // A failed check keeps its cursor; the next one catches up.
    } finally {
      running = false
    }
    if (again) {
      again = false
      return checkNow()
    }
    after(started)
  }

  return {
    start(run) {
      check = run
      void checkNow()
    },
    stop() {
      check = undefined
      again = false
      clear()
    },
    libraryChanged(reason) {
      const now = deps.now()
      deps.noteChange(now)
      if (!check) return
      if (reason === 'reopened') void checkNow()
      else after(now)
    },
    checkNow,
  }
}
