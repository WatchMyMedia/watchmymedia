import type { components } from '../../src/api/schema'
import { FakeServer, problem } from './server'
import { AIRED, CONTINUE, LIBRARIES, MOVIE_ITEMS, SHOW_ITEMS } from './data'

// Home, the libraries, See all and list pages on the fictional server, and
// what the rail needs: Live TV's capabilities (on, so the rail shows it) and
// the request options (an admin with two in the Inbox, so Settings shows
// its count). Every title, name and channel is made up; there are no
// pictures. `mode` (?fakes=) "empty", "scanning" and "unreadable" give an
// empty Home and why.

type BrowseItem = components['schemas']['BrowseItem']
type EpisodeCard = components['schemas']['EpisodeCard']
type HomeData = components['schemas']['HomeData']
type Library = components['schemas']['Library']
type LiveChannel = components['schemas']['LiveTVChannel']
type MediaList = components['schemas']['MediaList']
type MediaListEntry = components['schemas']['MediaListEntry']
type ScreenRow = components['schemas']['ScreenRow']

const DAY = 86_400_000
const ago = (ms: number) => new Date(Date.now() - ms).toISOString()
const day = (daysAgo: number) => new Date(Date.now() - daysAgo * DAY).toISOString().slice(0, 10)

const GENRES = ['Drama', 'Comedy', 'Thriller', 'Mystery', 'Science Fiction', 'Documentary', 'Adventure', 'Romance', 'Crime']
const genresOf = (i: number) => [GENRES[i % GENRES.length], ...(i % 3 === 0 ? [GENRES[(i + 4) % GENRES.length]] : [])]

// More made-up titles, so the grids have pages to load and letters to jump between.
const MORE_MOVIES = [
  '17 Lanterns', '24 Frames North', 'An Honest Winter', 'Ashgrove Junction', 'Blue Hour Motel', 'Brightwater', 'Copper Kettle Summer', 'Dunmore Weir', 'Echo Valley',
  'Ember Season', 'Ferrous', 'Foxglove', 'Glasswing', 'Gull Island', 'Halfway to Juniper', 'Hush Creek', 'Ironwood', 'Juniper Court', 'Kestrel Point', 'Kingfisher',
  'Larkspur Avenue', 'Lowtide Radio', 'Lumen', 'Marrow Lane', 'Millbrook', 'Mirrorwater', 'Nightjar', 'Ninety Fathoms', 'Northern Static', 'Old Ferry Road',
  'Overland', 'Pale Water', 'Pocket Atlas', 'Quarry Lane', 'Ravenscar', 'Red Signal', 'Saltwind', 'Second Ascent', 'Signal to Noise', 'Stonechat', 'Sundown Freight',
  'The Ninth Lighthouse', 'The Weir at Dunmore', 'Tidewater', 'Tin Sky', 'Two Rivers', 'Velvet Hours', 'Westward', 'Winter Quarters', 'Yardarm', 'Zephyr Hill',
]
const MORE_SHOWS = [
  'Ashfall Ridge', 'Atlas Road', 'Autumn Ledger', 'Bell Harbour', 'Blackthorn', 'Broken Compass', 'Cinder Hall', 'Coral Street', 'Driftwood Inn', 'Ember Street',
  'Fieldnotes', 'Glass Country', 'Hollow Pines', 'Kestrel Station', 'Low Tide', 'Midnight Cartography', 'Mountain Clerk', 'Old Ferry', 'Orbital Decay',
  'Paper Lanterns', 'Pinewood Assembly', 'Riverbend Clinic', 'Second Harbour', 'Signal Hill', 'The Lantern House', 'The Moth Hours', 'The Narrow Hours',
  'Velvet Static', 'Westward Signals', '9 Bells',
]

