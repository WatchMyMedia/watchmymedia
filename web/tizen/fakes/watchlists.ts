import type { components } from '../../src/api/schema'
import { MOVIE_ITEMS, SHOW_ITEMS } from './data'
import { json, problem, type FakeServer } from './server'

// Sam's watchlists on the fictional server: Watchlist (12 titles, 9 in the
// library), Halloween (which keeps watched titles) and With the kids, and
// every watchlist operation. Browse draws their rows and pages from here,
// and marks cards that are on one. A fake title's TMDB id is 50 000 plus
// its id for a movie and 60 000 plus it for a show, as its page says.

type BrowseItem = components['schemas']['BrowseItem']
type MediaList = components['schemas']['MediaList']
type MediaListEntry = components['schemas']['MediaListEntry']
type ScreenRow = components['schemas']['ScreenRow']
type Watchlist = components['schemas']['Watchlist']
type Kind = 'movie' | 'tv'
type Saved = { mediaType: Kind; tmdbId: number; title: string; year?: number; addedAt: string; requestState?: MediaListEntry['requestState'] }
type Kept = { id: number; name: string; removeWatched: boolean; createdAt: string; updatedAt: string; titles: Saved[] }

const MAX = 25
const MAX_TITLES = 500
const DAY = 86_400_000
const ago = (days: number) => new Date(Date.now() - days * DAY).toISOString()

export const fakeTmdbId = (item: Pick<BrowseItem, 'kind' | 'id'>) => (item.kind === 'show' ? 60_000 : 50_000) + item.id

const LIBRARY = [...MOVIE_ITEMS, ...SHOW_ITEMS]
const inLibrary = (t: Pick<Saved, 'mediaType' | 'tmdbId'>) => LIBRARY.find((i) => (i.kind === 'show' ? 'tv' : 'movie') === t.mediaType && fakeTmdbId(i) === t.tmdbId)

let added = 0
function title(name: string, extra: Partial<Saved> = {}): Saved {
  const item = LIBRARY.find((i) => i.title === name)
  const at = ago(++added)
  if (item) return { mediaType: item.kind === 'show' ? 'tv' : 'movie', tmdbId: fakeTmdbId(item), title: item.title, year: item.year, addedAt: at }
  return { mediaType: 'movie', tmdbId: 9300 + added, title: name, year: 2026, addedAt: at, ...extra }
}

const lists: Kept[] = [
  { id: 501, name: 'Watchlist', removeWatched: true, createdAt: ago(60), updatedAt: ago(1), titles: [
    title('Amberline', { requestState: 'requestable' }), title('Lantern Street'), title('Harbor Lights'), title('Signal Garden'), title('Dead Reckoning Bay'), title('Driftwood'),
    title('Winter Harbor', { requestState: 'downloading' }), title('Cinder Valley'), title('Kestrel House'), title('The Last Ferry'), title('The Ninth Bell', { requestState: 'requested' }), title('Paper Moons'),
  ] },
  { id: 502, name: 'Halloween', removeWatched: false, createdAt: ago(40), updatedAt: ago(9), titles: [title('Glass Orchard'), title('Winter Marsh'), title('Moonrise Diner')] },
  { id: 503, name: 'With the kids', removeWatched: true, createdAt: ago(20), updatedAt: ago(4), titles: [title('The Paper Kite'), title('Low Tide Club'), title('Copper Kettle Summer', { requestState: 'requestable' })] },
]
let nextId = 504

const holds = (w: Kept, t: Pick<Saved, 'mediaType' | 'tmdbId'>) => w.titles.some((s) => s.mediaType === t.mediaType && s.tmdbId === t.tmdbId)

/** The ids of Sam's watchlists that hold a title, in the order made. */
export function holding(mediaType: Kind, tmdbId: number): number[] {
  return lists.filter((w) => holds(w, { mediaType, tmdbId })).map((w) => w.id)
}

function view(w: Kept, t?: Pick<Saved, 'mediaType' | 'tmdbId'>): Watchlist {
  return {
    id: w.id, name: w.name, titles: w.titles.length, inLibrary: w.titles.filter((s) => inLibrary(s)).length, removeWatched: w.removeWatched,
    posters: [], contains: !!t && holds(w, t), createdAt: w.createdAt, updatedAt: w.updatedAt,
  }
}

