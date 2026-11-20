import { useCallback, useEffect, useState, type CSSProperties } from 'react'
import { problemWords } from '../../ui/problemWords'
import { placeholderTone } from '../../ui/tones'

// What the movie page and the show page share that isn't drawn: their
// placeholder tones, their notices and the words both say.

/** The placeholder gradient a title draws without a picture, the same every time. */
export function toneStyle(seed: string): CSSProperties {
  const [a, b] = placeholderTone(seed)
  return { '--ph-a': a, '--ph-b': b } as CSSProperties
}

/** Said after a report is sent (the Android app's REPORT_SENT and SEERR_REPORT_SENT). */
export function reportSent(seerr: boolean): string {
  return seerr ? 'Sent to Seerr as an issue. Admins hear about it as Seerr is set up to tell them.' : 'Report sent. An admin will take a look.'
}

/** "Marked as watched", "Marked as unwatched". */
export const markedMessage = (watched: boolean) => (watched ? 'Marked as watched' : 'Marked as unwatched')

export type Notice = { title: string; body?: string; problem?: boolean; n: number }

/**
 * A notice that goes after a few seconds (the Android TV app's Notice),
 * drawn by NoticeBanner. `fail` words a failure in our own words, never
 * the server's text.
 */
export function useNotice() {
  const [notice, setNotice] = useState<Notice>()
  useEffect(() => {
    if (!notice) return
    const timer = setTimeout(() => setNotice(undefined), notice.problem ? 7000 : 4000)
    return () => clearTimeout(timer)
  }, [notice])
  const say = useCallback((n: Omit<Notice, 'n'>) => setNotice((was) => ({ ...n, n: (was?.n ?? 0) + 1 })), [setNotice])
  const fail = useCallback((title: string, error: unknown) => say({ title, body: problemWords(error).title, problem: true }), [say])
  return { notice, say, fail }
}
