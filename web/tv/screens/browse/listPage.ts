import { relativeTime } from '../../../src/lib/format'
import { entryView, inLibraryWords, isWatchlist, listMeta, retryWords, type MediaList, type MediaListEntry } from '../../../src/lib/mediaLists'
import { entryTitle } from '../../../src/lib/watchlists'
import type { RequestTarget } from '../requests/RequestDialog'

// A list's page on the TV, a watchlist's among them: its header's words
// and what OK and Hold OK do on each title. The rules for which titles are
// in the library, missing or not found are the web's (mediaLists.ts).

/** "List · For everyone", "Your list", "Watchlist". */
export function listKicker(list: Pick<MediaList, 'owner' | 'source'>): string {
  if (isWatchlist(list)) return 'Watchlist'
  return list.owner === 'me' ? 'Your list' : list.owner === 'everyone' ? 'List · For everyone' : 'List'
}

/** "From TMDB · Updated 3 h ago", "Imported Nov 2, 2026 · One title a line"; nothing for a watchlist, which isn't fetched. */
export function listLine(list: Parameters<typeof listMeta>[0]): string {
  const meta = listMeta(list)
  return [meta.from, meta.updated].filter(Boolean).join(' · ')
}

/** "18 of 50 in your library", "9 of 24 in your library · 2 not found". */
export function listCount(inLibrary: number, total: number, notFound = 0): string {
  return [inLibraryWords(inLibrary, total), notFound ? `${notFound.toLocaleString()} not found` : ''].filter(Boolean).join(' · ')
}

/**
 * Why the list is as it was, when its last fetch failed: when it last worked
 * and when it tries again. The server's reason is its own text, which this
 * TV never shows.
 */
export function failedWords(list: Pick<MediaList, 'error' | 'errorAt' | 'fetchedAt' | 'nextFetchAt'>, now = Date.now()): string | undefined {
  if (!list.error) return undefined
  const when = list.errorAt ? ` ${relativeTime(list.errorAt)}` : ''
  const was = list.fetchedAt ? ` It’s shown as it was ${relativeTime(list.fetchedAt)}.` : ''
  return [`Couldn’t update this list${when}.${was}`, retryWords(list.nextFetchAt, now)].filter(Boolean).join(' ')
}

export type EntryCard =
  | { kind: 'library'; hint: string; hold?: string }
  | { kind: 'missing'; status: string; request?: RequestTarget; hint: string; hold?: string }
  | { kind: 'notFound'; hint: string }

/**
 * What a title on the list is and what OK does with it: open it, ask for
 * it, or nothing. With `menu` (the server keeps watchlists), Hold OK on a
 * title TMDB has matched opens its actions.
 */
export function entryCard(e: MediaListEntry, menu = false): EntryCard {
  const view = entryView(e)
  const actions = menu && entryTitle(e) ? 'Its menu' : undefined
  if (view.kind === 'library') {
    const movie = e.item?.kind === 'movie'
    return { kind: 'library', hint: movie ? 'Open the movie' : 'Open the show', hold: actions ?? (movie ? (e.item?.watched ? 'Mark unwatched' : 'Mark watched') : undefined) }
  }
  if (view.kind === 'notFound') return { kind: 'notFound', hint: 'Nothing to request' }
  const request: RequestTarget | undefined = view.request && e.tmdbId ? { kind: e.mediaType === 'tv' ? 'tv' : 'movie', tmdbId: e.tmdbId, title: e.title } : undefined
  return { kind: 'missing', status: view.label, request, hint: request ? 'Request it' : 'Nothing to request', hold: actions }
}

/** "1988 · Not in your library"; on a watchlist's narrower page only "Not in your library", where the year would be. */
export function missingLine(e: Pick<MediaListEntry, 'year'>, watchlist = false): string {
  return [watchlist || !e.year ? '' : String(e.year), 'Not in your library'].filter(Boolean).join(' · ')
}

/** "3. Halfway to Juniper, not in your library, OK to request"; a watchlist's titles have no place to say. */
export function entryLabel(e: MediaListEntry, card: EntryCard, ranked = true): string {
  const title = e.item?.title ?? e.title
  const what = card.kind === 'library' ? (e.item?.watched ? 'watched' : '')
    : card.kind === 'notFound' ? 'not found on TMDB'
      : card.request ? 'not in your library, OK to request' : card.status.toLowerCase()
  return [ranked ? `${e.rank}. ${title}` : title, what].filter(Boolean).join(', ')
}