/** A watchlist as the lists' routes send it. */
function asList(w: Kept): MediaList {
  const v = view(w)
  return {
    id: w.id, name: w.name, owner: 'me', screen: 'home', mediaType: 'all', source: { kind: 'watchlist' }, sourceName: '', canEdit: true,
    createdAt: w.createdAt, updatedAt: w.updatedAt, fetchedAt: w.updatedAt, fetching: false, entries: v.titles, inLibrary: v.inLibrary, limit: MAX_TITLES, removeWatched: w.removeWatched,
  }
}

export const watchlistLists = (): MediaList[] => lists.map(asList)
export const watchlistList = (id: number): MediaList | undefined => { const w = lists.find((x) => x.id === id); return w && asList(w) }

/** Marks every card, entry or requestable title in a reply that is on one of Sam's watchlists. */
export function markWatchlisted<T>(v: T): T {
  if (Array.isArray(v)) return v.map(markWatchlisted) as T
  if (!v || typeof v !== 'object') return v
  const o = Object.fromEntries(Object.entries(v).map(([k, x]) => [k, markWatchlisted(x)])) as Record<string, unknown>
  const type = o.kind === 'movie' ? 'movie' : o.kind === 'show' ? 'tv' : o.mediaType === 'movie' || o.mediaType === 'tv' ? o.mediaType : undefined
  if (type && typeof o.tmdbId === 'number' && holding(type, o.tmdbId).length) o.watchlisted = true
  return o as T
}

const SORTS: Record<string, (a: Saved, b: Saved) => number> = {
  added: (a, b) => b.addedAt.localeCompare(a.addedAt),
  title: (a, b) => a.title.localeCompare(b.title),
  released: (a, b) => (b.year ?? 0) - (a.year ?? 0),
}

/** A watchlist's titles as listMediaListEntries sends them: sorted, of one type if asked, numbered in that order. */
export function watchlistEntries(id: number, p: URLSearchParams) {
  const w = lists.find((x) => x.id === id)
  if (!w) return undefined
  const type = p.get('mediaType')
  const all = w.titles.filter((s) => !type || s.mediaType === type).sort(SORTS[p.get('sort') ?? 'added'] ?? SORTS.added)
  const offset = Number(p.get('offset') ?? 0)
  const limit = Number(p.get('limit') ?? 100)
  const entries: MediaListEntry[] = all.map((s, i) => {
    const item = inLibrary(s)
    return { rank: i + 1, title: s.title, mediaType: s.mediaType, found: true, tmdbId: s.tmdbId, year: s.year, addedAt: s.addedAt, watchlisted: true,
      ...(item ? { item: { ...item, tmdbId: s.tmdbId, watchlisted: true } } : { requestState: s.requestState }) }
  })
  return { items: entries.slice(offset, offset + limit), total: all.length, inLibrary: all.filter((s) => inLibrary(s)).length, offset, limit }
}

/** Each watchlist as a row, with what the library has of it: both types on Home, one on a library's screen. */
export function watchlistRows(type?: Kind): ScreenRow[] {
  return lists.flatMap((w) => {
    const of = w.titles.filter((s) => !type || s.mediaType === type)
    const items = of.flatMap((s) => { const i = inLibrary(s); return i ? [{ ...i, tmdbId: s.tmdbId, watchlisted: true }] : [] })
    if (!items.length) return []
    return [{ id: `list:${w.id}`, style: 'poster', title: w.name, listId: w.id, items, seeAll: { kind: 'list', listId: w.id },
      subtitle: items.length < of.length ? `${items.length} of ${of.length} in your library` : undefined }]
  })
}

