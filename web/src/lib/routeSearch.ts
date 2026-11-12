import { itemFilter, itemSort, type ItemFilter, type ItemSort } from './browseOptions'

/**
 * A season address's search, kept so older links still land: ?episode=
 * named the episode a season opened in place before episodes had pages of
 * their own, ?at= the one a Home card outlined in the season's list
 * (PLAN-HOME.md §6.2). Both now redirect to the episode's address
 * (PLAN-SHOW-PAGE.md §2.1).
 */
export type SeasonSearch = { episode?: number; at?: number }

export function validateSeasonSearch(s: Record<string, unknown>): SeasonSearch {
  return { episode: episodeId(s.episode), at: episodeId(s.at) }
}

const episodeId = (v: unknown) => {
  const n = typeof v === 'number' ? v : typeof v === 'string' && v ? Number(v) : undefined
  return n !== undefined && Number.isInteger(n) && n > 0 ? n : undefined
}

/** Where a season link with ?episode= or ?at= goes now: that episode's address, so links from before keep working. */
export function episodeRedirect(params: { showId: string; number: string }, search: SeasonSearch) {
  const id = search.episode ?? search.at
  if (id === undefined) return null
  return { to: '/shows/$showId/seasons/$number/episodes/$episodeId' as const, params: { showId: params.showId, number: params.number, episodeId: String(id) } }
}

/**
 * The player's search: where to start (seconds; none resumes, 0 is the
 * beginning), its title and where Back goes. trailer marks a movie's trailer
 * file, which goes back when it ends.
 */
export type PlaySearch = { start?: number; title?: string; back?: string; trailer?: true }

export function validatePlaySearch(s: Record<string, unknown>): PlaySearch {
  return {
    start: typeof s.start === 'number' ? s.start : typeof s.start === 'string' && s.start ? Number(s.start) : undefined,
    title: typeof s.title === 'string' ? s.title : undefined,
    // A path on this site, query included: the player navigates to it as an href.
    back: typeof s.back === 'string' && /^\/(?![/\\])/.test(s.back) ? s.back : undefined,
    trailer: s.trailer === true || s.trailer === 'true' ? true : undefined,
  }
}

/** The guide's search: a programme to open it at, from the Search page's On Live TV (PLAN-LIVE-TV-GUIDE.md §6.5). */
export type GuideSearch = { channel?: string; programme?: string; at?: string }

export function validateGuideSearch(s: Record<string, unknown>): GuideSearch {
  const text = (v: unknown) => (typeof v === 'string' && v && v.length <= 128 ? v : undefined)
  const at = text(s.at)
  return { channel: text(s.channel), programme: text(s.programme), at: at && !Number.isNaN(Date.parse(at)) ? at : undefined }
}

/**
 * The Movies and TV pages' search: a search term, the view, and the grid's
 * options when a row's See all opens it (PLAN-ROWS.md §6.1). library names
 * a library other than the first of its type.
 */
export type BrowseSearch = EditSearch & {
  q?: string
  view?: 'unmatched' | 'all'
  sort?: ItemSort
  filter?: Exclude<ItemFilter, ''>
  genre?: string
  decade?: number
  library?: number
}

const positive = (v: unknown) => {
  const n = typeof v === 'number' ? v : typeof v === 'string' && v ? Number(v) : NaN
  return Number.isInteger(n) && n > 0 ? n : undefined
}

export function validateBrowseSearch(s: Record<string, unknown>): BrowseSearch {
  return {
    q: typeof s.q === 'string' && s.q ? s.q : undefined,
    view: s.view === 'unmatched' || s.view === 'all' ? s.view : undefined,
    sort: itemSort(s.sort),
    filter: itemFilter(s.filter),
    genre: typeof s.genre === 'string' && s.genre && s.genre.length <= 64 ? s.genre : undefined,
    decade: positive(s.decade),
    library: positive(s.library),
    ...validateEditSearch(s),
  }
}

/**
 * Edit rows in a screen's address, so Make a list can come back to it:
 * edit folds the rows into the editor, add opens Add a row over it.
 */
export type EditSearch = { edit?: true; add?: true }

const flag = (v: unknown) => (v === true || v === 'true' || v === 1 || v === '1' ? true : undefined)

export function validateEditSearch(s: Record<string, unknown>): EditSearch {
  const edit = flag(s.edit)
  return { edit, add: edit && flag(s.add) }
}

/** Make a list's address: the screen its row goes on, and settings when it was opened from Settings › Lists. */
export type NewListSearch = { screen: string; back?: 'settings' }

export function validateNewListSearch(s: Record<string, unknown>): NewListSearch {
  return {
    screen: typeof s.screen === 'string' && /^(home|library-[0-9]+)$/.test(s.screen) ? s.screen : 'home',
    back: s.back === 'settings' ? 'settings' : undefined,
  }
}
