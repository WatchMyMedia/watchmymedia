import { itemSort, type ItemFilter, type ItemFilterValue, type ItemSort, type LibraryType } from './browseOptions'
import type { components } from '../api/schema'

type BrowseItem = components['schemas']['BrowseItem']
type ItemTotals = components['schemas']['ItemTotals']
type Library = components['schemas']['Library']

// A library page's choices (tab, filters, sort, view and poster size),
// remembered on this browser for each library, and the words its header and
// rows use. A row's See all brings its own sort and filter in the address,
// which win over what was remembered.

export type BrowseTab = 'overview' | 'all' | 'genres' | 'collections'
export type BrowseView = 'grid' | 'list'

/** The Show chips: what is left to watch, and for shows whether they air and whether the person follows them. */
export type ChipFilter = '' | 'unwatched' | 'inProgress' | 'following' | 'continuing' | 'ended'
/** More's picture choice, which combines with a chip (API level 34). */
export type PictureFilter = '' | '4k' | 'hdr'

export type BrowseChoices = {
  sort: ItemSort
  filter: ChipFilter
  picture: PictureFilter
  /** A content rating as the library's facets list it ("PG-13", "unrated"), or "" for any. */
  rated: string
  genre: string
  decade: number
}

export type BrowsePrefs = Partial<BrowseChoices> & {
  tab?: BrowseTab
  view?: BrowseView
  /** Poster size, 1 (smallest) to 5. */
  size?: number
  /** List view's extra columns. */
  columns?: { quality?: boolean; size?: boolean }
}

export const POSTER_SIZES = [1, 2, 3, 4, 5] as const
export const DEFAULT_SIZE = 3

/** The browser storage key for a library's choices. */
export function prefsKey(libraryId: number): string {
  return `wmm.library.${libraryId}`
}

/** Stored choices read back; anything unknown or malformed is left out. */
export function parsePrefs(raw: string | null): BrowsePrefs {
  if (!raw) return {}
  let v: unknown
  try {
    v = JSON.parse(raw)
  } catch {
    return {}
  }
  if (!v || typeof v !== 'object') return {}
  const o = v as Record<string, unknown>
  const out: BrowsePrefs = {}
  const sort = itemSort(o.sort)
  if (sort) out.sort = sort
  if (o.filter === '') out.filter = ''
  else if (isChip(o.filter)) out.filter = o.filter
  // A picture filter was kept as the filter before chips and pictures combined.
  else if (isPicture(o.filter) && o.filter) out.picture = o.filter
  if (o.picture === '' || isPicture(o.picture)) out.picture = o.picture as PictureFilter
  if (typeof o.rated === 'string' && o.rated.length <= 32) out.rated = o.rated
  if (typeof o.genre === 'string' && o.genre.length <= 64) out.genre = o.genre
  if (typeof o.decade === 'number' && Number.isInteger(o.decade) && o.decade >= 0) out.decade = o.decade
  if (o.tab === 'overview' || o.tab === 'all' || o.tab === 'genres' || o.tab === 'collections') out.tab = o.tab
  if (o.view === 'grid' || o.view === 'list') out.view = o.view
  if (typeof o.size === 'number' && Number.isInteger(o.size) && o.size >= 1 && o.size <= 5) out.size = o.size
  if (o.columns && typeof o.columns === 'object') {
    const c = o.columns as Record<string, unknown>
    out.columns = { quality: c.quality === true, size: c.size === true }
  }
  return out
}

const chips = new Set<string>(['unwatched', 'inProgress', 'following', 'continuing', 'ended'])
const pictures = new Set<string>(['', '4k', 'hdr'])
function isChip(v: unknown): v is Exclude<ChipFilter, ''> {
  return typeof v === 'string' && chips.has(v)
}
function isPicture(v: unknown): v is PictureFilter {
  return typeof v === 'string' && pictures.has(v)
}

/** The address's filter, from a See all: a chip, or a picture. */
export function splitFilter(filter: ItemFilter | undefined): { filter: ChipFilter; picture: PictureFilter } {
  if (filter === '4k' || filter === 'hdr') return { filter: '', picture: filter }
  return { filter: isChip(filter) ? filter : '', picture: '' }
}

/**
 * Where the grid starts: the address's sort and filters when a See all
 * brought any (a row's choice for this visit), else what was remembered,
 * else title order with nothing filtered.
 */
