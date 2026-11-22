import type { components } from '../../../src/api/schema'
import { libraryScreen } from '../../../src/lib/screenRows'
import { MAX_ROWS, moveRow, setHidden, type RowLayoutEntry } from '../../../src/lib/rowLayout'
import { isWatchlist, titlesInLibraryWords } from '../../../src/lib/mediaLists'

// Settings › Home rows: the screens whose
// rows a person arranges, each row's line, and what its side panel offers.
// Every change saves at once, as on Android TV.

type Library = Pick<components['schemas']['Library'], 'id' | 'type' | 'name'>
type MediaList = Pick<components['schemas']['MediaList'], 'id' | 'entries' | 'inLibrary' | 'source'>

export type RowsScreen = { id: string; name: string; line: string; type?: 'movie' | 'tv' }

/** Home, then each library's Overview, as the Screen panel lists them. */
export function rowScreens(libraries: readonly Library[] | undefined): RowsScreen[] {
  return [
    { id: 'home', name: 'Home', line: 'The rows on Home' },
    ...(libraries ?? []).filter((l) => l.type === 'movie' || l.type === 'tv').map((l): RowsScreen => {
      const tv = l.type === 'tv'
      return { id: libraryScreen(l.id), name: l.name || (tv ? 'TV Shows' : 'Movies'), line: tv ? 'The rows above All TV shows' : 'The rows above All movies', type: tv ? 'tv' : 'movie' }
    }),
  ]
}

/** The screen to show: one chosen by its id, else the first of the route's kind (a library's Edit rows), else Home. */
export function pickScreen(screens: readonly RowsScreen[], chosen: string | undefined, asked: 'home' | 'movie' | 'tv' | undefined): RowsScreen {
  return screens.find((s) => s.id === chosen) ?? (asked && asked !== 'home' ? screens.find((s) => s.type === asked) : undefined) ?? screens[0]
}

function fromLine(kind: string | undefined): string | undefined {
  switch (kind) {
    case 'tmdb': return 'From TMDB'
    case 'mdblist': return 'From MDBList'
    case 'library': return 'From your library'
    case 'import': return 'Imported'
    default: return undefined
  }
}

/**
 * A row's line: "Built in", "For everyone · 18 of 50 · From TMDB", "Your
 * list · 9 of 24 · Imported", and a watchlist's as the apps all say it,
 * "Watchlist · 12 titles · 9 in your library".
 */
export function rowLine(row: Pick<RowLayoutEntry, 'kind' | 'style' | 'owner' | 'listId'>, lists: readonly MediaList[] | undefined): string {
  if (row.kind !== 'list') return row.style === 'live' ? 'Built in · On now, or Watch a channel' : 'Built in'
  const list = lists?.find((l) => l.id === row.listId)
  if (list && isWatchlist(list)) return `Watchlist · ${titlesInLibraryWords(list.entries, list.inLibrary)}`
  const owner = row.owner === 'me' ? 'Your list' : row.owner === 'everyone' ? 'For everyone' : 'List'
  return [owner, list ? `${list.inLibrary} of ${list.entries}` : undefined, fromLine(list?.source.kind)].filter(Boolean).join(' · ')
}

/**
 * Why a row that isn't hidden isn't drawn, under its line, in our words: past
 * the screen's 30 rows, or a list with nothing in the library yet.
 */
export function rowNote(rows: readonly Pick<RowLayoutEntry, 'kind' | 'hidden' | 'shown'>[], index: number): string | undefined {
  const row = rows[index]
  if (!row || row.hidden || row.shown) return undefined
  const before = rows.slice(0, index).filter((r) => !r.hidden && r.shown).length
  if (before >= MAX_ROWS || row.kind !== 'list') return 'Not shown: too many rows'
  return 'Not shown: none of it is in your library yet'
}

export type RowAction = 'up' | 'down' | 'hide' | 'show' | 'cancel'

/** What a row's panel offers: Move up and Move down where it can go, Hide or Show, Cancel. */
export function rowActions(index: number, count: number, hidden: boolean): RowAction[] {
  return [...(index > 0 ? ['up' as const] : []), ...(index < count - 1 ? ['down' as const] : []), hidden ? 'show' : 'hide', 'cancel']
}

export function actionLabel(action: RowAction, screen: string): string {
  return { up: 'Move up', down: 'Move down', hide: `Hide from ${screen}`, show: `Show on ${screen}`, cancel: 'Cancel' }[action]
}

/** The rows after an action, or undefined when it changes nothing. */
export function applyAction(rows: readonly RowLayoutEntry[], id: string, action: RowAction): RowLayoutEntry[] | undefined {
  const index = rows.findIndex((r) => r.id === id)
  if (index < 0 || action === 'cancel') return undefined
  if (action === 'up' || action === 'down') {
    const by = action === 'up' ? -1 : 1
    return index + by < 0 || index + by >= rows.length ? undefined : moveRow(rows, index, by)
  }
  return setHidden(rows, id, action === 'hide')
}

/** "Home’s", "TV Shows’": a screen's name as the owner of its rows. */
export function possessive(name: string): string {
  return name.endsWith('s') ? `${name}’` : `${name}’s`
}
