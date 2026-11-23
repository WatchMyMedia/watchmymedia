import { useCallback, useState } from 'react'
import { parsePrefs, prefsKey, type BrowsePrefs } from '../lib/browsePrefs'

function read(libraryId: number): BrowsePrefs {
  try {
    return parsePrefs(localStorage.getItem(prefsKey(libraryId)))
  } catch {
    return {}
  }
}

/**
 * A library page's choices as this browser remembers them, read once when
 * the page opens, and a way to remember a change. Storage that refuses
 * (a private window, blocked site data) remembers nothing and the page still
 * works.
 */
export function useBrowsePrefs(libraryId: number): [BrowsePrefs, (change: Partial<BrowsePrefs>) => void] {
  const [prefs, setPrefs] = useState<BrowsePrefs>(() => read(libraryId))
  const save = useCallback(
    (change: Partial<BrowsePrefs>) => {
      setPrefs((before) => {
        const next = { ...before, ...change }
        try {
          localStorage.setItem(prefsKey(libraryId), JSON.stringify(next))
        } catch {
          // Not remembered; the choice still applies on this visit.
        }
        return next
      })
    },
    [libraryId],
  )
  return [prefs, save]
}
