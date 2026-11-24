import type { components } from '../../src/api/schema'
import { MOVIE_ITEMS, SHOW_ITEMS } from './data'
import { FakeServer, problem } from './server'
import type { FakeIdentity } from './others'

// One card across servers in the preview (?fakes=onecard): Lakeside, the
// current server at home; Cabin, reached away from home, with some of
// Lakeside's titles (same TMDB ids) and some of its own; and Den, signed in,
// which never answers, so its line says it is still being asked and then
// that it didn't answer. Dead Reckoning Bay is on both, further along on
// Cabin; Harbor Lights is on both, with season 3's Neap and Slack Water only
// on Cabin so far; The Harbor Choir and Salt Road are only on Cabin. Every
// name, title and address is made up.

type Movie = components['schemas']['Movie']
type Show = components['schemas']['Show']
type MovieDetail = components['schemas']['MovieDetail']
type ShowDetail = components['schemas']['ShowDetail']
type Episode = components['schemas']['Episode']
type BrowseItem = components['schemas']['BrowseItem']
type HomeData = components['schemas']['HomeData']
type ContinueItem = components['schemas']['ContinueItem']
type MediaFileDetail = components['schemas']['MediaFileDetail']

/** Cabin, away from home: a public name the preview answers itself. */
export const CABIN_AWAY: FakeIdentity = { origin: 'https://cabin.example.org', id: 'fake-cabin', name: 'Cabin' }
/** Den: signed in here, but never answers. */
export const DEN_SILENT: FakeIdentity = { origin: 'http://den.local:27587', id: 'fake-den', name: 'Den' }

const MIN = 60_000
const GB = 1024 ** 3
const ago = (ms: number) => new Date(Date.now() - ms).toISOString()
const day = (days: number) => new Date(Date.now() + days * 86_400_000).toISOString().slice(0, 10)

// --- Lakeside's copies by TMDB id (its titles' TMDB ids are 50 000 + a movie's id, 60 000 + a show's) ---

/** GET /api/movies?tmdbId= and /api/shows?tmdbId= on Lakeside: its copy, or none. */
export function registerLakesideCopies(server: FakeServer) {
  server.route('GET', '/api/movies', ({ url }) => {
    const tmdbId = Number(url.searchParams.get('tmdbId'))
    if (!tmdbId) return problem(422, 'tmdbId is required')
    const m = MOVIE_ITEMS.find((x) => x.tmdbId === tmdbId)
    return { items: m ? [asMovie(m)] : [] }
  })
  server.route('GET', '/api/shows', ({ url }) => {
    const tmdbId = Number(url.searchParams.get('tmdbId'))
    if (!tmdbId) return problem(422, 'tmdbId is required')
    const s = SHOW_ITEMS.find((x) => x.tmdbId === tmdbId)
    return { items: s ? [asShow(s)] : [] }
  })
}

const asMovie = (m: BrowseItem): Movie => ({ id: m.id, libraryId: m.libraryId, tmdbId: m.tmdbId ?? 0, title: m.title, overview: m.overview, genres: m.genres, fileCount: 1, credits: [], year: m.year, runtimeMin: m.runtimeMin, watched: m.watched, progress: m.progress })
const asShow = (s: BrowseItem): Show => ({ id: s.id, libraryId: s.libraryId, tmdbId: s.tmdbId ?? 0, title: s.title, overview: s.overview, genres: s.genres, episodeCount: s.episodeCount ?? 10, credits: [], year: s.year, status: s.status })

// --- Cabin's library ---

type CabinMovie = { id: number; tmdbId: number; title: string; year: number; runtime: number; overview: string; genres: string[]; addedDaysAgo: number; positionMin?: number; playedHoursAgo?: number; height?: number }

const CABIN_MOVIES: CabinMovie[] = [
  { id: 5102, tmdbId: 50_102, title: 'Dead Reckoning Bay', year: 2026, runtime: 134, overview: 'A salvage diver takes a cash job to raise a yacht that sank in calm water, and finds the manifest was written after it went down.', genres: ['Thriller'], addedDaysAgo: 30, positionMin: 80, playedHoursAgo: 2 },
  { id: 5101, tmdbId: 50_101, title: 'The Paper Kite', year: 2025, runtime: 100, overview: 'A made-up film for the TV app’s fakes (1).', genres: ['Drama'], addedDaysAgo: 40 },
  { id: 5901, tmdbId: 59_901, title: 'The Harbor Choir', year: 2022, runtime: 101, overview: 'A village choir loses its church hall and rehearses in the boat shed for one winter, for a contest nobody expects it to enter.', genres: ['Drama'], addedDaysAgo: 12, positionMin: 63, playedHoursAgo: 5 },
  { id: 5902, tmdbId: 59_902, title: 'Salt Road', year: 2025, runtime: 106, overview: 'Two sisters drive their father’s ashes the length of the coast road, stopping in every town he ever lied about.', genres: ['Drama'], addedDaysAgo: 0.1 },
  { id: 5903, tmdbId: 59_903, title: 'Tideline', year: 2024, runtime: 99, overview: 'A made-up film that only Cabin has.', genres: ['Mystery'], addedDaysAgo: 3 },
]

