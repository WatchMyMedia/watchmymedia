import type { components } from '../api/schema'

type SearchResults = components['schemas']['SearchResults']

/** One row under the app bar's search box: a title or an episode in the library. */
export type Suggestion =
  | { kind: 'movie'; id: number; title: string; year?: number; posterPath?: string }
  | { kind: 'show'; id: number; title: string; year?: number; posterPath?: string }
  | {
      kind: 'episode'
      id: number
      showId: number
      seasonNumber: number
      number: number
      title: string
      showTitle: string
      picturePath?: string
    }

/** Suggestions start at two characters: one matches most of a library and costs the server a full scan. */
export const SUGGEST_MIN_CHARS = 2

/** How long typing has to pause before the library is asked. */
export const SUGGEST_DELAY_MS = 200

const MAX_TITLES = 5
const MAX_EPISODES = 3

/** Lower case without accents, so "Amélie" ranks for "ame". */
function fold(s: string): string {
  return s.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase()
}

/**
 * How well a title matches what was typed, best first: 0 the whole title,
 * 1 its start, 2 the start of a later word, 3 inside a word, 4 not in the
 * title at all (the server matched the original title).
 */
export function matchRank(title: string, typed: string): number {
  const t = fold(title)
  const q = fold(typed.trim())
  if (!q) return 4
  if (t === q) return 0
  if (t.startsWith(q)) return 1
  let rank = 4
  for (let at = t.indexOf(q); at >= 0; at = t.indexOf(q, at + 1)) {
    if (at === 0 || !/[\p{L}\p{N}]/u.test(t[at - 1])) return 2
    rank = 3
  }
  return rank
}

/** Sorts by rank, keeping the server's order (by title) within a rank. */
function ranked<T>(items: T[], rank: (item: T) => number): T[] {
  return items
    .map((item, i) => ({ item, i, r: rank(item) }))
    .sort((a, b) => a.r - b.r || a.i - b.i)
    .map((x) => x.item)
}

/**
 * The rows for the search box, from the same reply the Search page draws:
 * movies and shows together, the best matches first, then a few episodes
 * by name.
 */
export function suggestions(r: SearchResults, typed: string): Suggestion[] {
  const titles: Suggestion[] = [
    ...r.movies.map((m): Suggestion => ({ kind: 'movie', id: m.id, title: m.title, year: m.year, posterPath: m.posterPath })),
    ...r.shows.map((s): Suggestion => ({ kind: 'show', id: s.id, title: s.title, year: s.year, posterPath: s.posterPath })),
  ]
  // Movies and shows each come sorted by title; merged, they keep that order within a rank.
  const byTitle = titles.slice().sort((a, b) => a.title.localeCompare(b.title, undefined, { sensitivity: 'base' }))
  const episodes = r.episodes.map(
    (e): Suggestion => ({
      kind: 'episode',
      id: e.id,
      showId: e.showId,
      seasonNumber: e.seasonNumber,
      number: e.number,
      title: e.title,
      showTitle: e.showTitle,
      picturePath: e.stillPath ?? e.showPosterPath,
    }),
  )
  return [
    ...ranked(byTitle, (s) => matchRank(s.title, typed)).slice(0, MAX_TITLES),
    ...ranked(episodes, (s) => matchRank(s.title, typed)).slice(0, MAX_EPISODES),
  ]
}

/**
 * The highlighted row after an arrow key: -1 is none (the text itself).
 * Down from the last row and up from the first go back to the text, as in
 * a browser's address bar.
 */
export function moveActive(current: number, count: number, step: 1 | -1): number {
  if (count === 0) return -1
  const next = current + step
  if (next < -1) return count - 1
  if (next >= count) return -1
  return next
}
