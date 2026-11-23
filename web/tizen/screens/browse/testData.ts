import type { components } from '../../../src/api/schema'
import type { LiveChannel } from '../../../src/api/liveTV'
import type { ScreenRow } from '../../../src/lib/screenRows'

// Small builders for the browsing screens' tests. Every title is made up.

type BrowseItem = components['schemas']['BrowseItem']
type ContinueItem = components['schemas']['ContinueItem']
type EpisodeCard = components['schemas']['EpisodeCard']
type HomeData = components['schemas']['HomeData']

export const item = (over: Partial<BrowseItem> = {}): BrowseItem => ({
  id: 1, kind: 'movie', title: 'Glass Orchard', libraryId: 1, addedAt: new Date(Date.now() - 86_400_000).toISOString(), genres: [], hdr: false,
  isNew: false, overview: '', totalSize: 0, watched: false, ...over,
})

export const resume = (over: Partial<ContinueItem> = {}): ContinueItem => ({
  kind: 'episode', title: 'Saltmarsh', subtitle: 'S01E09 Low Water', seasonNumber: 1, episodeNumber: 9, fileId: 9, showId: 5, positionMs: 40 * 60_000, durationMs: 48 * 60_000,
  updatedAt: new Date().toISOString(), ...over,
})

export const episode = (over: Partial<EpisodeCard> = {}): EpisodeCard => ({
  episodeId: 70, showId: 7, showTitle: 'Harbor Lights', seasonNumber: 3, episodeNumber: 7, title: 'The Long Dark', overview: 'A storm.', hasFile: true, isNew: false, fileId: 700, ...over,
})

export const row = (over: Partial<ScreenRow> = {}): ScreenRow => ({ id: 'recentlyAddedMovies', style: 'poster', title: 'Recently added · Movies', items: [item()], ...over })

export const home = (over: Partial<HomeData> = {}): HomeData => ({
  continueWatching: [], recentlyAddedShows: [], recentlyAired: [], recentlyAddedMovies: [], recentlyReleased: [], rows: [],
  totals: { movies: 0, shows: 0, episodes: 0, files: 0, totalSize: 0 }, ...over,
})

export const channel = (over: Partial<LiveChannel> = {}): LiveChannel => ({ id: 'c7', number: '7.1', name: 'KLAK', available: true, enabled: true, sourceId: 's', ...over })

export const libraries = [{ id: 1, type: 'movie' }, { id: 2, type: 'tv' }, { id: 3, type: 'movie' }]
