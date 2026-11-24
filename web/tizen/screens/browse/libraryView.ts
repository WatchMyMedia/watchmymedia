import type { components } from '../../../src/api/schema'
import type { ItemsParams } from '../../../src/api/queries'
import { itemFilter, itemSort, sortOptions, type ItemFilter, type ItemFilterValue, type ItemSort, type LibraryType } from '../../../src/lib/browseOptions'
import { formatRuntime } from '../../../src/lib/format'

// TV Shows and Movies on the TV:
// the grid's choices, how they show in a focus id, and the header's words.
// The sorts and filters themselves are the web's (browseOptions.ts); API
// level 34 adds In progress, Following and Rated, and filters that combine.

type ItemTotals = components['schemas']['ItemTotals']

/** A filter the grid can set: one a row at most. Below API level 34 the server takes one at a time. */
export type GridFilter = ItemFilterValue

/** The grid's choices: the sort, the filters (one per Filter row), genre, decade, and a rating ('' for any). */
export type GridChoices = { sort: ItemSort; filters: GridFilter[]; genre: string; decade: number; rating: string }

/** As Android TV opens a library: newest first, nothing narrowed. */
export const DEFAULT_CHOICES: GridChoices = { sort: 'added', filters: [], genre: '', decade: 0, rating: '' }

const LEVEL_34_FILTERS = new Set<string>(['inProgress', 'following'])

/** A filter the grid knows, at any level, else undefined. */
export function gridFilter(v: unknown): GridFilter | undefined {
  return itemFilter(v) ?? (typeof v === 'string' && LEVEL_34_FILTERS.has(v) ? (v as GridFilter) : undefined)
}

/** A See all's choices (a route's, or a row's link), the ones the grid knows; the rest as they open. */
export function choicesFrom(p: { sort?: string; filter?: string; filters?: readonly unknown[]; genre?: string; decade?: number; rating?: string }): GridChoices {
  const filters = [...(p.filters ?? []), p.filter].map(gridFilter).filter((f): f is GridFilter => !!f)
  return {
    sort: itemSort(p.sort) ?? DEFAULT_CHOICES.sort,
    // One a row: a later one for the same row gives way to the first.
    filters: filters.filter((f, i) => filters.findIndex((g) => FILTER_ROW[g] === FILTER_ROW[f]) === i),
    genre: typeof p.genre === 'string' ? p.genre : '',
    decade: p.decade && Number.isInteger(p.decade) && p.decade > 0 ? p.decade : 0,
    rating: typeof p.rating === 'string' ? p.rating : '',
  }
}

/**
 * What the server is asked for these choices (the web's `itemsQuery`): from
 * API level 34 every filter, combined, and the rating; below it the first
 * filter the server knows, as `filter`, and no rating.
 */
export function gridQuery(c: GridChoices, level34: boolean): Omit<ItemsParams, 'limit' | 'offset'> {
  const base = { sort: c.sort, genre: c.genre || undefined, decade: c.decade || undefined }
  if (level34) return { ...base, filter: '', filters: c.filters, ...(c.rating ? { contentRating: c.rating } : {}) }
  const first = c.filters.find((f): f is Exclude<ItemFilter, ''> => !LEVEL_34_FILTERS.has(f))
  return { ...base, filter: first ?? '' }
}

/** Whether a filter, genre, decade or rating narrows the grid (Nothing matches offers Clear filters). */
export function narrowed(c: GridChoices): boolean {
  return c.filters.length > 0 || !!c.genre || !!c.decade || !!c.rating
}

/** The grid's focus group for these choices: another sort or filter is another grid, starting at its first title. */
export function gridGroup(libraryId: number, c: GridChoices): string {
  return `grid/${libraryId}/${c.sort}/${c.filters.join('+') || 'all'}/${encodeURIComponent(c.genre) || 'any'}/${c.decade || 'any'}${c.rating ? `/${encodeURIComponent(c.rating)}` : ''}`
}

/** "movie", "show". */
export function libraryNoun(type: LibraryType): string {
  return type === 'tv' ? 'show' : 'movie'
}

function count(n: number, word: string): string {
  return `${n.toLocaleString()} ${n === 1 ? word : `${word}s`}`
}

const SORT_WORDS: Record<ItemSort, string> = {
  title: 'A to Z', added: 'newest first', year: 'newest first', rating: 'highest rated first', released: 'newest first', runtime: 'longest first', aired: 'last aired first',
}

/** "A to Z", "newest first": the order the server sorts in (titles ascending, the rest descending). */
export function sortWords(sort: ItemSort): string {
  return SORT_WORDS[sort]
}

export function sortLabel(type: LibraryType, sort: ItemSort): string {
  return sortOptions(type).find((o) => o.value === sort)?.label ?? 'Title'
}

