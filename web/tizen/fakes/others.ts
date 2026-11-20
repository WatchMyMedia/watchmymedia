import type { components } from '../../src/api/schema'
import { FakeServer, problem } from './server'
import { MOVIE_ITEMS, SHOW_ITEMS } from './data'

// The preview's other servers (?fakes=servers), so Servers, a switch and
// Search across servers can be tried without a network. Cabin answers and
// has titles of its own, some sharing a word with Lakeside's, so a search
// finds both and a card can be told apart by its server. Den never
// answers. Attic answers, but this TV is signed out of it. Every name and
// title is made up, and none of them is on the network.

type BrowseItem = components['schemas']['BrowseItem']
type HomeData = components['schemas']['HomeData']
type Movie = components['schemas']['Movie']
type Show = components['schemas']['Show']
type SearchEpisode = components['schemas']['SearchEpisode']

export type FakeIdentity = { origin: string; id: string; name: string }

export const LAKESIDE: FakeIdentity = { origin: 'http://lakeside.local:27587', id: 'fake-lakeside', name: 'Lakeside' }
export const CABIN: FakeIdentity = { origin: 'http://cabin.local:27587', id: 'fake-cabin', name: 'Cabin' }
/** Remembered and signed in, but nothing answers at its address. */
export const DEN: FakeIdentity = { origin: 'http://den.local:27587', id: 'fake-den', name: 'Den' }
export const ATTIC: FakeIdentity = { origin: 'http://attic.local:27587', id: 'fake-attic', name: 'Attic' }

/** The sign-ins the preview starts with on Cabin and Den, as if this TV had signed in to them before. */
export const CABIN_TOKEN = 'fake-cabin-session'
export const DEN_TOKEN = 'fake-den-session'

// Cabin's titles sit on the fakes' own pages (an id's last three digits pick the page), under their own names.
const movie = (id: number, title: string, year: number): BrowseItem => ({ ...MOVIE_ITEMS[(id % 1000) - 101], id, title, year, isNew: false, watched: false })
const show = (id: number, title: string, year: number): BrowseItem => ({ ...SHOW_ITEMS[(id % 1000) - 201], id, title, year, isNew: false })
const CABIN_MOVIES = [movie(5103, 'Harbor at Dusk', 2011), movie(5104, 'Harbour Run', 2019), movie(5105, 'The Portage Club', 2022)]
const CABIN_SHOWS = [show(5201, 'Harborside', 2020), show(5202, 'Safe Harbour', 2023), show(5203, 'Tidewater Road', 2021), show(5204, 'Kettle Pond', 2018)]

/** Cabin's names for the pages it borrows, by id. */
export const CABIN_TITLES: ReadonlyMap<number, string> = new Map([...CABIN_MOVIES, ...CABIN_SHOWS].map((i) => [i.id, i.title]))

const has = (title: string, q: string) => title.toLowerCase().includes(q)

/** Cabin's own search and Home, registered before the shared fakes so they answer first. */
export function registerCabin(server: FakeServer) {
  const movies: Movie[] = CABIN_MOVIES.map((m) => ({ id: m.id, libraryId: m.libraryId, tmdbId: 9000 + m.id, title: m.title, overview: m.overview, genres: m.genres, fileCount: 1, credits: [], year: m.year, runtimeMin: m.runtimeMin }))
  const shows: Show[] = CABIN_SHOWS.map((s) => ({ id: s.id, libraryId: s.libraryId, tmdbId: 9000 + s.id, title: s.title, overview: s.overview, genres: s.genres, episodeCount: s.episodeCount ?? 10, credits: [], year: s.year, status: s.status }))
  // Harborside's season 2 episode 7, as its page lists it.
  const episodes: SearchEpisode[] = [{ id: 5201 * 10_000 + 207, showId: 5201, showTitle: 'Harborside', seasonNumber: 2, number: 7, title: 'Harbour Master', fileId: (5201 * 10_000 + 207) * 10 + 1 }]
  server.route('GET', '/api/search', ({ url }) => {
    const q = (url.searchParams.get('q') ?? '').trim().toLowerCase()
    if (!q) return problem(422, 'q is required')
    return { movies: movies.filter((m) => has(m.title, q)), shows: shows.filter((s) => has(s.title, q)), episodes: episodes.filter((e) => has(e.title, q)) }
  })
  const home: HomeData = {
    continueWatching: [], recentlyAddedShows: [], recentlyAired: [], recentlyAddedMovies: [], recentlyReleased: [],
    rows: [
      { id: 'continue', style: 'continue', title: 'Continue watching', continue: [
        { kind: 'movie', title: 'Harbour Run', subtitle: '52 min left', fileId: 51041, movieId: 5104, positionMs: 48 * 60_000, durationMs: 100 * 60_000, updatedAt: new Date().toISOString() },
        { kind: 'episode', title: 'Safe Harbour', subtitle: 'S02 E02 · 19 min left', fileId: 52020202, showId: 5202, seasonNumber: 2, episodeNumber: 2, positionMs: 30 * 60_000, durationMs: 49 * 60_000, updatedAt: new Date().toISOString() },
      ] },
      { id: 'recentlyAddedMovies', style: 'poster', title: 'Recently added · Movies', items: CABIN_MOVIES },
      { id: 'recentlyAddedShows', style: 'poster', title: 'Recently added · TV Shows', items: CABIN_SHOWS },
    ],
    hero: { kind: 'episode', title: 'Safe Harbour', kicker: 'New episode · added today', facts: 'S02 E03 · Slack Water · 47 min', contentRating: 'TV-PG', quality: '1080p', hdr: false,
      overview: 'The lifeboat station takes on a volunteer who has never been to sea, the week the autumn gales arrive early.', showId: 5202, seasonNumber: 2 },
    totals: { movies: CABIN_MOVIES.length, shows: CABIN_SHOWS.length, episodes: 44, files: 47, totalSize: 210 * 1024 ** 3 },
    lastScannedAt: new Date(Date.now() - 40 * 60_000).toISOString(), movieLibraryId: 1, tvLibraryId: 2,
  }
  server.route('GET', '/api/home', () => home)
}
