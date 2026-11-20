import type { components } from '../../../src/api/schema'
import { itemFilter, itemSort, sortOptions, type ItemFilter, type ItemSort, type LibraryType } from '../../../src/lib/browseOptions'
import { formatRuntime } from '../../../src/lib/format'

// TV Shows and Movies on the TV (LibraryRows and LibraryAZ on the canvas):
// the grid's choices, how they show in a focus id, and the header's words.
// The sorts and filters themselves are the web's (browseOptions.ts).

type ItemTotals = components['schemas']['ItemTotals']

export type GridChoices = { sort: ItemSort; filter: ItemFilter; genre: string; decade: number }

/** As Android TV opens a library: newest first, nothing narrowed. */
export const DEFAULT_CHOICES: GridChoices = { sort: 'added', filter: '', genre: '', decade: 0 }

/** A See all's choices (a route's, or a row's link), the ones the grid knows; the rest as they open. */
export function choicesFrom(p: { sort?: string; filter?: string; genre?: string; decade?: number }): GridChoices {
  return {
    sort: itemSort(p.sort) ?? DEFAULT_CHOICES.sort,
    filter: itemFilter(p.filter) ?? '',
    genre: typeof p.genre === 'string' ? p.genre : '',
    decade: p.decade && Number.isInteger(p.decade) && p.decade > 0 ? p.decade : 0,
  }
}

/** Whether a filter, genre or decade narrows the grid (Nothing matches offers Clear filters). */
export function narrowed(c: GridChoices): boolean {
  return !!c.filter || !!c.genre || !!c.decade
}

/** The grid's focus group for these choices: another sort or filter is another grid, starting at its first title. */
export function gridGroup(libraryId: number, c: GridChoices): string {
  return `grid/${libraryId}/${c.sort}/${c.filter || 'all'}/${encodeURIComponent(c.genre) || 'any'}/${c.decade || 'any'}`
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

/**
 * The line under the library's name: "214 movies · 2 lists", or with the
 * sort in the header (no rows above the grid) "142 shows · 3,418 episodes ·
 * A to Z". Counts are the library's, whatever the grid is narrowed to.
 */
export function countsLine(type: LibraryType, o: { totals?: ItemTotals; total?: number; lists?: number; sort?: ItemSort }): string {
  const items = o.totals?.items ?? o.total
  return [
    items !== undefined ? count(items, libraryNoun(type)) : '',
    type === 'tv' && o.totals?.episodes ? count(o.totals.episodes, 'episode') : '',
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

/** The line under a grid poster: "2026 · 1 h 58 min", "2019 · 24 episodes". */
export function gridLine(item: Pick<BrowseItem, 'kind' | 'year' | 'runtimeMin' | 'episodeCount' | 'fileCount'>): string | undefined {
  const parts = item.kind === 'movie' ? [item.year ? String(item.year) : '', item.runtimeMin ? formatRuntime(item.runtimeMin) : '']
    : item.kind === 'show' ? [item.year ? String(item.year) : '', item.episodeCount ? count(item.episodeCount, 'episode') : '']
      : [item.fileCount ? count(item.fileCount, 'file') : 'Not matched yet']
  return parts.filter(Boolean).join(' · ') || undefined
}