/** "37 you haven’t watched", "12 with episodes you haven’t seen": what is left, from API level 34's totals. */
export function unwatchedWords(type: LibraryType, unwatched: number | undefined): string {
  if (!unwatched) return ''
  return type === 'tv' ? `${unwatched.toLocaleString()} with episodes you haven’t seen` : `${unwatched.toLocaleString()} you haven’t watched`
}

/**
 * The line under the library's name: "214 movies · 37 you haven’t watched ·
 * 2 lists", or with the sort in the header (no rows above the grid) "142
 * shows · 12 with episodes you haven’t seen · A to Z". Counts are the
 * library's, whatever the grid is narrowed to. A server below API level 34
 * sends no unwatched count; TV Shows then counts its episodes, as before.
 */
export function countsLine(type: LibraryType, o: { totals?: ItemTotals; total?: number; lists?: number; sort?: ItemSort }): string {
  const items = o.totals?.items ?? o.total
  const left = o.totals?.unwatched
  return [
    items !== undefined ? count(items, libraryNoun(type)) : '',
    left !== undefined ? unwatchedWords(type, left) : type === 'tv' && o.totals?.episodes ? count(o.totals.episodes, 'episode') : '',
    o.lists ? count(o.lists, 'list') : '',
    o.sort ? sortWords(o.sort) : '',
  ].filter(Boolean).join(' · ')
}

/**
 * The genre chips: the library's genres, without those the other choices
 * leave empty (the chosen one stays, so it can be cleared).
 */
export function genreChips(genres: readonly string[], counts: Record<string, number> | undefined, chosen: string): string[] {
  return genres.filter((g) => g === chosen || !counts || (counts[g] ?? 0) > 0)
}

/** "1980s". */
export function decadeLabel(decade: number): string {
  return `${decade}s`
}

type BrowseItem = components['schemas']['BrowseItem']

const WEEKDAYS = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday']

/** When the next episode airs, close by: "today", "tomorrow", "next Thursday"; nothing further off or already past. */
export function nextAirWords(date: string | undefined, now: Date): string | undefined {
  const m = date ? /^(\d{4})-(\d{2})-(\d{2})/.exec(date) : null
  if (!m) return undefined
  const day = new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]))
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  const days = Math.round((day.getTime() - today.getTime()) / 86_400_000)
  if (days < 0 || days > 6) return undefined
  return days === 0 ? 'today' : days === 1 ? 'tomorrow' : `next ${WEEKDAYS[day.getDay()]}`
}

/**
 * A show's place on its card, from API level 34's counts: "All watched",
 * "2 new episodes", "Not started", "6 left to watch", "3 left · next
 * Thursday". Undefined when the server sends no count.
 */
export function showLeftLine(item: Pick<BrowseItem, 'watched' | 'unwatchedCount' | 'newEpisodeCount' | 'episodeCount' | 'nextAirDate'>, now: Date): string | undefined {
  const left = item.unwatchedCount
  if (left === undefined) return undefined
  if (item.watched || left === 0) return 'All watched'
  if (item.newEpisodeCount) return item.newEpisodeCount === 1 ? '1 new episode' : `${item.newEpisodeCount} new episodes`
  if (item.episodeCount !== undefined && left >= item.episodeCount) return 'Not started'
  const next = nextAirWords(item.nextAirDate, now)
  return next ? `${left} left · ${next}` : `${left} left to watch`
}

/** The line under a grid poster: "2026 · 1 h 58 min", "3 left · next Thursday" (API level 34), "2019 · 24 episodes". */
export function gridLine(item: Pick<BrowseItem, 'kind' | 'year' | 'runtimeMin' | 'episodeCount' | 'fileCount' | 'watched' | 'unwatchedCount' | 'newEpisodeCount' | 'nextAirDate'>, now = new Date()): string | undefined {
  if (item.kind === 'show') {
    const left = showLeftLine(item, now)
    if (left) return left
  }
  const parts = item.kind === 'movie' ? [item.year ? String(item.year) : '', item.runtimeMin ? formatRuntime(item.runtimeMin) : '']
    : item.kind === 'show' ? [item.year ? String(item.year) : '', item.episodeCount ? count(item.episodeCount, 'episode') : '']
      : [item.fileCount ? count(item.fileCount, 'file') : 'Not matched yet']
  return parts.filter(Boolean).join(' · ') || undefined
}

/**
 * The Filter panel's rows. From API level 34 each row is its own filter and
 * they combine; below it the server takes one filter at a time, so "Watched
 * or not", "Still running" and "Picture" share it: choosing in one sets the
 * others back to Any. Genre and decade are separate either way.
 */
export type FilterRow = 'watched' | 'running' | 'following' | 'picture' | 'genre' | 'decade' | 'rating'

/** The rows whose choices are the server's filters. */
type FilterOnlyRow = Exclude<FilterRow, 'genre' | 'decade' | 'rating'>

const FILTER_ROW: Record<GridFilter, FilterOnlyRow> = { unwatched: 'watched', inProgress: 'watched', continuing: 'running', ended: 'running', following: 'following', '4k': 'picture', hdr: 'picture' }
const FILTER_WORDS: Record<GridFilter, string> = { unwatched: 'Unwatched', inProgress: 'In progress', continuing: 'Still running', ended: 'Ended', following: 'Following', '4k': '4K', hdr: 'HDR' }

