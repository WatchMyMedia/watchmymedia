import { describe, expect, it } from 'vitest'
import type { components } from '../../../src/api/schema'
import type { ScreenRow } from '../../../src/lib/screenRows'
import { keepOrder, mergeContinue, mergeEpisodes, mergeHomeRows, mergeItems, oneCardWords, rowOrder, type PeerHome } from './oneCardHome'

type ContinueItem = components['schemas']['ContinueItem']
type BrowseItem = components['schemas']['BrowseItem']
type HomeData = components['schemas']['HomeData']

const cabin = { id: 'cabin', name: 'Cabin' }
const den = { id: 'den', name: 'Den server' }
const at = (h: number) => new Date(Date.UTC(2026, 10, 23, h)).toISOString()
const movie = (fileId: number, movieId: number, tmdbId: number, h: number, positionMs = 60_000): ContinueItem => ({ kind: 'movie', title: `M${tmdbId}`, fileId, movieId, tmdbId, positionMs, durationMs: 600_000, updatedAt: at(h) })
const episode = (fileId: number, episodeId: number, tmdbId: number, h: number): ContinueItem => ({ kind: 'episode', title: `S${tmdbId}`, fileId, episodeId, showId: 1, tmdbId, seasonNumber: 1, episodeNumber: 2, positionMs: 1, durationMs: 2, updatedAt: at(h) })
const item = (id: number, tmdbId: number | undefined, addedH: number): BrowseItem => ({ kind: 'movie', id, tmdbId, title: `I${id}`, libraryId: 1, genres: [], hdr: false, isNew: false, overview: '', totalSize: 0, watched: false, addedAt: at(addedH) })

describe('Continue watching from every server', () => {
  it('keeps one card a title, at its latest place, newest first, and says where it plays from', () => {
    const merged = mergeContinue(
      [movie(1, 10, 500, 3), episode(2, 20, 600, 1)], 'home',
      [{ server: cabin, items: [movie(51, 510, 500, 5, 90_000), movie(52, 520, 700, 4)] }, { server: den, items: [episode(62, 620, 600, 2)] }],
    )
    expect(merged.map((c) => [c.title, c.fileId, c.oneCard?.server.id, c.oneCard?.where])).toEqual([
      ['M500', 51, 'cabin', 'from'], ['M700', 52, 'cabin', 'only'], ['S600', 62, 'den', 'from'],
    ])
    expect(merged[0].positionMs).toBe(90_000)
    // Remove takes the card off every server that has it.
    expect(merged[0].copies).toEqual([{ server: 'home', target: { type: 'movie', id: 10 } }, { server: 'cabin', target: { type: 'movie', id: 510 } }])
    expect(merged[1].copies).toBeUndefined()
  })

  it('plays the copy the person chose, from the latest place on any server', () => {
    const merged = mergeContinue([movie(1, 10, 500, 3, 30_000)], 'home', [{ server: cabin, items: [movie(51, 510, 500, 5, 90_000)] }], () => ({ server: 'home', file: 1 }))
    expect(merged[0]).toMatchObject({ fileId: 1, positionMs: 90_000 })
    expect(merged[0].oneCard).toBeUndefined()
  })

  it('never merges a title without a TMDB id', () => {
    const merged = mergeContinue([{ ...movie(1, 10, 0, 3), tmdbId: undefined }], 'home', [{ server: cabin, items: [{ ...movie(51, 510, 0, 5), tmdbId: undefined }] }])
    expect(merged).toHaveLength(2)
  })
})

describe('rows built the same way on each server', () => {
  it('fill from every server, one card a title, newest first', () => {
    const merged = mergeItems('recentlyAddedMovies', [item(1, 500, 2), item(2, 501, 6)], 'home', [{ server: cabin, items: [item(51, 500, 9), item(52, 502, 4), item(53, undefined, 1)] }])
    expect(merged.map((i) => [i.id, i.oneCard?.server.id])).toEqual([[2, undefined], [52, 'cabin'], [1, undefined], [53, 'cabin']])
  })

  it('fill episodes by show, season and number', () => {
    const e = (episodeId: number, showTmdbId: number, h: number) => ({ episodeId, showId: 1, showTitle: 'X', showTmdbId, seasonNumber: 1, episodeNumber: 3, title: '', overview: '', hasFile: true, isNew: false, airDate: at(h) })
    const merged = mergeEpisodes([e(1, 600, 2)], 'home', [{ server: cabin, items: [e(51, 600, 5), e(52, 601, 4)] }])
    expect(merged.map((x) => x.episodeId)).toEqual([52, 1])
  })

  it('never move once on screen: a late server’s cards join the end', () => {
    expect(keepOrder(['b', 'a'], ['a', 'c', 'b'], (x) => x)).toEqual(['b', 'a', 'c'])
    expect(keepOrder(undefined, ['a', 'c'], (x) => x)).toEqual(['a', 'c'])
  })

  it('leave the current server’s other rows and order alone', () => {
    const rows: ScreenRow[] = [
      { id: 'continue', style: 'continue', title: 'Continue watching', continue: [movie(1, 10, 500, 3)] },
      { id: 'list:3', style: 'poster', title: 'A list', items: [item(9, 900, 1)] },
      { id: 'recentlyAddedMovies', style: 'poster', title: 'Recently added', items: [item(1, 500, 2)] },
    ]
    const peer: PeerHome = { server: cabin, home: { continueWatching: [], recentlyAddedShows: [], recentlyAired: [], recentlyAddedMovies: [], recentlyReleased: [], totals: { movies: 0, shows: 0, episodes: 0, files: 0, totalSize: 0 },
      rows: [{ id: 'recentlyAddedMovies', style: 'poster', title: 'Recently added', items: [item(51, 777, 9)] }, { id: 'list:3', style: 'poster', title: 'Theirs', items: [item(52, 778, 9)] }] } as HomeData }
    const merged = mergeHomeRows(rows, 'home', [peer], new Map([['recentlyAddedMovies', rowOrder(rows[2], 'home')!]]))
    expect(merged.map((r) => r.id)).toEqual(['continue', 'list:3', 'recentlyAddedMovies'])
    expect(merged[1]).toBe(rows[1])
    // Seen before Cabin answered: Cabin's newer title joins at the end.
    expect(merged[2].items?.map((i) => i.id)).toEqual([1, 51])
    expect(mergeHomeRows(rows, 'home', [peer], new Map())[2].items?.map((i) => i.id)).toEqual([51, 1])
  })

  it('words where a card’s title is', () => {
    const bare = (n: string) => n.replace(/ server$/, '')
    expect(oneCardWords({ server: cabin, where: 'only' }, bare)).toBe('On Cabin')
    expect(oneCardWords({ server: den, where: 'from' }, bare)).toBe('from Den')
    expect(oneCardWords(undefined, bare)).toBeUndefined()
  })
})
