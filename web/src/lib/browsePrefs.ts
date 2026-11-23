import { itemFilter, itemSort, type ItemFilter, type ItemSort, type LibraryType } from './browseOptions'
import type { components } from '../api/schema'

type BrowseItem = components['schemas']['BrowseItem']
type ItemTotals = components['schemas']['ItemTotals']
type Library = components['schemas']['Library']

// A library page's choices (tab, filters, sort, view and poster size),
// remembered on this browser for each library, and the words its header and
// rows use. A row's See all brings its own sort and filter in the address,
// which win over what was remembered.

export type BrowseTab = 'overview' | 'all' | 'genres'
export type BrowseView = 'grid' | 'list'

export type BrowseChoices = {
  sort: ItemSort
  filter: ItemFilter
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
  else {
    const filter = itemFilter(o.filter)
    if (filter) out.filter = filter
  }
  if (typeof o.genre === 'string' && o.genre.length <= 64) out.genre = o.genre
  if (typeof o.decade === 'number' && Number.isInteger(o.decade) && o.decade >= 0) out.decade = o.decade
  if (o.tab === 'overview' || o.tab === 'all' || o.tab === 'genres') out.tab = o.tab
  if (o.view === 'grid' || o.view === 'list') out.view = o.view
  if (typeof o.size === 'number' && Number.isInteger(o.size) && o.size >= 1 && o.size <= 5) out.size = o.size
  if (o.columns && typeof o.columns === 'object') {
    const c = o.columns as Record<string, unknown>
    out.columns = { quality: c.quality === true, size: c.size === true }
  }
  return out
}

/**
 * Where the grid starts: the address's sort and filters when a See all
 * brought any (a row's choice for this visit), else what was remembered,
 * else title order with nothing filtered.
 */
export function startingChoices(address: Partial<BrowseChoices>, prefs: BrowsePrefs): BrowseChoices {
  const fromAddress = address.sort !== undefined || !!address.filter || !!address.genre || !!address.decade
  const from = fromAddress ? address : prefs
  return { sort: from.sort ?? 'title', filter: from.filter ?? '', genre: from.genre ?? '', decade: from.decade ?? 0 }
}

/** Whether anything narrows the grid, so Clear filters is offered. Sort doesn't narrow it. */
export function filtersActive(c: Pick<BrowseChoices, 'filter' | 'genre' | 'decade'>): boolean {
  return !!c.filter || !!c.genre || !!c.decade
}

/** The smallest a poster column may be at each size, in pixels; phones start smaller. */
export function posterMin(size: number, phone = false): number {
  const wide = [112, 132, 152, 176, 208]
  const narrow = [88, 96, 104, 120, 140]
  const i = Math.min(5, Math.max(1, Math.round(size))) - 1
  return (phone ? narrow : wide)[i]
}

/** The header's line in words: "236 movies · 58 you haven’t watched", or for TV "48 shows · 1,912 episodes · 23 still airing". */
export function libraryCountsLine(type: LibraryType, totals: Pick<ItemTotals, 'items' | 'episodes' | 'continuing'> | undefined, unwatched: number | undefined): string {
  if (!totals) return ''
  const n = (x: number) => x.toLocaleString()
  if (type === 'movie') {
    const parts = [`${n(totals.items)} ${totals.items === 1 ? 'movie' : 'movies'}`]
    if (unwatched !== undefined && totals.items > 0) parts.push(unwatched === 0 ? 'you’ve watched them all' : `${n(unwatched)} you haven’t watched`)
    return parts.join(' · ')
  }
  const parts = [`${n(totals.items)} ${totals.items === 1 ? 'show' : 'shows'}`]
  if (totals.episodes) parts.push(`${n(totals.episodes)} ${totals.episodes === 1 ? 'episode' : 'episodes'}`)
  if (totals.continuing) parts.push(`${n(totals.continuing)} still airing`)
  return parts.join(' · ')
}

/** The admin's way to files that need a match: "3 files need a match", or nothing. */
export function needsMatchWords(library: Pick<Library, 'unmatchedCount' | 'pendingCount'>): string | undefined {
  const n = library.unmatchedCount + library.pendingCount
  if (n <= 0) return undefined
  return `${n.toLocaleString()} ${n === 1 ? 'file needs' : 'files need'} a match`
}

/** A chip's choices for the Show group: movies by what is left to watch, shows by that and whether they still air. */
export function showChips(type: LibraryType): { value: ItemFilter; label: string }[] {
  return type === 'tv'
    ? [
        { value: '', label: 'All' },
        { value: 'unwatched', label: 'Something left' },
        { value: 'continuing', label: 'Still airing' },
        { value: 'ended', label: 'Ended' },
      ]
    : [
        { value: '', label: 'All' },
        { value: 'unwatched', label: 'Unwatched' },
      ]
}

/** More's picture choices, which the library filters by one at a time. */
export function pictureChoices(type: LibraryType): { value: ItemFilter; label: string }[] {
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
