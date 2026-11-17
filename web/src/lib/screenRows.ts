import type { components } from '../api/schema'
import { relativeTime } from './format'
import { itemFilter, itemSort, sortOptions } from './browseOptions'
import type { BrowseSearch } from './routeSearch'

// Home and a library's Overview drawn from the server's rows, in the
// person's order (PLAN-ROWS.md §6.1). Styles, link kinds and source kinds
// are plain strings: what the web doesn't know, it skips.

export type ScreenRow = components['schemas']['ScreenRow']
export type ScreenRowSource = components['schemas']['ScreenRowSource']
type BrowseItem = components['schemas']['BrowseItem']
type HomeData = components['schemas']['HomeData']
type LibraryRows = components['schemas']['LibraryRows']
type RelatedRows = components['schemas']['RelatedRows']
type LibraryRef = { id: number; type: string }

/** The screen key the layout routes take: home, or library-<id>. */
export function libraryScreen(libraryId: number): string {
  return `library-${libraryId}`
}

/** The library a screen key names, or undefined for Home. */
export function screenLibraryId(screen: string): number | undefined {
  const m = /^library-(\d+)$/.exec(screen)
  return m ? Number(m[1]) : undefined
}

/**
 * Whether the web draws a row: a style it knows with something in it.
 * live has no items and the web has no Live TV row yet, so it draws nothing.
 */
export function drawable(row: ScreenRow): boolean {
  switch (row.style) {
    case 'poster':
    case 'wide':
      return !!row.items?.length
    case 'episode':
      return !!row.episodes?.length
    case 'continue':
      return !!row.continue?.length
    default:
      return false
  }
}

/**
 * Home's rows. An arranged reply sends rows (or none, when every row is
 * hidden) and empty fixed arrays; an unarranged one sends only the fixed
 * arrays, which become the built-in rows in today's order, each linking to
 * its library sorted the row's way, as an arranged row's link does.
 */
export function homeRows(h: HomeData): ScreenRow[] {
  if (h.rows) return h.rows
  const link = (libraryId: number | undefined, sort: string) => (libraryId ? { kind: 'library', libraryId, sort } : undefined)
  return [
    { id: 'continue', style: 'continue', title: 'Continue watching', continue: h.continueWatching },
    { id: 'recentlyAddedShows', style: 'poster', title: 'Recently added · TV Shows', items: h.recentlyAddedShows, seeAll: link(h.tvLibraryId, 'added') },
    { id: 'recentlyAired', style: 'episode', title: 'Recently aired · Episodes', episodes: h.recentlyAired, seeAll: link(h.tvLibraryId, 'aired') },
    { id: 'recentlyAddedMovies', style: 'poster', title: 'Recently added · Movies', items: h.recentlyAddedMovies, seeAll: link(h.movieLibraryId, 'added') },
    { id: 'recentlyReleased', style: 'poster', title: 'Recently released · Movies', items: h.recentlyReleased, seeAll: link(h.movieLibraryId, 'released') },
  ]
}

/** A library's Overview rows, from an arranged reply or the fixed arrays. */
export function libraryRows(r: LibraryRows, type: 'movie' | 'tv'): ScreenRow[] {
  if (r.rows) return r.rows
  return type === 'tv'
    ? [
        { id: 'recentlyAdded', style: 'poster', title: 'Recently added · newest episodes per show', items: r.recentlyAdded },
        { id: 'recentlyAired', style: 'episode', title: 'Recently aired · episodes of shows you have', episodes: r.recentlyAired },
      ]
    : [
        { id: 'recentlyAdded', style: 'poster', title: 'Recently added · newest files', items: r.recentlyAdded },
        { id: 'recentlyReleased', style: 'wide', title: 'Recently released · by release date', items: r.recentlyReleased },
      ]
}

/**
 * The rows a movie's or a show's page ends with (PLAN-RELATED.md §7): the
 * collection, then More like this, as the server sends them, those the web
 * can draw. None before the reply arrives or when it failed, and never a
 * See all (§3.7), so the page ends as it did.
 */
export function relatedRows(reply: RelatedRows | undefined): ScreenRow[] {
  return (reply?.rows ?? []).filter(drawable).map((row) => ({ ...row, seeAll: undefined }))
}

export function isListRow(row: Pick<ScreenRow, 'id'>): boolean {
  return row.id.startsWith('list:')
}

/**
 * A row's heading and its muted part: built-in rows' titles split at the
 * first " · " ("Recently added", "Movies"), as the rows have always looked.
 * A list's name and a movie's collection's are their own and never split.
 */
export function titleParts(row: Pick<ScreenRow, 'id' | 'title'>): [string, string | undefined] {
  if (isListRow(row) || row.id === 'collection') return [row.title, undefined]
  const at = row.title.indexOf(' · ')
  return at > 0 ? [row.title.slice(0, at), row.title.slice(at + 3)] : [row.title, undefined]
}

