// How long a confirmed admin password still lasts in this browser.
// The server keeps the grant for ten minutes
// from the confirmation; the page counts from its own confirmation, and from
// the server's `adminConfirmedUntil` when another tab confirmed.

export const GRANT_MS = 10 * 60 * 1000

let until = 0
const listeners = new Set<() => void>()

/** The time the grant ends, in ms since the epoch; 0 for none. */
export function grantUntil() {
  return until
}

export function noteConfirmed(at = Date.now()) {
  setGrantUntil(at + GRANT_MS)
}

/** Takes the later of what this page knows and what the server reports. */
export function setGrantUntil(value: number) {
  if (value === until) return
  until = value
  for (const l of listeners) l()
}

export function forgetGrant() {
  setGrantUntil(0)
}

export function subscribeGrant(listener: () => void) {
  listeners.add(listener)
  return () => { listeners.delete(listener) }
}

/** Whole minutes left, rounded up, so "1 min left" shows until it ends. */
export function minutesLeft(end: number, now: number) {
  return end > now ? Math.ceil((end - now) / 60000) : 0
}

/** The server's optional `adminConfirmedUntil`, as ms; 0 when absent or bad. */
export function parseGrant(value: string | null | undefined) {
  if (!value) return 0
  const t = Date.parse(value)
  return Number.isFinite(t) ? t : 0
}
