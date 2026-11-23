import { useEffect, useState } from 'react'

/** The time now, moving on every `everyMs`: for end times and time left, which change as the page stays open. */
export function useNow(everyMs = 30_000): number {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const t = window.setInterval(() => setNow(Date.now()), everyMs)
    return () => window.clearInterval(t)
  }, [everyMs])
  return now
}