function movie(title: string, i: number, over: Partial<BrowseItem> = {}): BrowseItem {
  const year = i % 5 === 0 ? 1980 + (i % 10) : 2010 + (i % 17)
  return {
    kind: 'movie', id: 1001 + i, libraryId: 1, libraryType: 'movie', title, year, overview: `A made-up film for the TV app’s fakes (${i + 1}).`,
    genres: genresOf(i), hdr: i % 4 === 1, quality: i % 3 === 0 ? '4K' : '1080p', addedAt: ago((i + 15) * DAY * 1.3), isNew: false, totalSize: 4e9,
    watched: i % 6 === 2, runtimeMin: 84 + (i * 11) % 70, rating: 5.5 + (i % 9) * .4, releaseDate: `${year}-${String(1 + i % 12).padStart(2, '0')}-${String(1 + i % 27).padStart(2, '0')}`,
    progress: i % 7 === 3 ? .4 : undefined, ...over,
  }
}

function show(title: string, i: number, over: Partial<BrowseItem> = {}): BrowseItem {
  return {
    kind: 'show', id: 2001 + i, libraryId: 2, libraryType: 'tv', title, year: 2012 + (i % 14), overview: `A made-up series for the TV app’s fakes (${i + 1}).`,
    genres: genresOf(i + 2), hdr: false, quality: i % 4 === 0 ? '4K' : '1080p', addedAt: ago((i + 12) * DAY * 1.1), isNew: false, totalSize: 3e10, watched: false,
    episodeCount: 6 + (i * 5) % 40, status: i % 3 === 0 ? 'Ended' : 'Returning Series', lastAirDate: day(i * 9 + 3), rating: 6 + (i % 7) * .4, ...over,
  }
}

const MOVIES: BrowseItem[] = [
  ...MOVIE_ITEMS.map((m, i) => ({ ...m, quality: i % 3 === 0 ? '4K' as const : '1080p' as const, releaseDate: `${m.year}-0${1 + (i % 9)}-1${i % 9}` })),
  ...MORE_MOVIES.map((t, i) => movie(t, i)),
]
const SHOWS: BrowseItem[] = [
  ...SHOW_ITEMS.map((s, i) => ({ ...s, lastAirDate: day(i + 1), quality: '1080p' as const })),
  ...MORE_SHOWS.map((t, i) => show(t, i)),
]

/** As the server sorts titles: a leading The, A or An skipped. */
function titleKey(t: string) {
  const l = t.toLowerCase()
  for (const a of ['the ', 'a ', 'an ']) if (l.startsWith(a)) return l.slice(a.length)
  return l
}
const letterOf = (t: string) => { const c = titleKey(t).charAt(0).toUpperCase(); return c >= 'A' && c <= 'Z' ? c : '#' }

const SORTS: Record<string, (a: BrowseItem, b: BrowseItem) => number> = {
  title: (a, b) => (titleKey(a.title) < titleKey(b.title) ? -1 : titleKey(a.title) > titleKey(b.title) ? 1 : 0),
  added: (a, b) => b.addedAt.localeCompare(a.addedAt),
  year: (a, b) => (b.year ?? 0) - (a.year ?? 0),
  rating: (a, b) => (b.rating ?? 0) - (a.rating ?? 0),
  released: (a, b) => (b.releaseDate ?? '').localeCompare(a.releaseDate ?? ''),
  runtime: (a, b) => (b.runtimeMin ?? 0) - (a.runtimeMin ?? 0),
  aired: (a, b) => (b.lastAirDate ?? '').localeCompare(a.lastAirDate ?? ''),
}

const FILTERS: Record<string, (i: BrowseItem) => boolean> = {
  unwatched: (i) => !i.watched, '4k': (i) => i.quality === '4K', hdr: (i) => i.hdr,
  continuing: (i) => i.status !== 'Ended', ended: (i) => i.status === 'Ended',
}

/** The fake's watched marks, changed by Hold OK. */
const watched = new Set(MOVIES.filter((m) => m.watched).map((m) => m.id))
const marked = (i: BrowseItem): BrowseItem => (i.kind === 'movie' ? { ...i, watched: watched.has(i.id), isNew: i.isNew && !watched.has(i.id) } : i)

function libraryItems(id: number) {
  return (id === 1 ? MOVIES : id === 2 ? SHOWS : []).map(marked)
}