export function startingChoices(address: { sort?: ItemSort; filter?: ItemFilter; genre?: string; decade?: number }, prefs: BrowsePrefs): BrowseChoices {
  const fromAddress = address.sort !== undefined || !!address.filter || !!address.genre || !!address.decade
  if (fromAddress) return { sort: address.sort ?? 'title', ...splitFilter(address.filter), rated: '', genre: address.genre ?? '', decade: address.decade ?? 0 }
  return { sort: prefs.sort ?? 'title', filter: prefs.filter ?? '', picture: prefs.picture ?? '', rated: prefs.rated ?? '', genre: prefs.genre ?? '', decade: prefs.decade ?? 0 }
}

/** Whether anything narrows the grid, so Clear filters is offered. Sort doesn't narrow it. */
export function filtersActive(c: Pick<BrowseChoices, 'filter' | 'picture' | 'rated' | 'genre' | 'decade'>): boolean {
  return !!c.filter || !!c.picture || !!c.rated || !!c.genre || !!c.decade
}

/** Nothing narrowing the grid; the sort stays. */
export const NO_FILTERS = { filter: '', picture: '', rated: '', genre: '', decade: 0 } as const satisfies Partial<BrowseChoices>

/** The chip and the picture as the server's combined filters: both must hold. */
export function serverFilters(c: Pick<BrowseChoices, 'filter' | 'picture'>): ItemFilterValue[] {
  return [c.filter, c.picture].filter((f): f is ItemFilterValue => !!f)
}

/** The smallest a poster column may be at each size, in pixels; phones start smaller. */
export function posterMin(size: number, phone = false): number {
  const wide = [112, 132, 152, 176, 208]
  const narrow = [88, 96, 104, 120, 140]
  const i = Math.min(5, Math.max(1, Math.round(size))) - 1
  return (phone ? narrow : wide)[i]
}

/**
 * The header's line in words: "236 movies · 58 you haven’t watched · 12
 * added this month", or for TV "48 shows · 1,912 episodes · 23 still airing ·
 * 4 with new episodes for you". unwatched is for replies without the totals'
 * own count.
 */
export function libraryCountsLine(
  type: LibraryType,
  totals: Pick<ItemTotals, 'items' | 'episodes' | 'continuing' | 'unwatched' | 'addedThisMonth' | 'newEpisodes'> | undefined,
  unwatched?: number,
): string {
  if (!totals) return ''
  const n = (x: number) => x.toLocaleString()
  const left = totals.unwatched ?? unwatched
  if (type === 'movie') {
    const parts = [`${n(totals.items)} ${totals.items === 1 ? 'movie' : 'movies'}`]
    if (left !== undefined && totals.items > 0) parts.push(left === 0 ? 'you’ve watched them all' : `${n(left)} you haven’t watched`)
    if (totals.addedThisMonth) parts.push(`${n(totals.addedThisMonth)} added this month`)
    return parts.join(' · ')
  }
  const parts = [`${n(totals.items)} ${totals.items === 1 ? 'show' : 'shows'}`]
  if (totals.episodes) parts.push(`${n(totals.episodes)} ${totals.episodes === 1 ? 'episode' : 'episodes'}`)
  if (totals.continuing) parts.push(`${n(totals.continuing)} still airing`)
  if (totals.newEpisodes) parts.push(`${n(totals.newEpisodes)} with new episodes for you`)
  return parts.join(' · ')
}

/** The admin's way to files that need a match: "3 files need a match", or nothing. */
export function needsMatchWords(library: Pick<Library, 'unmatchedCount' | 'pendingCount'>): string | undefined {
  const n = library.unmatchedCount + library.pendingCount
  if (n <= 0) return undefined
  return `${n.toLocaleString()} ${n === 1 ? 'file needs' : 'files need'} a match`
}

/** A chip's choices for the Show group: movies by what is left to watch, shows by that, whether they still air and whether the person follows them. */
export function showChips(type: LibraryType): { value: ChipFilter; label: string }[] {
  return type === 'tv'
    ? [
        { value: '', label: 'All' },
        { value: 'unwatched', label: 'Something left' },
        { value: 'following', label: 'Following' },
        { value: 'continuing', label: 'Still airing' },
        { value: 'ended', label: 'Ended' },
      ]
    : [
        { value: '', label: 'All' },
        { value: 'unwatched', label: 'Unwatched' },
        { value: 'inProgress', label: 'In progress' },
      ]
}

/** Each chip's count, from the library's totals; a chip whose count the reply lacks shows none. */
export function chipCounts(
  type: LibraryType,
  totals: Pick<ItemTotals, 'items' | 'continuing' | 'unwatched' | 'inProgress' | 'following'> | undefined,
): Partial<Record<ChipFilter, number>> {
  if (!totals) return {}
  if (type === 'movie') return { unwatched: totals.unwatched, inProgress: totals.inProgress }
  return { unwatched: totals.unwatched, following: totals.following, continuing: totals.continuing, ended: Math.max(0, totals.items - totals.continuing) }
}