export function registerWatchlists(server: FakeServer) {
  const find = (id: string) => lists.find((w) => w.id === Number(id))
  const change = (t: Pick<Saved, 'mediaType' | 'tmdbId'>, w?: Kept, extra: object = {}) => ({ watchlists: holding(t.mediaType, t.tmdbId), ...(w ? { watchlist: view(w) } : {}), ...extra })
  const save = (w: Kept, t: Pick<Saved, 'mediaType' | 'tmdbId'>, body: unknown) => {
    if (holds(w, t)) return
    const b = (body ?? {}) as { title?: string; year?: number }
    const item = inLibrary(t)
    w.titles.unshift({ ...t, title: item?.title ?? b.title ?? 'A title', year: item?.year ?? b.year, addedAt: new Date().toISOString() })
    w.updatedAt = new Date().toISOString()
  }
  const drop = (w: Kept, t: Pick<Saved, 'mediaType' | 'tmdbId'>) => {
    const before = w.titles.length
    w.titles = w.titles.filter((s) => !(s.mediaType === t.mediaType && s.tmdbId === t.tmdbId))
    if (w.titles.length !== before) w.updatedAt = new Date().toISOString()
    return w.titles.length !== before
  }
  const titleOf = (params: Record<string, string>) => ({ mediaType: params.mediaType as Kind, tmdbId: Number(params.tmdbId) })
  const taken = (name: string, self?: number) => lists.some((w) => w.id !== self && w.name.toLowerCase() === name.trim().toLowerCase())

  server.route('GET', '/api/me/watchlists', ({ url }) => {
    const type = url.searchParams.get('mediaType') as Kind | null
    const tmdbId = Number(url.searchParams.get('tmdbId') ?? 0)
    const t = type && tmdbId ? { mediaType: type, tmdbId } : undefined
    return { items: lists.map((w) => view(w, t)), max: MAX, maxTitles: MAX_TITLES }
  })
  server.route('POST', '/api/me/watchlists', ({ body }) => {
    const b = body as { name: string; removeWatched?: boolean }
    if (lists.length >= MAX) return problem(422, `You have ${MAX} watchlists, the most you can make.`)
    if (taken(b.name)) return problem(422, `You already have a watchlist called ${b.name.trim()}.`)
    const now = new Date().toISOString()
    const w: Kept = { id: nextId++, name: b.name.trim(), removeWatched: b.removeWatched ?? true, createdAt: now, updatedAt: now, titles: [] }
    lists.push(w)
    return json(view(w), 201)
  })
  server.route('PATCH', '/api/me/watchlists/:id', ({ params, body }) => {
    const w = find(params.id)
    if (!w) return problem(404, 'no such watchlist')
    const b = body as { name?: string; removeWatched?: boolean }
    if (b.name !== undefined && taken(b.name, w.id)) return problem(422, `You already have a watchlist called ${b.name.trim()}.`)
    if (b.name !== undefined) w.name = b.name.trim()
    if (b.removeWatched !== undefined) w.removeWatched = b.removeWatched
    w.updatedAt = new Date().toISOString()
    return view(w)
  })
  server.route('DELETE', '/api/me/watchlists/:id', ({ params }) => {
    const at = lists.findIndex((w) => w.id === Number(params.id))
    if (at < 0) return problem(404, 'no such watchlist')
    lists.splice(at, 1)
    return new Response(null, { status: 204 })
  })
  server.route('PUT', '/api/me/watchlists/:id/titles/:mediaType/:tmdbId', ({ params, body }) => {
    const w = find(params.id)
    if (!w) return problem(404, 'no such watchlist')
    if (w.titles.length >= MAX_TITLES) return problem(422, `A watchlist holds up to ${MAX_TITLES} titles.`)
    const t = titleOf(params)
    save(w, t, body)
    return change(t, w)
  })
  server.route('DELETE', '/api/me/watchlists/:id/titles/:mediaType/:tmdbId', ({ params }) => {
    const w = find(params.id)
    if (!w) return problem(404, 'no such watchlist')
    const t = titleOf(params)
    drop(w, t)
    return change(t, w)
  })
  server.route('PUT', '/api/me/watchlist/titles/:mediaType/:tmdbId', ({ params, body }) => {
    const t = titleOf(params)
    if (lists.length > 1) {
      return new Response(JSON.stringify({ status: 409, title: 'Conflict', detail: 'several watchlists', watchlists: lists.map((w) => view(w, t)) }),
        { status: 409, headers: { 'Content-Type': 'application/problem+json' } })
    }
    let created = false
    if (!lists.length) {
      const now = new Date().toISOString()
      lists.push({ id: nextId++, name: 'Watchlist', removeWatched: true, createdAt: now, updatedAt: now, titles: [] })
      created = true
    }
    save(lists[0], t, body)
    return change(t, lists[0], created ? { created: true } : {})
  })
  server.route('DELETE', '/api/me/watchlist/titles/:mediaType/:tmdbId', ({ params }) => {
    const t = titleOf(params)
    const removed = lists.filter((w) => drop(w, t)).map((w) => w.id)
    return change(t, undefined, { removed })
  })
}