function itemsReply(id: number, p: URLSearchParams) {
  const all = libraryItems(id)
  const filter = FILTERS[p.get('filter') ?? '']
  const decade = Number(p.get('decade') ?? 0)
  const q = (p.get('q') ?? '').toLowerCase()
  const genre = p.get('genre') ?? ''
  const before = all.filter((i) => (!filter || filter(i)) && (!decade || (i.year ?? 0) - ((i.year ?? 0) % 10) === decade) && (!q || i.title.toLowerCase().includes(q)))
  const genreCounts = Object.fromEntries(GENRES.map((g) => [g, before.filter((i) => i.genres.includes(g)).length]))
  const sort = p.get('sort') ?? 'title'
  const items = before.filter((i) => !genre || i.genres.includes(genre)).sort(SORTS[sort] ?? SORTS.title)
  const letters: { letter: string; offset: number }[] = []
  if (sort === 'title') items.forEach((i, at) => { const l = letterOf(i.title); if (letters[letters.length - 1]?.letter !== l) letters.push({ letter: l, offset: at }) })
  const limit = Number(p.get('limit') ?? 100)
  const offset = Number(p.get('offset') ?? 0)
  const decades = [...new Set(all.map((i) => (i.year ?? 0) - ((i.year ?? 0) % 10)))].filter(Boolean).sort()
  return {
    items: items.slice(offset, offset + limit), total: items.length, limit, offset,
    facets: { genres: GENRES.filter((g) => all.some((i) => i.genres.includes(g))), decades },
    genreCounts, letters: sort === 'title' ? letters : undefined,
    totals: { items: all.length, episodes: id === 2 ? all.reduce((n, s) => n + (s.episodeCount ?? 0), 0) : 0, continuing: all.filter((i) => i.status && i.status !== 'Ended').length, unmatched: 0, totalSize: 3e11 },
  }
}

const byId = (id: number) => MOVIES.find((m) => m.id === id)!