/** Rated's choices, in the server's order (US ratings, unrated last), each with how many titles have it. */
export function ratedChoices(ratings: readonly string[] | undefined, counts: Record<string, number> | undefined): { value: string; label: string }[] {
  return [
    { value: '', label: 'Any' },
    ...(ratings ?? []).map((r) => {
      const name = r === 'unrated' ? 'Not rated' : r
      const n = counts?.[r]
      return { value: r, label: n !== undefined ? `${name} (${n.toLocaleString()})` : name }
    }),
  ]
}

/** More's picture choices. */
export function pictureChoices(type: LibraryType): { value: PictureFilter; label: string }[] {
  return type === 'tv' ? [{ value: '4k', label: '4K' }] : [{ value: '4k', label: '4K' }, { value: 'hdr', label: 'HDR' }]
}

/** What a list row says about this person and the title: "Watched", "52 min left", "New", or nothing. */
export function youWords(item: Pick<BrowseItem, 'kind' | 'watched' | 'progress' | 'runtimeMin'> & { isNew?: boolean }): { text: string; gold: boolean } | undefined {
  if (item.watched) return { text: item.kind === 'show' ? 'All watched' : 'Watched', gold: false }
  if (item.kind === 'movie' && item.progress && item.progress > 0) {
    if (item.runtimeMin) {
      const left = Math.max(1, Math.round(item.runtimeMin * (1 - item.progress)))
      const h = Math.floor(left / 60)
      const m = left % 60
      return { text: `${h ? `${h} h${m ? ` ${m} min` : ''}` : `${m} min`} left`, gold: true }
    }
    return { text: `${Math.round(item.progress * 100)}% watched`, gold: true }
  }
  if (item.isNew) return { text: 'New', gold: true }
  return undefined
}

/** A show card's first line: how many episodes the library has. */
export function episodesWords(count: number | undefined): string {
  if (!count) return ''
  return `${count.toLocaleString()} ${count === 1 ? 'episode' : 'episodes'}`
}

/** What is left of a show for this person: "12 episodes", "All watched", "Not started"; nothing without the counts. */
export function leftToWatch(item: Pick<BrowseItem, 'unwatchedCount' | 'episodeCount' | 'watched' | 'progress'>): { text: string; left: boolean } | undefined {
  const left = item.unwatchedCount
  if (left === undefined) return item.watched ? { text: 'All watched', left: false } : undefined
  if (left === 0) return { text: 'All watched', left: false }
  if (item.episodeCount !== undefined && left >= item.episodeCount && !item.progress) return { text: 'Not started', left: false }
  return { text: episodesWords(left), left: true }
}

/** A show card's first line: "3 seasons · 2 left", "1 season · all watched", "2 seasons · not started"; the episode count without the seasons. */
export function showCardLine(item: Pick<BrowseItem, 'seasonCount' | 'unwatchedCount' | 'episodeCount' | 'watched' | 'progress' | 'year'>): string {
  const seasons = item.seasonCount ? `${item.seasonCount.toLocaleString()} ${item.seasonCount === 1 ? 'season' : 'seasons'}` : ''
  const where = leftToWatch(item)
  const tail = where ? (where.left ? `${item.unwatchedCount?.toLocaleString()} left` : where.text.toLowerCase()) : ''
  if (seasons) return [seasons, tail].filter(Boolean).join(' · ')
  return episodesWords(item.episodeCount) || (item.year ? String(item.year) : '')
}

/** The picture's flag: "2 new" for a show with new episodes the person hasn't watched, else New. */
export function newFlag(item: Pick<BrowseItem, 'isNew' | 'newEpisodeCount' | 'kind'>): string | undefined {
  if (item.kind === 'show' && item.newEpisodeCount && item.newEpisodeCount > 0) return `${item.newEpisodeCount.toLocaleString()} new`
  return item.isNew ? 'New' : undefined
}

/** When a title was added, as a list row says it: "Today", "Yesterday", "Nov 20", or "Dec 2025" in another year. */
export function addedWords(iso: string | undefined, now = Date.now()): string {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  const day = (t: Date) => new Date(t.getFullYear(), t.getMonth(), t.getDate()).getTime()
  const today = new Date(now)
  const days = Math.round((day(today) - day(d)) / 86_400_000)
  if (days <= 0) return 'Today'
  if (days === 1) return 'Yesterday'
  if (d.getFullYear() === today.getFullYear()) return d.toLocaleDateString(undefined, { month: 'short', day: 'numeric' })
  return d.toLocaleDateString(undefined, { month: 'short', year: 'numeric' })
}