const cabinWatch = new Map<number, { positionMs: number; completed: boolean; updatedAt: string }>(CABIN_MOVIES.filter((m) => m.positionMin)
  .map((m) => [m.id * 10 + 1, { positionMs: m.positionMin! * MIN, completed: false, updatedAt: ago(m.playedHoursAgo! * 3_600_000) }]))

function cabinFile(id: number, relativePath: string, durationMs: number, over: Partial<MediaFileDetail> = {}): MediaFileDetail {
  return {
    id, libraryId: 11, path: `/srv/media/${relativePath}`, relativePath, size: 3.4 * GB, modifiedAt: ago(30 * 86_400_000), status: 'present', parsed: { kind: 'movie', title: '' },
    firstSeenAt: ago(30 * 86_400_000), addedAt: ago(30 * 86_400_000), lastSeenAt: ago(86_400_000), matchStatus: 'matched', matchLocked: false, matchIgnored: false,
    container: 'matroska', videoCodec: 'h264', audioCodec: 'aac', width: 1920, height: 1080, durationMs, ...over,
  }
}

function cabinMovieDetail(m: CabinMovie): MovieDetail {
  const file = cabinFile(m.id * 10 + 1, `${m.title} (${m.year})/${m.title.replace(/ /g, '.')}.1080p.mkv`, m.runtime * MIN)
  const w = cabinWatch.get(file.id)
  return {
    id: m.id, libraryId: 11, tmdbId: m.tmdbId, title: m.title, year: m.year, overview: m.overview, genres: m.genres, runtimeMin: m.runtime, contentRating: 'PG', rating: 7.1,
    quality: '1080p', hdr: false, files: [file], fileCount: 1, totalSize: file.size, credits: [], trailers: [],
    watch: w ? { positionMs: w.positionMs, durationMs: m.runtime * MIN, completed: w.completed, updatedAt: w.updatedAt, fileId: file.id } : undefined,
  }
}

function cabinItem(m: CabinMovie): BrowseItem {
  const w = cabinWatch.get(m.id * 10 + 1)
  return {
    kind: 'movie', id: m.id, libraryId: 11, libraryType: 'movie', title: m.title, year: m.year, overview: m.overview, genres: m.genres, hdr: false, quality: '1080p',
    addedAt: ago(m.addedDaysAgo * 86_400_000), isNew: m.addedDaysAgo < 7, totalSize: 3.4 * GB, watched: !!w?.completed, runtimeMin: m.runtime, rating: 7,
    tmdbId: m.tmdbId, progress: w && !w.completed ? w.positionMs / (m.runtime * MIN) : undefined, releaseDate: `${m.year}-06-01`,
  }
}

// Harbor Lights on Cabin: season 3 with every aired episode's file, in 1080p, so Neap and Slack Water (which Lakeside lacks) play from here.
const HL_ID = 5201
const HL_TMDB = 60_201
const HL_S3 = ['Low Water', 'The Pilot Boat', 'Breakwater', 'Fog Signal', 'Neap', 'Slack Water', 'The Long Dark', 'Ebb']
const hlEpisodeId = (season: number, n: number) => HL_ID * 10_000 + season * 100 + n

function cabinEpisode(season: number, n: number, title: string): Episode {
  const id = hlEpisodeId(season, n)
  const file = cabinFile(id * 10 + 1, `Harbor Lights/Season 0${season}/S0${season}E0${n}.mkv`, 49 * MIN, { libraryId: 12, parsed: { kind: 'episode', title: '' } })
  const w = cabinWatch.get(file.id)
  return {
    id, seasonId: HL_ID * 100 + season, number: n, title, overview: `Harbor Lights, season ${season} episode ${n}, on Cabin.`, hasFile: true, aired: true,
    airDate: day(-60 + n * 7), runtimeMin: 49, isNew: false, addedAt: ago(10 * 86_400_000), files: [file],
    watch: w ? { positionMs: w.positionMs, durationMs: 49 * MIN, completed: w.completed, updatedAt: w.updatedAt, fileId: file.id } : undefined,
  }
}

