import { describe, expect, it } from 'vitest'
import type { components } from '../../../src/api/schema'
import { before, mergedCounts, mergeStreams, type GridServer, type Stream } from './mergedGrid'

type BrowseItem = components['schemas']['BrowseItem']

const home: GridServer = { id: 'home', name: 'Home', current: true }
const cabin: GridServer = { id: 'cabin', name: 'Cabin', current: false }
const at = (d: number) => new Date(Date.UTC(2026, 10, d)).toISOString()
const item = (id: number, tmdbId: number | undefined, day: number, title = `T${id}`): BrowseItem => ({ kind: 'movie', id, tmdbId, title, libraryId: 1, genres: [], hdr: false, isNew: false, overview: '', totalSize: 0, watched: false, addedAt: at(day) })
const totals = (items: number, unwatched?: number) => ({ items, unmatched: 0, totalSize: 0, continuing: 0, episodes: 0, ...(unwatched !== undefined ? { unwatched } : {}) })

describe('a library from every server', () => {
  it('sorts as the server does', () => {
    expect(before('added', item(1, 1, 20), item(2, 2, 10))).toBe(true)
    expect(before('title', item(1, 1, 1, 'apple'), item(2, 2, 1, 'Banana'))).toBe(true)
  })

  it('merges the pages in the sort, one card a title, and stops where a server needs its next page', () => {
    const streams: Stream[] = [
      { server: home, items: [item(1, 500, 22), item(2, 501, 18)], total: 4, done: false },
      { server: cabin, items: [item(51, 502, 21), item(52, 500, 20), item(53, 503, 19)], total: 3, done: true },
    ]
    const m = mergeStreams(streams, 'added')
    // Home's next page may hold something added before day 18: the merge waits for it there.
    expect(m.items.map((i) => [i.id, i.oneCard?.server.id])).toEqual([[1, undefined], [51, 'cabin'], [53, 'cabin'], [2, undefined]])
    expect(m.needs).toEqual(['home'])
    expect(m.shared).toBe(1)
  })

  it('counts a title watched on any server as watched', () => {
    const m = mergeStreams([
      { server: home, items: [item(1, 500, 10)], total: 1, done: true },
      { server: cabin, items: [{ ...item(51, 500, 20), watched: true }], total: 1, done: true },
    ], 'added')
    expect(m.items.map((i) => [i.id, i.watched])).toEqual([[1, true]])
  })

  it('keeps a shared card where it fell and opens the current server’s copy', () => {
    const streams: Stream[] = [
      { server: home, items: [item(1, 500, 10)], total: 1, done: true },
      { server: cabin, items: [item(51, 500, 20)], total: 1, done: true },
    ]
    const m = mergeStreams(streams, 'added')
    expect(m.items.map((i) => [i.id, i.oneCard])).toEqual([[1, undefined]])
    expect(m.items[0].watched).toBe(false)
    expect(m.needs).toEqual([])
  })

  it('counts each server’s titles less those they share, "about" until it knows', () => {
    const done: Stream[] = [{ server: home, items: [], total: 0, totals: totals(1000, 300), done: true }, { server: cabin, items: [], total: 0, totals: totals(240, 112), done: true }]
    const cards = [item(1, 1, 1), { ...item(2, 2, 1), watched: true }, item(3, 3, 1)]
    expect(mergedCounts('movie', done, 4, { asking: [], failed: [], items: cards })).toEqual({ line: '1,236 movies · 2 you haven’t watched', about: false })
    expect(mergedCounts('movie', done, 4, { asking: [], failed: [] }).line).toBe('1,236 movies')
    expect(mergedCounts('movie', done, 4, { asking: ['Den server'], failed: [] }).line).toBe('About 1,236 movies · still asking Den server…')
    expect(mergedCounts('movie', done, 4, { asking: [], failed: ['Den server'] }).line).toBe('1,236 movies · Den server didn’t answer')
    expect(mergedCounts('tv', [{ ...done[0], done: false }, done[1]], 0, { asking: [], failed: [] }).line).toBe('About 1,240 shows')
  })
})
