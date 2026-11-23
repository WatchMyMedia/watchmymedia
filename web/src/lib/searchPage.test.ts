import { describe, expect, it } from 'vitest'
import { bestMatch, episodeRowWords, notInLibrary, requestableLine, requestableState, resultsWords, searchChips, searchShowLine, sectionShown } from './searchPage'
import type { components } from '../api/schema'

type Movie = components['schemas']['Movie']
type Show = components['schemas']['Show']
type Requestable = components['schemas']['Requestable']

const movie = (id: number, title: string, over: Partial<Movie> = {}) => ({ id, title, ...over }) as Movie
const show = (id: number, title: string, over: Partial<Show> = {}) => ({ id, title, ...over }) as Show

describe('the best match', () => {
  it('is the title that matches the words best', () => {
    const r = { movies: [movie(1, 'Cold Harbor Road'), movie(2, 'Harbor Noir')], shows: [show(3, 'Glass Harbor')] }
    expect(bestMatch(r, 'harbor noir')).toEqual({ kind: 'movie', item: r.movies[1] })
    expect(bestMatch(r, 'harb')).toEqual({ kind: 'movie', item: r.movies[1] })
  })

  it('prefers a show on a tie', () => {
    const r = { movies: [movie(1, 'Harbor Lights')], shows: [show(3, 'Harbor Lights')] }
    expect(bestMatch(r, 'harbor lights')?.kind).toBe('show')
  })

  it('is nothing when the words are only inside a word or not in a title', () => {
    expect(bestMatch({ movies: [movie(1, 'Unharbored')], shows: [] }, 'harbor')).toBeUndefined()
    expect(bestMatch({ movies: [], shows: [] }, 'harbor')).toBeUndefined()
  })
})

describe('the chips', () => {
  it('count each section and leave out empty ones', () => {
    const chips = searchChips({ movies: 3, shows: 2, episodes: 0, live: 2, requests: 1 })
    expect(chips.map((c) => [c.label, c.count])).toEqual([
      ['All', 8],
      ['Movies', 3],
      ['Shows', 2],
      ['Live TV', 2],
      ['Not in your library', 1],
    ])
    expect(sectionShown(undefined, 'movies')).toBe(true)
    expect(sectionShown('shows', 'movies')).toBe(false)
    expect(resultsWords(1)).toBe('1 result')
    expect(resultsWords(14)).toBe('14 results')
  })
})

describe('result lines', () => {
  it('says what a show holds and where it stands', () => {
    const now = Date.parse('2026-11-23T12:00:00Z')
    expect(searchShowLine({ episodeCount: 24, status: 'Ended' }, now)).toBe('24 episodes · Ended')
    expect(searchShowLine({ year: 2020, episodeCount: 0 }, now)).toBe('2020')
  })

  it('words an episode row', () => {
    const w = episodeRowWords({ showTitle: 'Saltmarsh', seasonNumber: 1, number: 3, title: 'Safe Harbor', airDate: '2025-09-12', watched: true })
    expect(w.title).toBe('Saltmarsh · S1 E3 · Safe Harbor')
    expect(w.line).toMatch(/^Aired .*2025 · Watched$/)
    expect(w.label).toBe('Saltmarsh, season 1 episode 3, Safe Harbor')
  })
})

describe('not in your library', () => {
  const r = (over: Partial<Requestable>) => ({ mediaType: 'movie', tmdbId: 1, title: 'X', state: 'requestable', ...over }) as Requestable
  it('leaves out what the server has and kinds that can’t be requested', () => {
    const items = [r({ title: 'A' }), r({ title: 'B', state: 'available' }), r({ title: 'C', mediaType: 'tv' })]
    expect(notInLibrary(items, { movies: true, shows: false }).map((i) => i.title)).toEqual(['A'])
  })

  it('says what each is and where its request stands', () => {
    expect(requestableLine({ year: 2024, mediaType: 'movie' })).toBe('2024 · Movie')
    expect(requestableLine({ mediaType: 'tv' })).toBe('Show')
    expect(requestableState({ state: 'requestable' })).toBeUndefined()
    expect(requestableState({ state: 'downloading' })).toEqual({ label: 'Downloading', gold: true })
    expect(requestableState({ state: 'requested', request: { createdAt: '2026-11-22T10:00:00Z' } as never })?.label).toMatch(/^Requested Nov 2[12], 2026$/)
  })
})