function cabinShowDetail(): ShowDetail {
  return {
    id: HL_ID, libraryId: 12, tmdbId: HL_TMDB, title: 'Harbor Lights', year: 2019, overview: 'A harbour pilot in a fading fishing town starts to suspect that the lights guiding ships home have been moved on purpose.',
    genres: ['Drama'], status: 'Returning Series', rating: 8.1, credits: [], hdr: false, episodeCount: HL_S3.length, totalEpisodes: HL_S3.length, fileCount: HL_S3.length, totalSize: 30 * GB,
    seasons: [{ id: HL_ID * 100 + 3, showId: HL_ID, number: 3, name: 'Season 3', overview: '', episodeCount: HL_S3.length, availableCount: HL_S3.length, watchedCount: 0 }],
  }
}

const cabinShowItem: BrowseItem = {
  kind: 'show', id: HL_ID, libraryId: 12, libraryType: 'tv', title: 'Harbor Lights', year: 2019, overview: 'A made-up series.', genres: ['Drama'], hdr: false, quality: '1080p',
  addedAt: ago(86_400_000), isNew: true, totalSize: 30 * GB, watched: false, episodeCount: HL_S3.length, tmdbId: HL_TMDB, status: 'Returning Series',
}

/** Cabin's own Continue watching: Dead Reckoning Bay further along than on Lakeside, and The Harbor Choir, which only Cabin has. */
function cabinContinue(): ContinueItem[] {
  return CABIN_MOVIES.filter((m) => cabinWatch.get(m.id * 10 + 1) && !cabinWatch.get(m.id * 10 + 1)!.completed).map((m) => {
    const w = cabinWatch.get(m.id * 10 + 1)!
    return { kind: 'movie' as const, title: m.title, subtitle: String(m.year), fileId: m.id * 10 + 1, movieId: m.id, positionMs: w.positionMs, durationMs: m.runtime * MIN, updatedAt: w.updatedAt, tmdbId: m.tmdbId, overview: m.overview }
  }).sort((a, b) => b.updatedAt.localeCompare(a.updatedAt))
}

function cabinHome(): HomeData {
  const added = [...CABIN_MOVIES].sort((a, b) => a.addedDaysAgo - b.addedDaysAgo).map(cabinItem)
  return {
    continueWatching: [], recentlyAddedShows: [], recentlyAired: [], recentlyAddedMovies: [], recentlyReleased: [],
    rows: [
      { id: 'continue', style: 'continue', title: 'Continue watching', continue: cabinContinue() },
      { id: 'recentlyAddedMovies', style: 'poster', title: 'Recently added · Movies', items: added },
      { id: 'recentlyAddedShows', style: 'poster', title: 'Recently added · TV Shows', items: [cabinShowItem] },
    ],
    totals: { movies: CABIN_MOVIES.length, shows: 1, episodes: HL_S3.length, files: CABIN_MOVIES.length + HL_S3.length, totalSize: 90 * GB },
    lastScannedAt: ago(40 * MIN), movieLibraryId: 11, tvLibraryId: 12,
  }
}

const has = (title: string, q: string) => title.toLowerCase().includes(q)

