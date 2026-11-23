import type { components } from '../api/schema'
import { plural } from './format'

type MediaListEntry = components['schemas']['MediaListEntry']

// How much of a list the person has and has watched, and the filters on a
// list's page, counted from its titles.

export type ListTally = {
  /** Titles on the list. */
  total: number
  /** In the library. */
  have: number
  /** In the library and watched. */
  watched: number
  /** In the library, not watched yet. */
  toWatch: number
  /** Not in the library, Not found included. */
  missing: number
}

export function listTally(entries: readonly MediaListEntry[]): ListTally {
  let have = 0
  let watched = 0
  for (const e of entries) {
    if (!e.item) continue
    have++
    if (e.item.watched) watched++
  }
  return { total: entries.length, have, watched, toWatch: have - watched, missing: entries.length - have }
}

export type ListFilter = 'all' | 'have' | 'missing' | 'unwatched'

/** The chips over a list's titles, with their counts. "Not in your library" only when something is missing. */
export function listFilterChips(t: ListTally): { value: ListFilter; label: string; count: number }[] {
  const chips: { value: ListFilter; label: string; count: number }[] = [
    { value: 'all', label: 'All', count: t.total },
    { value: 'have', label: 'In your library', count: t.have },
  ]
  if (t.missing > 0) chips.push({ value: 'missing', label: 'Not in your library', count: t.missing })
  chips.push({ value: 'unwatched', label: 'Unwatched', count: t.toWatch })
  return chips
}

export function filterEntries(entries: readonly MediaListEntry[], filter: ListFilter): MediaListEntry[] {
  switch (filter) {
    case 'have':
      return entries.filter((e) => !!e.item)
    case 'missing':
      return entries.filter((e) => !e.item)
    case 'unwatched':
      return entries.filter((e) => !!e.item && !e.item.watched)
    default:
      return [...entries]
  }
}

/** The progress panel's words: "18 of 50 in your library", "6 watched", and the legend. */
export function progressWords(t: ListTally): { headline: string; watched: string; legend: { key: 'watched' | 'toWatch' | 'missing'; text: string }[]; label: string } {
  const legend: { key: 'watched' | 'toWatch' | 'missing'; text: string }[] = [
    { key: 'watched', text: `${t.watched.toLocaleString()} watched` },
    { key: 'toWatch', text: `${t.toWatch.toLocaleString()} to watch` },
  ]
  if (t.missing > 0) legend.push({ key: 'missing', text: `${t.missing.toLocaleString()} not in your library` })
  return {
    headline: t.missing > 0 ? `${t.have.toLocaleString()} of ${t.total.toLocaleString()} in your library` : `All ${plural(t.total, 'title')} in your library`,
    watched: `${t.watched.toLocaleString()} watched`,
    legend,
    label: legend.map((l) => l.text).join(', '),
  }
}

/** The bar's two filled parts, in percent of the list: watched, then to watch. */
export function meterParts(t: ListTally): { watched: number; toWatch: number } {
  if (!t.total) return { watched: 0, toWatch: 0 }
  return { watched: (t.watched / t.total) * 100, toWatch: (t.toWatch / t.total) * 100 }
}

/** "Showing 24 of 50"; "Showing all 50" once every title is on the page. */
export function showingWords(shown: number, of: number): string {
  return shown >= of ? `Showing all ${of.toLocaleString()}` : `Showing ${shown.toLocaleString()} of ${of.toLocaleString()}`
}
