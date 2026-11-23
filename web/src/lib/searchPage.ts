import type { components } from '../api/schema'
import { formatDate, plural } from './format'
import { movieLine, showStatusWords } from './cardFacts'
import { matchRank } from './searchSuggestions'

type SearchResults = components['schemas']['SearchResults']
type Movie = components['schemas']['Movie']
type Show = components['schemas']['Show']
type SearchEpisode = components['schemas']['SearchEpisode']
type Requestable = components['schemas']['Requestable']

// The Search page: the library's titles first, the best match on top with the
// person's place in it, episodes that play from the list, what's on live TV,
// and, set apart, what the library doesn't have.

/** The page's sections, in order, and the chips that narrow to one of them. */
export type SearchSection = 'movies' | 'shows' | 'episodes' | 'live' | 'requests'
export type SearchChip = { value: SearchSection | undefined; label: string; count: number }

export type BestMatch = { kind: 'movie'; item: Movie } | { kind: 'show'; item: Show }

/**
 * The title the words most likely mean: the movie or show whose title
 * matches best, if its title is the words, starts with them, or has a word
 * starting with them. Shows win a tie, since a show's page is where more
 * of a person's place is kept; within a kind, the server's order holds.
 */
export function bestMatch(r: Pick<SearchResults, 'movies' | 'shows'>, q: string): BestMatch | undefined {
  let best: { m: BestMatch; rank: number } | undefined
  const consider = (m: BestMatch) => {
    const rank = matchRank(m.item.title, q)
    if (rank <= 2 && (!best || rank < best.rank)) best = { m, rank }
  }
  for (const item of r.shows) consider({ kind: 'show', item })
  for (const item of r.movies) consider({ kind: 'movie', item })
  return best?.m
}

/** The chips over the results, with a count each; a section with nothing in it has no chip. */
export function searchChips(counts: Record<SearchSection, number>): SearchChip[] {
  const named: [SearchSection, string][] = [
    ['movies', 'Movies'],
    ['shows', 'Shows'],
    ['episodes', 'Episodes'],
    ['live', 'Live TV'],
    ['requests', 'Not in your library'],
  ]
  const all = Object.values(counts).reduce((a, b) => a + b, 0)
  return [{ value: undefined, label: 'All', count: all }, ...named.filter(([k]) => counts[k] > 0).map(([value, label]) => ({ value, label, count: counts[value] }))]
}

/** Whether a section shows under the chip chosen. */
export function sectionShown(chosen: SearchSection | undefined, section: SearchSection): boolean {
  return chosen === undefined || chosen === section
}

/** "14 results", "1 result". */
export function resultsWords(n: number): string {
  return plural(n, 'result')
}

/** A movie card's line in the results: "2024 · 1 h 46 min". */
export function searchMovieLine(m: Pick<Movie, 'year' | 'runtimeMin'>): string {
  return movieLine(m)
}

/** A show card's line: "24 episodes · Returning". */
export function searchShowLine(s: Pick<Show, 'year' | 'episodeCount' | 'status' | 'nextAirDate'>, now = Date.now()): string {
  return [s.episodeCount ? plural(s.episodeCount, 'episode') : s.year ? String(s.year) : '', showStatusWords(s, now)].filter(Boolean).join(' · ')
}

/** An episode row's first line, "Saltmarsh · S1 E3 · Safe Harbor", and its second, "Aired Sep 12, 2025". */
export function episodeRowWords(e: Pick<SearchEpisode, 'showTitle' | 'seasonNumber' | 'number' | 'title' | 'airDate' | 'watched'>): { title: string; line: string; label: string } {
  const code = `S${e.seasonNumber} E${e.number}`
  return {
    title: [e.showTitle, code, e.title].filter(Boolean).join(' · '),
    line: [e.airDate ? `Aired ${formatDate(e.airDate)}` : '', e.watched ? 'Watched' : ''].filter(Boolean).join(' · '),
    label: [e.showTitle, `season ${e.seasonNumber} episode ${e.number}`, e.title].filter(Boolean).join(', '),
  }
}

/** What the library lacks: the requestable search's titles not already on the server. */
export function notInLibrary(items: readonly Requestable[], offered: { movies?: boolean; shows?: boolean }): Requestable[] {
  return items.filter((r) => r.state !== 'available' && (r.mediaType === 'movie' ? !!offered.movies : !!offered.shows))
}

/** A title the library lacks, as its card says it: "2024 · Movie", "2021 · Show". */
export function requestableLine(r: Pick<Requestable, 'year' | 'mediaType'>): string {
  return [r.year ? String(r.year) : '', r.mediaType === 'movie' ? 'Movie' : 'Show'].filter(Boolean).join(' · ')
}

/** Where a requested title stands, for its card: undefined while it can be requested. */
export function requestableState(r: Pick<Requestable, 'state' | 'request'>): { label: string; gold: boolean } | undefined {
  switch (r.state) {
    case 'requested':
      return { label: r.request?.createdAt ? `Requested ${formatDate(r.request.createdAt)}` : 'Requested', gold: false }
    case 'downloading':
      return { label: 'Downloading', gold: true }
    case 'available':
      return { label: 'In your library', gold: false }
    default:
      return undefined
  }
}
