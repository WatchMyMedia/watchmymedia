import { describe, expect, it } from 'vitest'
import type { components } from '../../../src/api/schema'
import { keyRows, MAX_QUERY, typeKey } from './keyboard'
import { bestMatch, continueItems, emptyWords, kindLine, liveAction, liveWhen, movieSub, noMatchWords, notInLibrary, placeIn, requestableAction, requestBadge, showSub } from './searchResults'

type Hit = components['schemas']['LiveTVSearchHit']

describe('Search’s keyboard', () => {
  it('has four rows of ten, in capitals with Shift', () => {
    const rows = keyRows('letters', false)
    expect(rows.map((r) => r.length)).toEqual([10, 10, 10, 10])
    expect(rows[1].join('')).toBe('qwertyuiop')
    expect(keyRows('letters', true)[1][0]).toBe('Q')
    expect(keyRows('symbols', true)[1][0]).toBe('!')
  })

  it('types, spaces, deletes and clears', () => {
    expect(typeKey('har', { kind: 'char', char: 'b' })).toBe('harb')
    expect(typeKey('', { kind: 'space' })).toBe('')
    expect(typeKey('harbor ', { kind: 'space' })).toBe('harbor ')
    expect(typeKey('café', { kind: 'delete' })).toBe('caf')
    expect(typeKey('harbor', { kind: 'clear' })).toBe('')
    expect(typeKey('x'.repeat(MAX_QUERY), { kind: 'char', char: 'y' })).toHaveLength(MAX_QUERY)
    expect(typeKey('x'.repeat(60), { kind: 'char', char: 'y' }, 60)).toHaveLength(60)
  })
})

describe('Search’s results', () => {
  it('picks a best match from this server’s titles: the same title, then one starting with it, then a word', () => {
    const shows = [{ item: { id: 1, title: 'Safe Harbor Road' } }, { item: { id: 2, title: 'Harbor Lights' } }]
    const movies = [{ item: { id: 3, title: 'The Harbor Choir' } }, { item: { id: 4, title: 'Harbor' }, server: { id: 'cabin', name: 'Cabin' } }]
    expect(bestMatch(shows, movies, 'harb')).toEqual({ kind: 'show', item: { id: 2, title: 'Harbor Lights' } })
    expect(bestMatch(shows, [{ item: { id: 5, title: 'harbor lights' } }], 'Harbor Lights')).toEqual({ kind: 'show', item: { id: 2, title: 'Harbor Lights' } })
    expect(bestMatch([], movies, 'choir')).toEqual({ kind: 'movie', item: { id: 3, title: 'The Harbor Choir' } })
    // Another server's title is never the best match.
    expect(bestMatch([], [movies[1]], 'harbor')).toBeUndefined()
  })

  it('finds your place in the best match from Continue watching', () => {
    const home = { rows: [{ id: 'continue', style: 'continue', title: 'Continue watching', continue: [
      { kind: 'episode', showId: 2, seasonNumber: 3, episodeNumber: 7, fileId: 9, positionMs: 18 * 60_000, durationMs: 47 * 60_000, title: 'Harbor Lights', updatedAt: '' },
      { kind: 'movie', movieId: 3, fileId: 8, positionMs: 60_000, durationMs: 73 * 60_000, title: 'The Harbor Choir', updatedAt: '' },
    ] }] } as never
    const items = continueItems(home)
    expect(placeIn({ kind: 'show', item: { id: 2 } }, items)?.line).toBe('You are on S3 E7 · 29 min left')
    expect(placeIn({ kind: 'movie', item: { id: 3 } }, items)?.line).toBe('You are partway through · 1 h 12 min left')
    expect(placeIn({ kind: 'movie', item: { id: 2 } }, items)).toBeUndefined()
    expect(continueItems(undefined)).toEqual([])
  })

  it('words the cards and the empty and nothing-found states', () => {
    expect(movieSub({ year: 2018, runtimeMin: 112 })).toBe('2018 · 1 h 52 min')
    expect(showSub({ year: 2022, status: 'Returning Series', episodeCount: 24 })).toBe('2022– · 24 episodes')
    expect(showSub({ year: 2019, status: 'Ended', episodeCount: 1 })).toBe('2019 · 1 episode')
    expect(kindLine('movie', { year: 2018 })).toBe('Movie · 2018')
    expect(kindLine('show', { year: 2021, episodeCount: 24 })).toBe('TV show · 24 episodes')
    expect(emptyWords('Lakeside', 1, true).body).toBe('Movies, shows and episodes on Lakeside, and what is on live TV.')
    expect(emptyWords('Lakeside', 3, false).body).toBe('Movies, shows and episodes on your 3 servers.')
    expect(noMatchWords('iron tide', 1, true)).toEqual({ title: 'Nothing in your library matches “iron tide”', body: 'Nothing on live TV either.' })
    expect(noMatchWords('iron tide', 2, false)).toEqual({ title: 'Nothing on your 2 servers matches “iron tide”', body: undefined })
  })

  it('leaves out of Not in your library what the library’s results have', () => {
    const items = [{ mediaType: 'tv', tmdbId: 1, title: 'A', overview: '', state: 'available', showId: 5 }, { mediaType: 'movie', tmdbId: 2, title: 'B', overview: '', state: 'requestable' }] as never
    expect(notInLibrary(items, { shows: [{ id: 5 }], movies: [] } as never).map((r) => r.title)).toEqual(['B'])
    expect(requestBadge('downloading')).toMatchObject({ label: 'Downloading', gold: true })
    expect(requestableAction({ state: 'requestable' })).toEqual({ kind: 'request' })
    expect(requestableAction({ state: 'available', movieId: 7 })).toEqual({ kind: 'movie', id: 7 })
    expect(requestableAction({ state: 'requested' })).toBeUndefined()
  })

  it('watches what is on now on Live TV, and opens a later one in the guide', () => {
    const now = new Date(2026, 10, 19, 20, 27).getTime()
    const at = (h: number, m = 0) => new Date(2026, 10, 19, h, m).toISOString()
    const channel = { id: 'c', sourceId: 's', number: '7.1', name: 'LKSD', enabled: true, available: true }
    const on: Hit = { channel, programme: { id: 'p', title: 'T', start: at(20), end: at(21) } }
    const later: Hit = { channel, programme: { id: 'q', title: 'U', start: at(21), end: at(22), reminder: true } }
    expect(liveWhen(on, now, 'en-US')).toBe('Now on 7.1 · 33 min left')
    expect(liveWhen(later, now, 'en-US')).toBe('9:00 PM on 7.1 · Reminder set')
    expect(liveAction(on, now)).toEqual({ kind: 'watch', channelId: 'c' })
    expect(liveAction(later, now)).toEqual({ kind: 'guide', channelId: 'c', at: Date.parse(at(21)) })
    expect(liveAction({ ...on, channel: { ...channel, available: false } }, now).kind).toBe('guide')
  })
})
