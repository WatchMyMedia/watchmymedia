import { useEffect, useState } from 'react'

// The banner a one-step task or a remove leaves on the page it returns to
// (PLAN-ADMIN-SETTINGS.md §3.7): said once, on the next visit to that page.

export type Flash = { tone: 'ok' | 'warn'; title: string; text?: string }

const waiting = new Map<string, Flash>()

export function leaveFlash(page: string, flash: Flash) {
  waiting.set(page, flash)
}

/** The page's banner, if one is waiting; it shows once, until it is dismissed. */
export function useFlash(page: string) {
  const [flash, setFlash] = useState<Flash | null>(() => waiting.get(page) ?? null)
  useEffect(() => { waiting.delete(page) }, [page])
  return [flash, () => setFlash(null)] as const
}
