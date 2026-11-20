/**
 * Time for the engine. `now` is monotonic and times everything that runs on
 * this TV (countdowns, heartbeats, a recording's growth); `wall` is only for
 * comparing with the server's timestamps (a media token's expiry). Tests
 * pass a clock on fake timers.
 */
export interface Clock {
  now(): number
  wall(): number
  setTimeout(run: () => void, ms: number): unknown
  clearTimeout(handle: unknown): void
  setInterval(run: () => void, ms: number): unknown
  clearInterval(handle: unknown): void
}

type Handle = ReturnType<typeof setTimeout>

export const systemClock: Clock = {
  now: () => performance.now(),
  wall: () => Date.now(),
  setTimeout: (run, ms) => setTimeout(run, ms),
  clearTimeout: (handle) => clearTimeout(handle as Handle),
  setInterval: (run, ms) => setInterval(run, ms),
  clearInterval: (handle) => clearInterval(handle as Handle),
}

/** A random ID from getRandomValues: crypto.randomUUID needs a secure context the packaged app may not be. */
export function randomId(): string {
  const bytes = new Uint8Array(16)
  crypto.getRandomValues(bytes)
  bytes[6] = (bytes[6] & 0x0f) | 0x40
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}