// Lists: one from TMDB, an import with titles TMDB couldn't find, and one whose last fetch failed.
const LIST_ENTRIES: Record<number, MediaListEntry[]> = {
  1: [
    { rank: 1, title: 'Kestrel Point', mediaType: 'movie', found: true, tmdbId: 9001, year: 1986, item: byId(1001 + MORE_MOVIES.indexOf('Kestrel Point')) },
    { rank: 2, title: 'Sundown Freight', mediaType: 'movie', found: true, tmdbId: 9002, year: 1984, item: byId(1001 + MORE_MOVIES.indexOf('Sundown Freight')) },
    { rank: 3, title: 'Halfway to Juniper', mediaType: 'movie', found: true, tmdbId: 9003, year: 1988, requestState: 'requestable' },
    { rank: 4, title: 'The Ninth Lighthouse', mediaType: 'movie', found: true, tmdbId: 9004, year: 1982, item: byId(1001 + MORE_MOVIES.indexOf('The Ninth Lighthouse')) },
    { rank: 5, title: 'Velvet Hours', mediaType: 'movie', found: true, tmdbId: 9005, year: 1987, requestState: 'requested' },
    { rank: 6, title: 'Northern Static', mediaType: 'movie', found: true, tmdbId: 9006, year: 1989, requestState: 'downloading' },
    { rank: 7, title: 'Glasswing', mediaType: 'movie', found: true, tmdbId: 9007, year: 1985, item: byId(1001 + MORE_MOVIES.indexOf('Glasswing')) },
    { rank: 8, title: 'Lowtide Radio', mediaType: 'movie', found: true, tmdbId: 9008, year: 1983, requestState: 'requestable' },
    { rank: 9, title: 'Ember Season', mediaType: 'movie', found: true, tmdbId: 9009, year: 1981, item: byId(1001 + MORE_MOVIES.indexOf('Ember Season')) },
    { rank: 10, title: 'Ashgrove Junction', mediaType: 'movie', found: true, tmdbId: 9010, year: 1987, item: byId(1001 + MORE_MOVIES.indexOf('Ashgrove Junction')) },
    { rank: 11, title: 'Copper Kettle Summer', mediaType: 'movie', found: true, tmdbId: 9011, year: 1986, requestState: 'requestable' },
    { rank: 12, title: 'Marrow Lane', mediaType: 'movie', found: true, tmdbId: 9012, year: 1989, item: byId(1001 + MORE_MOVIES.indexOf('Marrow Lane')) },
    { rank: 13, title: 'The Hollow Coast', mediaType: 'movie', found: true, tmdbId: 9013, year: 1982 },
    { rank: 14, title: 'Blue Hour Motel', mediaType: 'movie', found: true, tmdbId: 9014, year: 1985, item: byId(1001 + MORE_MOVIES.indexOf('Blue Hour Motel')) },
    { rank: 15, title: 'The Last Ferry North', mediaType: 'movie', found: true, tmdbId: 9015, year: 1988, requestState: 'requestable' },
    { rank: 16, title: 'Saltwind', mediaType: 'movie', found: true, tmdbId: 9016, year: 1982, item: byId(1001 + MORE_MOVIES.indexOf('Saltwind')) },
  ],
  2: [
    { rank: 1, title: 'Dead Reckoning Bay', mediaType: 'movie', found: true, tmdbId: 9101, year: 2024, item: MOVIES[1] },
    { rank: 2, title: 'The Paper Kite', mediaType: 'movie', found: true, tmdbId: 9102, year: 2025, item: MOVIES[0] },
    { rank: 3, title: 'Larkspur Avenue (1997)', mediaType: 'movie', found: false },
    { rank: 4, title: 'Halfway to Juniper', mediaType: 'movie', found: true, tmdbId: 9003, year: 1988, requestState: 'requestable' },
    { rank: 5, title: 'Harbor Lights', mediaType: 'tv', found: true, tmdbId: 9105, year: 2022, item: SHOWS[0] },
    { rank: 6, title: 'tt9900417', mediaType: 'movie', found: false },
    { rank: 7, title: 'The Quiet Shore', mediaType: 'tv', found: true, tmdbId: 9107, year: 2019, requestState: 'requestable' },
    { rank: 8, title: 'Tin Sky', mediaType: 'movie', found: true, tmdbId: 9108, year: 2016, item: byId(1001 + MORE_MOVIES.indexOf('Tin Sky')) },
  ],
  3: [
    { rank: 1, title: 'Low Tide Club', mediaType: 'movie', found: true, tmdbId: 9201, year: 2018, item: MOVIES[7] },
    { rank: 2, title: 'Signal Garden', mediaType: 'movie', found: true, tmdbId: 9202, year: 2017, item: MOVIES[8] },
    { rank: 3, title: 'Pocket Kingdom', mediaType: 'movie', found: true, tmdbId: 9203, year: 1994, requestState: 'requestable' },
    { rank: 4, title: 'Lantern Street', mediaType: 'movie', found: true, tmdbId: 9204, year: 2022, item: MOVIES[3] },
  ],
}

const inLibrary = (id: number) => LIST_ENTRIES[id].filter((e) => e.item).length

const LISTS: MediaList[] = [
  { id: 1, name: 'Best of the 1980s', owner: 'everyone', screen: 'home', mediaType: 'movie', source: { kind: 'tmdb', chart: 'list', ref: '8812' }, sourceName: 'TMDB',
    canEdit: true, createdAt: ago(40 * DAY), updatedAt: ago(3 * 3_600_000), fetchedAt: ago(3 * 3_600_000), nextFetchAt: new Date(Date.now() + 21 * 3_600_000).toISOString(),
    fetching: false, entries: LIST_ENTRIES[1].length, inLibrary: inLibrary(1), limit: 50 },
  { id: 2, name: 'Sam’s watchlist', owner: 'me', screen: 'library-1', mediaType: 'all', source: { kind: 'import', format: 'lines' },
    canEdit: true, createdAt: ago(17 * DAY), updatedAt: ago(17 * DAY), fetching: false, entries: LIST_ENTRIES[2].length, inLibrary: inLibrary(2), notFound: 2, limit: 250 },
  { id: 3, name: 'Saturday matinees', owner: 'everyone', screen: 'library-1', mediaType: 'movie', source: { kind: 'tmdb', chart: 'popular' }, sourceName: 'TMDB',
    canEdit: false, createdAt: ago(60 * DAY), updatedAt: ago(2 * DAY), fetchedAt: ago(2 * DAY), error: 'TMDB did not answer', errorAt: ago(3_600_000),
    nextFetchAt: new Date(Date.now() + 3_600_000).toISOString(), fetching: false, entries: LIST_ENTRIES[3].length, inLibrary: inLibrary(3), limit: 50 },
]

