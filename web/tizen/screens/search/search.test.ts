import { describe, expect, it } from 'vitest'
import type { components } from '../../../src/api/schema'
import { keyRows, MAX_QUERY, typeKey } from './keyboard'
import { highlight, libraryBand, liveAction, liveWhen, movieSub, notInLibrary, requestableAction, requestBadge, showSub, suggestionChips } from './searchResults'

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
  it('suggests titles with what was typed in bold', () => {
    expect(highlight('Harbor Lights', 'harb')).toEqual(['', 'Harb', 'or Lights'])
    expect(highlight('The Harbor Choir', 'harb')).toEqual(['The ', 'Harb', 'or Choir'])
    expect(highlight('Lights', 'harb')).toEqual(['Lights', '', ''])
    const results = { movies: [{ id: 1, title: 'The Harbor Choir' }, { id: 2, title: 'Harbor at Dusk' }], shows: [{ id: 3, title: 'Harbor Lights' }], episodes: [{ id: 9, title: 'Harbor Night', showId: 3, showTitle: 'x', seasonNumber: 1, number: 1, fileId: 1 }] } as never
    expect(suggestionChips(results, 'harb').map((c) => c.title)).toEqual(['Harbor at Dusk', 'Harbor Lights', 'The Harbor Choir'])
  })

  it('words the cards', () => {
    expect(movieSub({ year: 2018, runtimeMin: 112 })).toBe('2018 · 1 h 52 min')
    expect(showSub({ year: 2022, status: 'Returning Series', episodeCount: 24 })).toBe('2022– · 24 episodes')
    expect(showSub({ year: 2019, status: 'Ended', episodeCount: 1 })).toBe('2019 · 1 episode')
    expect(libraryBand(1, 2)).toBe('band')
    expect(libraryBand(3, 3)).toBe('rows')
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
