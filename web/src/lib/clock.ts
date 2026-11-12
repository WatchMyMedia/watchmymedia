/** "1:23", "1:23.5", "0:01:23" or "83" → milliseconds; null when it is not a time. */
export function parseClock(s: string): number | null {
  const t = s.trim()
  if (!t) return null
  const parts = t.split(':')
  if (parts.length > 3 || parts.some((p) => !/^\d+(\.\d+)?$/.test(p))) return null
  let seconds = 0
  for (const p of parts) seconds = seconds * 60 + Number(p)
  return Math.round(seconds * 1000)
}

/** Milliseconds → "m:ss", or "h:mm:ss" from an hour. */
export function clock(ms: number): string {
  const s = Math.round(ms / 1000)
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const sec = String(s % 60).padStart(2, '0')
  return h ? `${h}:${String(m).padStart(2, '0')}:${sec}` : `${m}:${sec}`
}
