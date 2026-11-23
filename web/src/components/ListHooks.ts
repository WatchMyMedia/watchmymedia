import { useState } from 'react'
import { filterEntries, showingWords, type ListFilter } from '../lib/listProgress'
import type { MediaListEntry } from '../lib/mediaLists'

/** How many of a list's titles show at first, and how many more each press of Show more adds. */
export const LIST_STEP = 60

/** A list's titles under a chip, a step at a time. */
export function useListView(entries: readonly MediaListEntry[]) {
  const [filter, setFilter] = useState<ListFilter>('all')
  const [count, setCount] = useState(LIST_STEP)
  const filtered = filterEntries(entries, filter)
  return {
    filter,
    setFilter: (f: ListFilter) => {
      setFilter(f)
      setCount(LIST_STEP)
    },
    shown: filtered.slice(0, count),
    of: filtered.length,
    showing: showingWords(Math.min(count, filtered.length), filtered.length),
    more: Math.max(0, Math.min(LIST_STEP, filtered.length - count)),
    showMore: () => setCount((c) => c + LIST_STEP),
  }
}
