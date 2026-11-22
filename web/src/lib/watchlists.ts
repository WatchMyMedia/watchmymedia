import type { components } from '../api/schema'
import { inLibraryWords, titlesInLibraryWords, type MediaListEntries, type MediaListEntry } from './mediaLists'

// Watchlists: the movies and shows a person means to
// watch, on lists they name. The words every client shows and the rules
// that decide what a press does. The calls and the cache are
// api/watchlists.ts. No React and nothing of the browser, so the Samsung TV
// app uses it as it is.

export type Watchlist = components['schemas']['Watchlist']
export type WatchlistType = 'movie' | 'tv'
/** A title as the watchlist operations name it: its TMDB id and type. */
export type WatchlistTitle = { mediaType: WatchlistType; tmdbId: number }
export type WatchlistSort = 'added' | 'title' | 'released'

/** The name of the watchlist made on a person's first save. */
export const FIRST_WATCHLIST = 'Watchlist'
/** The most a person may make, until the server says otherwise. */
export const MAX_WATCHLISTS = 25
/** The most titles one holds, until the server says otherwise. */
export const MAX_TITLES = 500
export const MAX_NAME = 60

const titles = (n: number) => `${n.toLocaleString()} ${n === 1 ? 'title' : 'titles'}`

/** A movie's or a show's title for the watchlist operations: a show's is tv there. */
export function watchlistTitle(kind: string, tmdbId: number | undefined): WatchlistTitle | undefined {
  if (!tmdbId) return undefined
  if (kind === 'movie') return { mediaType: 'movie', tmdbId }
  if (kind === 'show' || kind === 'tv') return { mediaType: 'tv', tmdbId }
  return undefined
}

/**
 * The button on a movie's or a show's page: "Watchlist", or which
 * watchlists hold the title ("On Watchlist", "On Halloween", "On 2
 * watchlists"). A watchlist not loaded yet is the first one, Watchlist.
 */
export function watchlistLabel(holding: readonly number[], lists: readonly Pick<Watchlist, 'id' | 'name'>[] | undefined): string {
  if (holding.length === 0) return 'Watchlist'
  if (holding.length > 1) return `On ${holding.length} watchlists`
  return `On ${lists?.find((l) => l.id === holding[0])?.name ?? FIRST_WATCHLIST}`
}

/**
 * What one press does. With two or more watchlists it opens Save to
 * watchlist; with one or none it saves (making Watchlist the first time)
 * or takes the title off.
 */
export function pressAction(count: number | undefined, saved: boolean): 'picker' | 'save' | 'remove' {
  if ((count ?? 0) >= 2) return 'picker'
  return saved ? 'remove' : 'save'
}

/** The one watchlist's name, which the messages and the actions say. */
export function onlyName(lists: readonly Pick<Watchlist, 'name'>[] | undefined): string {
  return lists?.length === 1 ? lists[0].name : FIRST_WATCHLIST
}

/** A title's action where it isn't the page's button: "Add to Watchlist", "Remove from Watchlist", "Save to watchlist…". */
export function titleActionLabel(lists: readonly Pick<Watchlist, 'name'>[] | undefined, saved: boolean): string {
  if ((lists?.length ?? 0) >= 2) return 'Save to watchlist…'
  return saved ? `Remove from ${onlyName(lists)}` : `Add to ${onlyName(lists)}`
}

export function savedMessage(name: string): string {
  return `Saved to ${name}`
}

export function removedMessage(name: string): string {
  return `Removed from ${name}`
}

/** "12 titles · 9 in your library", and "6 titles · 6 in your library" when all are. */
export function watchlistCounts(w: Pick<Watchlist, 'titles' | 'inLibrary'>): string {
  return titlesInLibraryWords(w.titles, w.inLibrary)
}

/** The line under a watchlist's row: "9 of 12 in your library", and none when the library has them all. */
export function watchlistRowLine(inLibrary: number, total: number): string | undefined {
  return inLibrary < total ? inLibraryWords(inLibrary, total) : undefined
}

/**
 * Why a name can't be used, in the picker's words; undefined when it can.
 * An empty name only keeps Create disabled. Names are compared as the
 * server does, whatever the case. self is the watchlist being renamed.
 */
