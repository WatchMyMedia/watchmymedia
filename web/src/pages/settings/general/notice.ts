// The line General shows once after the Add a TMDB key task saves (a
// one-step task ends with a banner on its page). Kept for the next visit only.

let pending: string | null = null

export function leaveNotice(text: string) {
  pending = text
}

export function peekNotice() {
  return pending
}

export function clearNotice() {
  pending = null
}
