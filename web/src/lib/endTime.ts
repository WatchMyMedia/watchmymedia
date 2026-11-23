// When something would end if it started (or carried on) now, and how much
// of it is left, in the words every page uses: "29 min left · ends 8:41 PM".

const plain = (text: string) => text.replace(/\u202f/g, ' ')

/** "8:41 PM" (or "20:41", by the browser's locale): the clock time `ms` from `now`. */
export function endsAt(ms: number, now: number, locale?: string): string {
  return plain(new Intl.DateTimeFormat(locale, { hour: 'numeric', minute: '2-digit' }).format(now + Math.max(0, ms)))
}

/** "29 min", "1 h 12 min", "2 h", rounded up to the minute; "1 min" for anything under one. */
export function minutesWords(ms: number): string {
  const minutes = Math.max(1, Math.ceil(ms / 60_000))
  if (minutes < 60) return `${minutes} min`
  const h = Math.floor(minutes / 60)
  const m = minutes % 60
  return m ? `${h} h ${m} min` : `${h} h`
}

/** "29 min left", or undefined when nothing is left or the length is unknown. */
export function leftWords(positionMs: number, durationMs: number | undefined): string | undefined {
  if (!durationMs || durationMs <= 0) return undefined
  const left = durationMs - positionMs
  return left > 0 ? `${minutesWords(left)} left` : undefined
}

/** "29 min left · ends 8:41 PM": time left of a play in progress and when it ends if resumed now. */
export function leftAndEnds(positionMs: number, durationMs: number | undefined, now: number, locale?: string): string | undefined {
  const left = leftWords(positionMs, durationMs)
  if (!left || !durationMs) return undefined
  return `${left} · ends ${endsAt(durationMs - positionMs, now, locale)}`
}

/** "Ends 10:10 PM" for a runtime in minutes started now; undefined without one. */
export function endsWords(runtimeMin: number | undefined, now: number, locale?: string): string | undefined {
  if (!runtimeMin || runtimeMin <= 0) return undefined
  return `Ends ${endsAt(runtimeMin * 60_000, now, locale)}`
}

/** The share watched, 0 to 100, for a progress bar. */
export function percentWatched(positionMs: number, durationMs: number | undefined): number {
  if (!durationMs || durationMs <= 0) return 0
  return Math.min(100, Math.max(0, Math.round((positionMs / durationMs) * 100)))
}