function listRow(list: MediaList): ScreenRow {
  const items = LIST_ENTRIES[list.id].flatMap((e) => (e.item ? [marked(e.item)] : []))
  return {
    id: `list:${list.id}`, style: 'poster', title: list.name, listId: list.id, items, seeAll: { kind: 'list', listId: list.id },
    source: list.source.kind === 'tmdb' ? { kind: 'tmdb', name: 'TMDB', url: 'https://www.themoviedb.org/list/8812' } : undefined,
    subtitle: `${list.inLibrary} of ${list.entries}${list.source.kind === 'tmdb' ? ' · From TMDB' : ''}`,
  }
}

const recent = (items: BrowseItem[]) => [...items].sort(SORTS.added).slice(0, 14).map(marked)

const ADDED_SHOWS: BrowseItem[] = [
  { ...SHOWS[0], subtitle: 'S03E07', episodeId: 5000, seasonNumber: 3, isNew: true, addedAt: ago(3_600_000) },
  { ...SHOWS[MORE_SHOWS.indexOf('Orbital Decay') + SHOW_ITEMS.length], subtitle: 'Season 1', isNew: true, addedAt: ago(DAY) },
  { ...SHOWS[MORE_SHOWS.indexOf('The Lantern House') + SHOW_ITEMS.length], subtitle: 'S02E10', episodeId: 7210, seasonNumber: 2, addedAt: ago(2 * DAY) },
  { ...SHOWS[MORE_SHOWS.indexOf('Westward Signals') + SHOW_ITEMS.length], subtitle: 'Complete series', addedAt: ago(3 * DAY) },
  { ...SHOWS[MORE_SHOWS.indexOf('Glass Country') + SHOW_ITEMS.length], subtitle: 'S01E05', episodeId: 7305, seasonNumber: 1, addedAt: ago(4 * DAY) },
  { ...SHOWS[MORE_SHOWS.indexOf('Hollow Pines') + SHOW_ITEMS.length], subtitle: '6 episodes', addedAt: ago(6 * DAY) },
  ...SHOWS.slice(1, 8).map((s, i) => ({ ...s, subtitle: `Season ${1 + (i % 3)}`, addedAt: ago((7 + i) * DAY) })),
]

const FOLLOWED: EpisodeCard[] = AIRED.slice(0, 3).map((e, i) => ({ ...e, episodeId: 5100 + i, episodeNumber: e.episodeNumber + 1, isNew: true, airDate: day(i), title: ['Slack Water', 'The Cartographer', 'Brass Tacks II'][i], fileId: 6100 + i }))

