/**
 * This browser's device ID for activity: random, made once and kept, so the
 * server can tell a person's laptop from their phone. The server names the
 * device from the user agent ("Chrome on macOS").
 */
const KEY = 'watchmymedia.deviceId'

let fallback = ''

export function deviceId(): string {
  try {
    const saved = localStorage.getItem(KEY)
    if (saved) return saved
    const id = crypto.randomUUID()
    localStorage.setItem(KEY, id)
    return id
  } catch {
    // Storage blocked: one ID for this page's life.
    fallback ||= crypto.randomUUID()
    return fallback
  }
}

export function clientInfo() {
  return { deviceId: deviceId(), product: 'WatchMyMedia Web' }
}
