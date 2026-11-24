import type { components } from '../../src/api/schema'

// The fakes' fictional household library. Every title, name and server is
// made up. A title's TMDB id is 50 000 plus its id for a movie and 60 000
// plus it for a show. There are no pictures (no posterPath): cards draw their
// placeholders, and the preview fetches nothing from the network.

type BrowseItem = components['schemas']['BrowseItem']
type ContinueItem = components['schemas']['ContinueItem']
type EpisodeCard = components['schemas']['EpisodeCard']
type Library = components['schemas']['Library']

export const FAKE_USER = { id: 1, name: 'Sam', isAdmin: true }

const at = (daysAgo: number) => new Date(Date.UTC(2026, 10, 18) - daysAgo * 86_400_000).toISOString()

export const LIBRARIES: Library[] = [
  { id: 1, type: 'movie', name: 'Movies', path: '/media/movies', fileCount: 14, missingCount: 0, pendingCount: 0, unmatchedCount: 0, createdAt: at(400), updatedAt: at(1) },
  { id: 2, type: 'tv', name: 'TV Shows', path: '/media/tv', fileCount: 96, missingCount: 0, pendingCount: 0, unmatchedCount: 0, createdAt: at(400), updatedAt: at(1) },
]

const MOVIES = ['The Paper Kite', 'Dead Reckoning Bay', 'Glass Orchard', 'Lantern Street', 'The Last Ferry', 'Cinder Valley', 'Ember and Oak',
  'Low Tide Club', 'Signal Garden', 'Northbound Freight', 'A Map of Small Rooms', 'The Fourth Lighthouse', 'Paper Moons', 'Winter Marsh']
const SHOWS = ['Harbor Lights', 'Copperline', 'Northfield Precinct', 'The Quiet Meridian', 'Saltmarsh', 'Kestrel House', 'Driftwood', 'Moonrise Diner']
const GENRES = [['Drama'], ['Thriller', 'Mystery'], ['Comedy'], ['Drama', 'Romance'], ['Adventure'], ['Science Fiction'], ['Documentary']]

export const MOVIE_ITEMS: BrowseItem[] = MOVIES.map((title, i) => ({
  kind: 'movie', id: 101 + i, libraryId: 1, libraryType: 'movie', title, year: 2025 - (i % 9), overview: `A made-up film for the TV app's fakes (${i + 1}).`,
  genres: GENRES[i % GENRES.length], hdr: i % 4 === 0, addedAt: at(i * 3), isNew: i < 3, totalSize: 4_000_000_000 + i * 1e8, watched: i % 5 === 4,
  runtimeMin: 88 + (i * 7) % 60, rating: 6 + (i % 4) * .5, tmdbId: 50_101 + i,
}))

export const SHOW_ITEMS: BrowseItem[] = SHOWS.map((title, i) => ({
  kind: 'show', id: 201 + i, libraryId: 2, libraryType: 'tv', title, year: 2019 + (i % 7), overview: `A made-up series for the TV app's fakes (${i + 1}).`,
  genres: GENRES[(i + 2) % GENRES.length], hdr: false, addedAt: at(i * 2), isNew: i < 2, totalSize: 30_000_000_000, watched: false,
  episodeCount: 8 + i * 2, status: i % 3 === 0 ? 'Ended' : 'Returning Series', tmdbId: 60_201 + i,
}))

// As the server words them: an episode's code and title, a movie's year. An episode's id is its show's, season and number as the pages number them.
export const CONTINUE: ContinueItem[] = [
  { kind: 'episode', title: 'The Quiet Meridian', subtitle: 'S02E04 The Undertow', fileId: 3104, showId: 204, episodeId: 2_040_204, seasonNumber: 2, episodeNumber: 4, positionMs: 28 * 60_000, durationMs: 51 * 60_000, updatedAt: at(0), tmdbId: 60_204 },
  { kind: 'movie', title: 'Dead Reckoning Bay', subtitle: '2026', fileId: 1102, movieId: 102, positionMs: 32 * 60_000, durationMs: 104 * 60_000, updatedAt: at(1), tmdbId: 50_102 },
  { kind: 'episode', title: 'Saltmarsh', subtitle: 'S01E09 Low Water', fileId: 3509, showId: 205, episodeId: 2_050_109, seasonNumber: 1, episodeNumber: 9, positionMs: 40 * 60_000, durationMs: 48 * 60_000, updatedAt: at(2), tmdbId: 60_205 },
  { kind: 'episode', title: 'Copperline', subtitle: 'S04E01 Night Shift', fileId: 3201, showId: 202, episodeId: 2_020_401, seasonNumber: 4, episodeNumber: 1, positionMs: 6 * 60_000, durationMs: 47 * 60_000, updatedAt: at(3), tmdbId: 60_202 },
  { kind: 'movie', title: 'The Paper Kite', subtitle: '2024', fileId: 1101, movieId: 101, positionMs: 66 * 60_000, durationMs: 100 * 60_000, updatedAt: at(4), tmdbId: 50_101 },
  { kind: 'episode', title: 'Northfield Precinct', subtitle: 'S01E02 Old Roads', fileId: 3302, showId: 203, episodeId: 2_030_102, seasonNumber: 1, episodeNumber: 2, positionMs: 3 * 60_000, durationMs: 53 * 60_000, updatedAt: at(5), tmdbId: 60_203 },
]

/** What Remove from Continue watching took off the row, by "movie:<id>" or "episode:<id>", for the visit. */
export const HIDDEN = new Set<string>()

/** Continue watching as Home shows it: without what was removed. */
export function continueWatching(): ContinueItem[] {
  return CONTINUE.filter((c) => !HIDDEN.has(c.kind === 'movie' ? `movie:${c.movieId}` : `episode:${c.episodeId}`))
}

export const AIRED: EpisodeCard[] = SHOWS.slice(0, 6).map((showTitle, i) => ({
  episodeId: 5000 + i, showId: 201 + i, showTitle, seasonNumber: 1 + (i % 3), episodeNumber: 3 + i, title: ['The Long Dark', 'Brass Tacks', 'Night Shift', 'Undertow', 'Old Roads', 'Second Harbour'][i],
  overview: 'A made-up episode.', hasFile: true, isNew: i < 2, airDate: at(i), fileId: 6000 + i,
}))
