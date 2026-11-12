// The library grid's sorts and filters, shared by the grid, its address
// (See all from a row) and a list made from the library (PLAN-ROWS.md §5).

export type LibraryType = 'movie' | 'tv'
export type ItemSort = 'title' | 'added' | 'year' | 'rating' | 'released' | 'runtime' | 'aired'
export type ItemFilter = '' | 'continuing' | 'ended' | '4k' | 'hdr' | 'unwatched'

export function filterOptions(type: LibraryType): { value: ItemFilter; label: string }[] {
  return type === 'tv'
    ? [
        { value: '', label: 'All' },
        { value: 'continuing', label: 'Continuing' },
        { value: 'ended', label: 'Ended' },
        { value: 'unwatched', label: 'Unwatched' },
        { value: '4k', label: '4K' },
      ]
    : [
        { value: '', label: 'All' },
        { value: 'unwatched', label: 'Unwatched' },
        { value: '4k', label: '4K' },
        { value: 'hdr', label: 'HDR' },
      ]
}

export function sortOptions(type: LibraryType): { value: ItemSort; label: string }[] {
  return type === 'tv'
    ? [
        { value: 'title', label: 'Title' },
        { value: 'added', label: 'Date added' },
        { value: 'aired', label: 'Last aired' },
        { value: 'year', label: 'Year' },
        { value: 'rating', label: 'Rating' },
      ]
    : [
        { value: 'title', label: 'Title' },
        { value: 'added', label: 'Date added' },
        { value: 'released', label: 'Release date' },
        { value: 'year', label: 'Year' },
        { value: 'rating', label: 'Rating' },
        { value: 'runtime', label: 'Runtime' },
      ]
}

const sorts = new Set<string>(['title', 'added', 'year', 'rating', 'released', 'runtime', 'aired'])
const filters = new Set<string>(['continuing', 'ended', '4k', 'hdr', 'unwatched'])

/** A sort the grid knows, else undefined. */
export function itemSort(v: unknown): ItemSort | undefined {
  return typeof v === 'string' && sorts.has(v) ? (v as ItemSort) : undefined
}

/** A filter the grid knows, else undefined (All). */
export function itemFilter(v: unknown): Exclude<ItemFilter, ''> | undefined {
  return typeof v === 'string' && filters.has(v) ? (v as Exclude<ItemFilter, ''>) : undefined
}
