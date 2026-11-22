import { keepsWatchedWords, limitWords, MAX_TITLES, nameProblem, titleActionLabel, watchlistCounts, type Watchlist } from '../../../src/lib/watchlists'
import { problemWords, statusOf } from '../../ui/problemWords'

// Watchlists on the TV: when the app offers them, what a card's actions
// hold, the Watchlists screen's tiles, and the TV's own words. The words the
// apps share, the calls and what each changes in the cache are the web's
// (src/lib/watchlists.ts, src/api/watchlists.ts). A TV shows no message
// after saving, as the label changing says it, and never the server's text.

/** Servers from API level 33 keep watchlists. Below it nothing here is offered; their rows still come, as lists. */
export const WATCHLISTS_API_LEVEL = 33

export function watchlistsOn(apiLevel: number | undefined): boolean {
  return (apiLevel ?? 0) >= WATCHLISTS_API_LEVEL
}

/** What OK on the title page's Watchlist button does, for the line under the buttons. */
export function buttonHint(picker: boolean, saved: boolean, name: string): string {
  if (picker) return 'opens Save to watchlist'
  return saved ? `removes it from your ${name}` : `saves it to your ${name}`
}

export type CardMenuItem = { kind: 'open' | 'watched' | 'request' | 'watchlist'; label: string }

export type CardMenuOf = {
  kind: 'movie' | 'show'
  year?: number
  /** OK's words for a title in the library ("Open the movie"); none for one the library lacks. */
  open?: string
  /** A movie in the library: whether it is watched, for Mark watched or Mark unwatched. */
  watched?: boolean
  /** A title the library lacks that the person may ask for. */
  request?: boolean
  /** The person's watchlists, and whether one holds the title (a card's `watchlisted`). */
  lists?: readonly Pick<Watchlist, 'name'>[]
  saved: boolean
  /** On a watchlist's own page: that watchlist, which every title there is on. */
  page?: Pick<Watchlist, 'name'>
}

/**
 * A card's actions, from Hold OK: its kicker ("Movie · 2026") and what it
 * offers, the watchlist item last. On a watchlist's page that item takes
 * the title off it; elsewhere it adds, removes, or opens Save to watchlist.
 */
export function cardMenu(m: CardMenuOf): { kicker: string; items: CardMenuItem[] } {
  const kicker = [m.kind === 'movie' ? 'Movie' : 'TV show', m.year ? String(m.year) : '', m.open ? '' : 'Not in your library'].filter(Boolean).join(' · ')
  const items: CardMenuItem[] = []
  if (m.open) items.push({ kind: 'open', label: m.open })
  if (m.open && m.kind === 'movie' && m.watched !== undefined) items.push({ kind: 'watched', label: m.watched ? 'Mark unwatched' : 'Mark watched' })
  if (!m.open && m.request) items.push({ kind: 'request', label: 'Request' })
  items.push({ kind: 'watchlist', label: m.page ? titleActionLabel([m.page], true) : titleActionLabel(m.lists, m.saved) })
  return { kicker, items }
}

/** A tile on the Watchlists screen: its counts, whether it keeps watched titles, and all of it for a screen reader. */
export function tileWords(w: Pick<Watchlist, 'name' | 'titles' | 'inLibrary' | 'removeWatched'>): { count: string; keeps?: string; label: string } {
  const count = watchlistCounts(w)
  const keeps = w.removeWatched ? undefined : keepsWatchedWords
  return { count, keeps, label: [w.name, count.replace(' · ', ', '), keeps?.toLowerCase()].filter(Boolean).join(', ') }
}

/** "3 watchlists" under the Watchlists screen's title; nothing with none. */
export function watchlistsLine(n: number): string | undefined {
  return n > 0 ? `${n} ${n === 1 ? 'watchlist' : 'watchlists'}` : undefined
}

/** A save or a removal that didn't happen. A save the server refused is a full watchlist. */
export function changeFailed(error: unknown, saving: boolean, maxTitles = MAX_TITLES): string {
  if (saving && statusOf(error) === 422) return `A watchlist holds up to ${maxTitles} titles.`
  return `${saving ? 'Couldn’t save it' : 'Couldn’t take it off'}. ${problemWords(error).title}.`
}

/**
 * A name the server refused (New watchlist, Rename): another of the
 * person's watchlists has it, they have as many as they may make, or
 * neither that this TV knows of.
 */
export function nameFailed(error: unknown, name: string, lists: readonly Pick<Watchlist, 'id' | 'name'>[], max?: number, self?: number): string {
  if (statusOf(error) !== 422) return `Couldn’t save the name. ${problemWords(error).title}.`
  return nameProblem(name, lists, self) ?? (max !== undefined && lists.length >= max ? limitWords(max) : 'That name can’t be used. Try another.')
}