/** The rows a library's Filter panel has, in order: Following and Rated only from API level 34, Rated once the library has ratings. */
export function filterRows(type: LibraryType, o: { genres: boolean; decades: boolean; ratings?: boolean; level34?: boolean }): FilterRow[] {
  return ([
    'watched',
    ...(type === 'tv' ? ['running' as const] : []),
    ...(type === 'tv' && o.level34 ? ['following' as const] : []),
    ...(o.genres ? ['genre' as const] : []),
    ...(o.decades ? ['decade' as const] : []),
    ...(o.ratings && o.level34 ? ['rating' as const] : []),
    'picture',
  ] as FilterRow[])
}

export const FILTER_ROW_LABEL: Record<FilterRow, string> = { watched: 'Watched or not', running: 'Still running', following: 'Following', picture: 'Picture', genre: 'Genre', decade: 'Decade', rating: 'Rated' }

/** A filter row's choices: the server's filters that belong to it, Any first; In progress and Following from API level 34. */
export function filterChoices(type: LibraryType, row: FilterOnlyRow, level34 = false): { value: GridFilter | ''; label: string }[] {
  const all: GridFilter[] = row === 'watched' ? (level34 ? ['unwatched', 'inProgress'] : ['unwatched'])
    : row === 'running' ? ['continuing', 'ended']
      : row === 'following' ? ['following']
        : type === 'tv' ? ['4k'] : ['4k', 'hdr']
  return [{ value: '', label: 'Any' }, ...all.map((f) => ({ value: f, label: f === 'following' ? 'Shows you follow' : FILTER_WORDS[f] }))]
}

/** "PG-13", or "Not rated" for the server's `unrated`. */
export function ratingLabel(rating: string): string {
  return rating === 'unrated' ? 'Not rated' : rating
}

/** The filter a row has set, if any. */
export function rowFilter(row: FilterRow, c: GridChoices): GridFilter | undefined {
  return c.filters.find((f) => FILTER_ROW[f] === row)
}

/**
 * Chooses `value` in a filter row: from API level 34 it takes that row's
 * place and leaves the others; below it, it is the one filter.
 */
export function chooseFilter(c: GridChoices, row: FilterOnlyRow, value: GridFilter | '', level34: boolean): GridChoices {
  const others = level34 ? c.filters.filter((f) => FILTER_ROW[f] !== row) : []
  return { ...c, filters: value ? [...others, value] : others }
}

/** What a filter row is set to, in words ("Unwatched", "Thriller", "1980s", "Rated PG-13", "Any"). */
export function filterRowValue(row: FilterRow, c: GridChoices): string {
  if (row === 'genre') return c.genre || 'Any'
  if (row === 'decade') return c.decade ? decadeLabel(c.decade) : 'Any'
  if (row === 'rating') return c.rating ? ratingLabel(c.rating) : 'Any'
  const f = rowFilter(row, c)
  return f ? (f === 'following' ? 'Shows you follow' : FILTER_WORDS[f]) : 'Any'
}

/** The Filter button's value: "Off", or what narrows the grid ("Unwatched, Thriller, 1980s, Rated PG-13"). */
export function filterSummary(c: GridChoices): string {
  const parts = [...c.filters.map((f) => FILTER_WORDS[f]), c.genre, c.decade ? decadeLabel(c.decade) : '', c.rating ? `Rated ${ratingLabel(c.rating)}` : ''].filter(Boolean)
  return parts.length ? parts.join(', ') : 'Off'
}

/** The line under the library's name while it is narrowed: "23 of 214 movies". */
export function narrowedLine(type: LibraryType, shown: number, all: number): string {
  return `${shown.toLocaleString()} of ${count(all, libraryNoun(type))}`
}

const FILTER_ADJECTIVE: Record<GridFilter, string> = { unwatched: 'unwatched', inProgress: 'half-watched', continuing: 'still running', ended: 'ended', following: 'followed', '4k': '4K', hdr: 'HDR' }

/** Why nothing matches, in a sentence: "There are no unwatched thriller movies from the 1980s rated PG-13 in Movies." */
export function nothingMatches(type: LibraryType, c: GridChoices): string {
  const noun = type === 'tv' ? 'shows' : 'movies'
  const kind = c.genre ? `${c.genre.toLowerCase()} ${noun}` : noun
  const what = [...c.filters.map((f) => FILTER_ADJECTIVE[f]), kind].join(' ')
  const when = c.decade ? ` from the ${decadeLabel(c.decade)}` : ''
  const rated = c.rating ? (c.rating === 'unrated' ? ' without a rating' : ` rated ${c.rating}`) : ''
  return `There are no ${what}${when}${rated} in ${type === 'tv' ? 'TV Shows' : 'Movies'}.`
}