function fakeHome(): HomeData {
  const rows: ScreenRow[] = [
    { id: 'continue', style: 'continue', title: 'Continue watching', continue: CONTINUE },
    { id: 'followed', style: 'episode', title: 'New from shows you follow', episodes: FOLLOWED },
    { id: 'live', style: 'live', title: 'Live TV' },
    { id: 'recentlyAddedShows', style: 'poster', title: 'Recently added · TV Shows', items: ADDED_SHOWS, seeAll: { kind: 'library', libraryId: 2, sort: 'added' } },
    { id: 'recentlyAired', style: 'episode', title: 'Recently aired · Episodes', episodes: AIRED.map((e, i) => ({ ...e, airDate: day(i + 1) })), seeAll: { kind: 'library', libraryId: 2, sort: 'aired' } },
    listRow(LISTS[0]),
    { id: 'recentlyAddedMovies', style: 'poster', title: 'Recently added · Movies', items: recent(MOVIES), seeAll: { kind: 'library', libraryId: 1, sort: 'added' } },
    { id: 'recentlyReleased', style: 'wide', title: 'Recently released · Movies', items: [...MOVIES].sort(SORTS.released).slice(0, 12).map(marked), seeAll: { kind: 'library', libraryId: 1, sort: 'released' } },
  ]
  return {
    continueWatching: [], recentlyAddedShows: [], recentlyAired: [], recentlyAddedMovies: [], recentlyReleased: [], rows,
    hero: {
      kind: 'episode', title: 'Harbor Lights', kicker: 'New episode · aired yesterday', facts: 'S01 E03 · The Long Dark · 52 min', contentRating: 'TV-14', quality: '4K', hdr: true,
      overview: 'A storm closes the harbour for the first time in forty years, and Maren has one night to find out who moved the channel markers before the ferry comes in.',
      fileId: AIRED[0].fileId, episodeId: AIRED[0].episodeId, showId: AIRED[0].showId, seasonNumber: AIRED[0].seasonNumber,
    },
    totals: { movies: MOVIES.length, shows: SHOWS.length, episodes: SHOWS.reduce((n, s) => n + (s.episodeCount ?? 0), 0), files: 2600, totalSize: 18.4 * 1024 ** 4 },
    lastScannedAt: ago(12 * 60_000), movieLibraryId: 1, tvLibraryId: 2,
  }
}

const EMPTY_HOME: HomeData = {
  continueWatching: [], recentlyAddedShows: [], recentlyAired: [], recentlyAddedMovies: [], recentlyReleased: [], rows: [],
  totals: { movies: 0, shows: 0, episodes: 0, files: 0, totalSize: 0 },
}

function libraryRowsReply(id: number): components['schemas']['LibraryRows'] {
  // The TV library arranges none, so it opens on its grid (LibraryAZ); Movies has rows above it (LibraryRows).
  if (id !== 1) return { recentlyAdded: [], recentlyAired: [], recentlyReleased: [], rows: [] }
  return {
    recentlyAdded: [], recentlyAired: [], recentlyReleased: [],
    rows: [
      { id: 'recentlyAdded', style: 'poster', title: 'Recently added', items: recent(MOVIES), seeAll: { kind: 'library', libraryId: 1, sort: 'added' } },
      { id: 'recentlyReleased', style: 'wide', title: 'Recently released', items: [...MOVIES].sort(SORTS.released).slice(0, 10).map(marked), seeAll: { kind: 'library', libraryId: 1, sort: 'released' } },
      listRow(LISTS[2]),
      listRow(LISTS[1]),
    ],
  }
}

/** Six channels, four with listings: on now started a while ago, next after it. */
function channels(): LiveChannel[] {
  const half = 30 * 60_000
  const start = Math.floor(Date.now() / half) * half
  const slot = (n: number, title: string, extra: Partial<components['schemas']['LiveTVProgramme']> = {}) => ({
    id: `p${n}`, title, start: new Date(start - (n % 2) * half).toISOString(), end: new Date(start + half * (1 + (n % 3))).toISOString(), categories: ['News', 'Drama', 'Sports', 'Movie'][n % 4] ? [['News', 'Drama', 'Sports', 'Movie'][n % 4]] : [], ...extra,
  })
  const next = (n: number, title: string) => ({ id: `n${n}`, title, start: new Date(start + half * (1 + (n % 3))).toISOString(), end: new Date(start + half * (3 + (n % 3))).toISOString() })
  return [
    { id: 'ch2', number: '2.1', name: 'KLAK', sourceId: 'hd1', enabled: true, available: true, now: slot(0, 'Lakeside Evening News', { description: 'The day’s stories from around the lake.' }), next: next(0, 'Weather Watch') },
    { id: 'ch4', number: '4.1', name: 'NorthTV', sourceId: 'hd1', enabled: true, available: true, now: slot(1, 'The Cartographer’s Daughter', { season: 2, episode: 4, episodeTitle: 'Contours' }), next: next(1, 'Late Desk') },
    { id: 'ch7', number: '7.1', name: 'KBAY', sourceId: 'hd1', enabled: true, available: true, now: slot(2, 'Harbour League Football'), next: next(2, 'Post-match') },
    { id: 'ch9', number: '9.1', name: 'Ridge 9', sourceId: 'hd1', enabled: true, available: true, now: slot(3, 'Saturday Matinee: Tin Sky', { kind: 'movie', airDate: '1979-05-02' }), next: next(3, 'Night Owls') },
    { id: 'ch11', number: '11.1', name: 'Valley PBS', sourceId: 'hd1', enabled: true, available: true },
    { id: 'ch13', number: '13.1', name: 'Coast 13', sourceId: 'hd1', enabled: true, available: true },
  ]
}