/** Cabin's routes, registered before the shared fakes so they answer first. */
export function registerCabinOneCard(server: FakeServer) {
  server.route('GET', '/api/home', () => cabinHome())
  server.route('GET', '/api/libraries', () => ({ items: [
    { id: 11, type: 'movie', name: 'Films', path: '/srv/media/films', fileCount: CABIN_MOVIES.length, missingCount: 0, pendingCount: 0, unmatchedCount: 0, createdAt: ago(400 * 86_400_000), updatedAt: ago(86_400_000) },
    { id: 12, type: 'tv', name: 'Series', path: '/srv/media/series', fileCount: HL_S3.length, missingCount: 0, pendingCount: 0, unmatchedCount: 0, createdAt: ago(400 * 86_400_000), updatedAt: ago(86_400_000) },
  ] }))
  server.route('GET', '/api/libraries/:id/items', ({ params, url }) => {
    const all = Number(params.id) === 11 ? CABIN_MOVIES.map(cabinItem) : [cabinShowItem]
    // Cabin answers level 34's filters, but its titles carry no rating, so a Rated filter leaves them all out.
    const items = (url.searchParams.get('contentRating') ? [] : all).filter((i) => url.searchParams.get('filter') !== 'unwatched' || !i.watched)
    const sort = url.searchParams.get('sort') ?? 'added'
    items.sort(sort === 'title' ? (a, b) => a.title.localeCompare(b.title) : (a, b) => b.addedAt.localeCompare(a.addedAt))
    const limit = Number(url.searchParams.get('limit') ?? 48), offset = Number(url.searchParams.get('offset') ?? 0)
    return { items: items.slice(offset, offset + limit), total: items.length, limit, offset, facets: { genres: ['Drama', 'Mystery', 'Thriller'], decades: [2020] },
      totals: { items: all.length, unwatched: all.filter((i) => !i.watched).length, episodes: HL_S3.length, continuing: 1, unmatched: 0, totalSize: 90 * GB } }
  })
  server.route('GET', '/api/movies', ({ url }) => {
    const tmdbId = Number(url.searchParams.get('tmdbId'))
    if (!tmdbId) return problem(422, 'tmdbId is required')
    return { items: CABIN_MOVIES.filter((m) => m.tmdbId === tmdbId).map((m) => asMovie(cabinItem(m))) }
  })
  server.route('GET', '/api/shows', ({ url }) => {
    const tmdbId = Number(url.searchParams.get('tmdbId'))
    if (!tmdbId) return problem(422, 'tmdbId is required')
    return { items: tmdbId === HL_TMDB ? [asShow(cabinShowItem)] : [] }
  })
  server.route('GET', '/api/movies/:id', ({ params }) => {
    const m = CABIN_MOVIES.find((x) => x.id === Number(params.id))
    return m ? cabinMovieDetail(m) : problem(404, 'no such movie')
  })
  server.route('GET', '/api/movies/:id/related', () => ({ rows: [] }))
  server.route('GET', '/api/shows/:id', ({ params }) => (Number(params.id) === HL_ID ? cabinShowDetail() : problem(404, 'no such show')))
  server.route('GET', '/api/shows/:id/related', () => ({ rows: [] }))
  server.route('GET', '/api/shows/:id/seasons/:number', ({ params }) => {
    if (Number(params.id) !== HL_ID || Number(params.number) !== 3) return problem(404, 'no such season')
    return { ...cabinShowDetail().seasons[0], show: asShow(cabinShowItem), episodes: HL_S3.map((t, i) => cabinEpisode(3, i + 1, t)) }
  })
  server.route('GET', '/api/search', ({ url }) => {
    const q = (url.searchParams.get('q') ?? '').trim().toLowerCase()
    if (!q) return problem(422, 'q is required')
    const episodes = HL_S3.map((t, i) => ({ t, i })).filter(({ t }) => has(t, q)).map(({ t, i }) => ({
      id: hlEpisodeId(3, i + 1), showId: HL_ID, showTitle: 'Harbor Lights', showTmdbId: HL_TMDB, seasonNumber: 3, number: i + 1, title: t, fileId: hlEpisodeId(3, i + 1) * 10 + 1,
    }))
    return {
      movies: CABIN_MOVIES.filter((m) => has(m.title, q)).map((m) => asMovie(cabinItem(m))),
      shows: has('harbor lights', q) ? [asShow(cabinShowItem)] : [],
      episodes,
    }
  })
  // What played here is kept here: Continue watching and the pages show it.
  server.route('PUT', '/api/files/:id/progress', ({ params, body }) => {
    const b = body as { playId?: string; positionMs: number; durationMs: number }
    const completed = b.positionMs >= b.durationMs * .9
    cabinWatch.set(Number(params.id), { positionMs: b.positionMs, completed, updatedAt: new Date().toISOString() })
    return { completed, positionMs: b.positionMs, durationMs: b.durationMs, updatedAt: new Date().toISOString(), playId: b.playId }
  })
  server.route('PUT', '/api/watch/:type/:id/hidden', ({ params }) => {
    if (params.type === 'movie') cabinWatch.delete(Number(params.id) * 10 + 1)
    return new Response(null, { status: 204 })
  })
}

/** Den: a server that takes the request and never answers. */
export function silentServer(identity: FakeIdentity): Pick<FakeServer, 'origin' | 'handle'> {
  return { origin: identity.origin, handle: () => new Promise<Response>(() => { /* Never answers. */ }) }
}