/**
 * The plain end of the line under a poster in a built-in row: when it was
 * added, or released. A Recently added show card's "S03E07" or "Season 1"
 * comes before it as links (`cardLinks.ts`, PLAN-HOME.md §6.1).
 */
export function posterSubtitle(row: Pick<ScreenRow, 'id'>, item: BrowseItem): string | undefined {
  const added = row.id === 'recentlyAddedShows' || row.id === 'recentlyAddedMovies' || row.id === 'recentlyAdded'
  if (added) return `added ${relativeTime(item.addedAt)}`
  if (row.id === 'recentlyReleased') return item.releaseDate ?? ''
  return undefined
}

/** Only web links leave the app: a source's URL that is anything else is not drawn. */
export function webLink(url: string | undefined): string | undefined {
  if (!url) return undefined
  try {
    const u = new URL(url)
    return u.protocol === 'https:' || u.protocol === 'http:' ? u.toString() : undefined
  } catch {
    return undefined
  }
}

export type Segment = { text: string; href?: string }

/**
 * A list row's line: the server's subtitle ("18 of 50 · From TMDB") with
 * "From <name>" linking to the list on the source's site. When the
 * subtitle doesn't name the source, the attribution follows it.
 */
export function attribution(subtitle: string | undefined, source: ScreenRowSource | undefined): Segment[] {
  const from = source?.name ? `From ${source.name}` : ''
  const href = webLink(source?.url)
  if (!subtitle) return from ? [{ text: from, href }] : []
  const at = from ? subtitle.indexOf(from) : -1
  if (at < 0) return from ? [{ text: subtitle }, { text: ' · ' }, { text: from, href }] : [{ text: subtitle }]
  const out: Segment[] = []
  if (at > 0) out.push({ text: subtitle.slice(0, at) })
  out.push({ text: from, href })
  const rest = subtitle.slice(at + from.length)
  if (rest) out.push({ text: rest })
  return out
}

export type SeeAllTarget = { kind: 'library'; type: 'movie' | 'tv'; search: BrowseSearch } | { kind: 'list'; listId: number }

/**
 * Where a row's See all goes: a library's grid with the link's sort and
 * filter (its Overview when the link sets none), or a list's page. None for
 * a kind the web doesn't know or a library it can't find.
 */
export function seeAllTarget(row: Pick<ScreenRow, 'seeAll' | 'listId' | 'items'>, libraries: readonly LibraryRef[]): SeeAllTarget | undefined {
  const link = row.seeAll
  if (!link) return undefined
  if (link.kind === 'list') {
    const listId = link.listId ?? row.listId
    return listId ? { kind: 'list', listId } : undefined
  }
  if (link.kind !== 'library') return undefined
  const libraryId = link.libraryId ?? row.items?.find((i) => i.libraryId)?.libraryId
  const lib = libraries.find((l) => l.id === libraryId)
  if (!lib || (lib.type !== 'movie' && lib.type !== 'tv')) return undefined
  const type = lib.type
  const first = libraries.find((l) => l.type === type)
  const options = { sort: itemSort(link.sort), filter: itemFilter(link.filter), genre: link.genre || undefined, decade: link.decade || undefined }
  const any = Object.values(options).some((v) => v !== undefined)
  return {
    kind: 'library',
    type,
    search: { ...options, view: any ? 'all' : undefined, library: first && first.id !== lib.id ? lib.id : undefined },
  }
}

export type SeeAllWords = { title: string; detail?: string; label: string }

/**
 * What a row's See all says (PLAN-SEE-ALL.md §4.4), on its card and, as
 * `label`, to a screen reader from the card and the heading's link alike. A
 * library's grid is "See all TV shows" over "By last aired", the sort in the
 * grid's own words so the two can't drift, and no second line without a
 * sort; a list is "See all" over "With the ones you don’t have".
 */
export function seeAllWords(target: SeeAllTarget, rowTitle: string): SeeAllWords {
  if (target.kind === 'list') return { title: 'See all', detail: 'With the ones you don’t have', label: `See all of ${rowTitle}` }
  const title = target.type === 'tv' ? 'See all TV shows' : 'See all movies'
  const sort = sortOptions(target.type).find((o) => o.value === target.search.sort)?.label
  if (!sort) return { title, label: title }
  const by = sort.charAt(0).toLowerCase() + sort.slice(1)
  return { title, detail: `By ${by}`, label: `${title}, by ${by}` }
}

export type ScreenTarget = { to: '/' } | { to: '/movies' | '/tv'; library?: number }

/** Where a screen is in the app: Home, or the library's page (naming it when it isn't the first of its type). */
export function screenTarget(screen: string, libraries: readonly LibraryRef[]): ScreenTarget {
  const id = screenLibraryId(screen)
  const lib = libraries.find((l) => l.id === id)
  if (!lib || (lib.type !== 'movie' && lib.type !== 'tv')) return { to: '/' }
  const first = libraries.find((l) => l.type === lib.type)
  return { to: lib.type === 'tv' ? '/tv' : '/movies', library: first && first.id !== lib.id ? lib.id : undefined }
}