export function nameProblem(name: string, lists: readonly Pick<Watchlist, 'id' | 'name'>[], self?: number): string | undefined {
  const t = name.trim().toLocaleLowerCase()
  if (!t) return undefined
  const same = lists.find((l) => l.id !== self && l.name.trim().toLocaleLowerCase() === t)
  return same ? `You already have a watchlist called ${same.name}.` : undefined
}

/** Whether Create (or Rename's Save) may be pressed: a new name, not too long, not taken. */
export function canUseName(name: string, lists: readonly Pick<Watchlist, 'id' | 'name'>[], self?: Pick<Watchlist, 'id' | 'name'>): boolean {
  const t = name.trim()
  if (!t || t.length > MAX_NAME || t === self?.name) return false
  return !nameProblem(t, lists, self?.id)
}

/**
 * What a refused change says: the server's own words when it refused for a
 * reason people can act on (a full watchlist, a name already used), else
 * the fallback. The Samsung TV app shows no server text, so it uses its own.
 */
export function failureWords(error: unknown, fallback: string): string {
  const e = error as { status?: unknown; message?: unknown } | undefined
  return e?.status === 422 && typeof e.message === 'string' && e.message ? e.message : fallback
}

/** In the picker at the limit, in place of New watchlist…. */
export function limitWords(max = MAX_WATCHLISTS): string {
  return `You have ${max} watchlists, the most you can make.`
}

/**
 * Delete's question. What it reassures about is the library: the titles
 * the library has stay there, so it counts those.
 */
export function deleteWords(w: Pick<Watchlist, 'name' | 'inLibrary'>): { title: string; body: string } {
  const n = w.inLibrary
  const stay = n > 0 ? `Its ${titles(n)} ${n === 1 ? 'stays' : 'stay'} in your library. ` : ''
  return { title: `Delete ${w.name}?`, body: `${stay}This can’t be undone.` }
}

export const switchWords = {
  label: 'Remove titles once watched',
  detail: 'A movie leaves when you finish it. A show leaves when you’ve watched every episode in your library.',
} as const

export const keepsWatchedWords = 'Keeps watched titles'

export const emptyWatchlists = { title: 'No watchlists yet', detail: 'Save a movie or show to watch later. Press Watchlist on its page.' } as const

export const emptyWatchlist = { title: 'Nothing on this watchlist yet', detail: 'Press Watchlist on a movie’s or show’s page to save it here.' } as const

/** A watchlist with titles, none of them of the type chosen. */
export function emptyFilterWords(mediaType: WatchlistType): string {
  return mediaType === 'tv' ? 'No TV shows on this watchlist.' : 'No movies on this watchlist.'
}

export const watchlistSorts: readonly { value: WatchlistSort; label: string }[] = [
  { value: 'added', label: 'Date added' },
  { value: 'title', label: 'Title' },
  { value: 'released', label: 'Release year' },
]

export const watchlistFilters: readonly { value: WatchlistType | undefined; label: string }[] = [
  { value: undefined, label: 'All' },
  { value: 'movie', label: 'Movies' },
  { value: 'tv', label: 'TV shows' },
]

/** A sort from an address, or none for the default (Date added). */
export function watchlistSort(v: unknown): WatchlistSort | undefined {
  return v === 'title' || v === 'released' ? v : undefined
}

/** A type from an address, or none for All. */
export function watchlistType(v: unknown): WatchlistType | undefined {
  return v === 'movie' || v === 'tv' ? v : undefined
}

/** An entry's title for the operations, when it has a TMDB id. */
export function entryTitle(e: Pick<MediaListEntry, 'mediaType' | 'tmdbId'>): WatchlistTitle | undefined {
  return watchlistTitle(e.mediaType, e.tmdbId)
}

/**
 * A page of a watchlist's titles as it shows while some wait for Undo: those
 * are left out and the counts drop, so a refetch in the meantime doesn't
 * bring them back.
 */
export function withoutHeld(page: Pick<MediaListEntries, 'items' | 'total' | 'inLibrary'>, held: (t: WatchlistTitle) => boolean): { items: MediaListEntry[]; total: number; inLibrary: number } {
  const gone = page.items.filter((e) => {
    const t = entryTitle(e)
    return t !== undefined && held(t)
  })
  if (!gone.length) return page
  return {
    items: page.items.filter((e) => !gone.includes(e)),
    total: Math.max(0, page.total - gone.length),
    inLibrary: Math.max(0, page.inLibrary - gone.filter((e) => e.item).length),
  }
}
