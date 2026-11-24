import { formatRuntime } from '../../src/lib/format'
import { timeOfDay } from '../../src/lib/liveGuide'

// What a viewer asks of anything partway or about to start: how long is
// left, and when it would end if it played now. Home, the title pages, the
// player and Up next all say it the same way.

const MIN = 60_000

/** "29 min left", "1 h 12 min left": at least a minute, so nothing reads "0 min left". */
export function leftWords(remainingMs: number): string {
  return `${formatRuntime(Math.max(1, Math.round(remainingMs / MIN)))} left`
}

/** "Ends at 8:41 PM": when something this long would end, played from `now`. */
export function endsAtWords(now: number, remainingMs: number, locale?: string): string {
  return `Ends at ${timeOfDay(now + Math.max(0, remainingMs), locale)}`
}

/** What is left of a play `durationMs` long, `positionMs` in; undefined without a length. */
export function remaining(positionMs: number | undefined, durationMs: number | undefined): number | undefined {
  if (!durationMs || durationMs <= 0) return undefined
  return Math.max(0, durationMs - Math.max(0, positionMs ?? 0))
}
