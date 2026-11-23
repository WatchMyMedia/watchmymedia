import { describe, expect, it } from 'vitest'
import { filterEntries, listFilterChips, listTally, meterParts, progressWords, showingWords } from './listProgress'
import type { components } from '../api/schema'

type MediaListEntry = components['schemas']['MediaListEntry']

const have = (rank: number, watched = false) => ({ rank, title: `T${rank}`, found: true, mediaType: 'movie', item: { id: rank, watched } }) as unknown as MediaListEntry
const missing = (rank: number, found = true) => ({ rank, title: `M${rank}`, found, mediaType: 'movie' }) as unknown as MediaListEntry

const entries = [have(1, true), have(2), missing(3), have(4), missing(5, false)]

describe('a list’s progress', () => {
  it('counts what the library has, what is watched and what is missing', () => {
    expect(listTally(entries)).toEqual({ total: 5, have: 3, watched: 1, toWatch: 2, missing: 2 })
  })

  it('words the panel and its bar', () => {
    const t = listTally(entries)
    const w = progressWords(t)
    expect(w.headline).toBe('3 of 5 in your library')
    expect(w.watched).toBe('1 watched')
    expect(w.label).toBe('1 watched, 2 to watch, 2 not in your library')
    expect(meterParts(t)).toEqual({ watched: 20, toWatch: 40 })
    expect(progressWords(listTally([have(1), have(2)])).headline).toBe('All 2 titles in your library')
    expect(meterParts(listTally([]))).toEqual({ watched: 0, toWatch: 0 })
  })

  it('narrows the titles by chip, offering Not in your library only when something is missing', () => {
    const t = listTally(entries)
    expect(listFilterChips(t).map((c) => `${c.label} ${c.count}`)).toEqual(['All 5', 'In your library 3', 'Not in your library 2', 'Unwatched 2'])
    expect(listFilterChips(listTally([have(1)])).map((c) => c.value)).toEqual(['all', 'have', 'unwatched'])
    expect(filterEntries(entries, 'have').map((e) => e.rank)).toEqual([1, 2, 4])
    expect(filterEntries(entries, 'missing').map((e) => e.rank)).toEqual([3, 5])
    expect(filterEntries(entries, 'unwatched').map((e) => e.rank)).toEqual([2, 4])
    expect(filterEntries(entries, 'all')).toHaveLength(5)
  })

  it('says how many are showing', () => {
    expect(showingWords(24, 50)).toBe('Showing 24 of 50')
    expect(showingWords(50, 50)).toBe('Showing all 50')
  })
})