function libraries(mode: string): Library[] {
  if (mode === 'empty') return []
  if (mode === 'scanning') return LIBRARIES.map((l) => ({ ...l, fileCount: 0, scan: { libraryId: l.id, scanned: 12, total: 400, current: 'a.mkv' } }))
  if (mode === 'unreadable') return LIBRARIES.map((l, i) => (i === 0 ? { ...l, fileCount: 0, lastScanError: 'open /media/movies: no such file or directory', lastScanErrorAt: ago(600_000) } : { ...l, fileCount: 0 }))
  return LIBRARIES
}

export function registerBrowse(server: FakeServer, mode = '') {
  const emptyHome = mode === 'empty' || mode === 'scanning' || mode === 'unreadable'
  server.route('GET', '/api/libraries', () => ({ items: libraries(mode) }))
  server.route('GET', '/api/libraries/:id', ({ params }) => libraries(mode).find((l) => l.id === Number(params.id)) ?? problem(404, 'no such library'))
  server.route('GET', '/api/home', () => (emptyHome ? EMPTY_HOME : fakeHome()))
  server.route('GET', '/api/libraries/:id/rows', ({ params }) => libraryRowsReply(emptyHome ? 0 : Number(params.id)))
  server.route('GET', '/api/libraries/:id/items', ({ params, url }) => itemsReply(emptyHome ? 0 : Number(params.id), url.searchParams))
  server.route('GET', '/api/lists', () => ({ items: LISTS }))
  server.route('GET', '/api/lists/:id', ({ params }) => LISTS.find((l) => l.id === Number(params.id)) ?? problem(404, 'no such list'))
  server.route('GET', '/api/lists/:id/entries', ({ params, url }) => {
    const id = Number(params.id)
    const all = LIST_ENTRIES[id]
    if (!all) return problem(404, 'no such list')
    const offset = Number(url.searchParams.get('offset') ?? 0)
    const limit = Number(url.searchParams.get('limit') ?? 100)
    const items = all.map((e) => (e.item ? { ...e, item: marked(e.item) } : e))
    return { items: items.slice(offset, offset + limit), total: all.length, inLibrary: inLibrary(id), offset, limit }
  })
  const mark = (on: boolean) => ({ params }: { params: Record<string, string> }) => {
    if (params.type === 'movie') { if (on) watched.add(Number(params.id)); else watched.delete(Number(params.id)) }
    return new Response(null, { status: 204 })
  }
  server.route('POST', '/api/watch/:type/:id/watched', mark(true))
  server.route('DELETE', '/api/watch/:type/:id/watched', mark(false))
  server.route('GET', '/api/live-tv/capabilities', () => ({ enabled: true, allowed: true, playback: true, guide: true, setupComplete: true, recording: true, canRecord: true }))
  server.route('GET', '/api/live-tv/channels', () => ({ items: channels() }))
  server.route('GET', '/api/requests/options', () => ({ movies: true, shows: true, needsApproval: false, problems: true, manage: true, backend: 'arr', inbox: 2, delete: true }))
}
