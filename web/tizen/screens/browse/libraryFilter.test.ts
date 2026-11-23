import { describe, expect, it } from 'vitest'
import { DEFAULT_CHOICES, filterChoices, filterRows, filterRowValue, filterSummary, narrowedLine, nothingMatches } from './libraryView'
import { keepChoices, keptChoices } from './keptChoices'

const c = (o: Partial<typeof DEFAULT_CHOICES>) => ({ ...DEFAULT_CHOICES, ...o })

describe('the library’s Filter and Sort', () => {
  it('lists the rows the server can filter by', () => {
    expect(filterRows('movie', { genres: true, decades: true })).toEqual(['watched', 'genre', 'decade', 'picture'])
    expect(filterRows('tv', { genres: false, decades: false })).toEqual(['watched', 'running', 'picture'])
    expect(filterChoices('movie', 'picture').map((x) => x.label)).toEqual(['Any', '4K', 'HDR'])
    expect(filterChoices('tv', 'running').map((x) => x.value)).toEqual(['', 'continuing', 'ended'])
  })

  it('words what narrows the grid, one shared filter at a time', () => {
    expect(filterSummary(DEFAULT_CHOICES)).toBe('Off')
    expect(filterSummary(c({ filter: 'unwatched', genre: 'Thriller', decade: 1980 }))).toBe('Unwatched, Thriller, 1980s')
    const fourK = c({ filter: '4k' })
    expect(filterRowValue('picture', fourK)).toBe('4K')
    expect(filterRowValue('watched', fourK)).toBe('Any')
    expect(filterRowValue('genre', c({ genre: 'Drama' }))).toBe('Drama')
    expect(narrowedLine('movie', 23, 214)).toBe('23 of 214 movies')
  })

  it('says why nothing matches in a sentence', () => {
    expect(nothingMatches('movie', c({ filter: 'unwatched', genre: 'Thriller', decade: 1980 }))).toBe('There are no unwatched thriller movies from the 1980s in Movies.')
    expect(nothingMatches('tv', c({ filter: 'ended' }))).toBe('There are no ended shows in TV Shows.')
  })

  it('keeps a library’s choices on this TV, per server and library', () => {
    const store = new Map<string, string>()
    const storage = { getItem: (k: string) => store.get(k) ?? null, setItem: (k: string, v: string) => { store.set(k, v) } }
    expect(keptChoices('s1', 1, storage)).toEqual(DEFAULT_CHOICES)
    keepChoices('s1', 1, c({ sort: 'title', filter: 'unwatched' }), storage)
    expect(keptChoices('s1', 1, storage)).toEqual(c({ sort: 'title', filter: 'unwatched' }))
    expect(keptChoices('s2', 1, storage)).toEqual(DEFAULT_CHOICES)
    store.set('wmm.tv.grid.s1.2', '{"sort":"nonsense","filter":"x"}')
    expect(keptChoices('s1', 2, storage)).toEqual(DEFAULT_CHOICES)
    store.set('wmm.tv.grid.s1.3', 'not json')
    expect(keptChoices('s1', 3, storage)).toEqual(DEFAULT_CHOICES)
  })
})
