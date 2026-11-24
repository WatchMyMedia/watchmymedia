import { describe, expect, it } from 'vitest'
import {
  chooseFilter, choicesFrom, countsLine, DEFAULT_CHOICES, filterChoices, filterRows, filterRowValue, filterSummary, gridLine, gridQuery, narrowedLine, nextAirWords, nothingMatches, showLeftLine,
} from './libraryView'
import { keepChoices, keptChoices } from './keptChoices'

const c = (o: Partial<typeof DEFAULT_CHOICES>) => ({ ...DEFAULT_CHOICES, ...o })

describe('the library’s Filter and Sort', () => {
  it('lists the rows the server can filter by', () => {
    expect(filterRows('movie', { genres: true, decades: true })).toEqual(['watched', 'genre', 'decade', 'picture'])
    expect(filterRows('tv', { genres: false, decades: false })).toEqual(['watched', 'running', 'picture'])
    expect(filterChoices('movie', 'picture').map((x) => x.label)).toEqual(['Any', '4K', 'HDR'])
    expect(filterChoices('tv', 'running').map((x) => x.value)).toEqual(['', 'continuing', 'ended'])
  })

  it('adds Following, Rated and In progress from API level 34', () => {
    expect(filterRows('tv', { genres: true, decades: false, ratings: true, level34: true })).toEqual(['watched', 'running', 'following', 'genre', 'rating', 'picture'])
    expect(filterRows('movie', { genres: false, decades: false, ratings: true, level34: false })).toEqual(['watched', 'picture'])
    expect(filterRows('movie', { genres: false, decades: false, ratings: false, level34: true })).toEqual(['watched', 'picture'])
    expect(filterChoices('movie', 'watched', true).map((x) => x.label)).toEqual(['Any', 'Unwatched', 'In progress'])
    expect(filterChoices('tv', 'following', true).map((x) => x.label)).toEqual(['Any', 'Shows you follow'])
  })

  it('combines filters from API level 34, one a row', () => {
    const one = chooseFilter(DEFAULT_CHOICES, 'watched', 'unwatched', true)
    const two = chooseFilter(one, 'picture', '4k', true)
    expect(two.filters).toEqual(['unwatched', '4k'])
    expect(chooseFilter(two, 'watched', 'inProgress', true).filters).toEqual(['4k', 'inProgress'])
    expect(chooseFilter(two, 'picture', '', true).filters).toEqual(['unwatched'])
  })

  it('keeps one filter at a time below API level 34, as before', () => {
    const one = chooseFilter(DEFAULT_CHOICES, 'watched', 'unwatched', false)
    expect(chooseFilter(one, 'picture', '4k', false).filters).toEqual(['4k'])
    expect(gridQuery(c({ filters: ['unwatched', '4k'], rating: 'PG-13' }), false)).toEqual({ sort: 'added', filter: 'unwatched', genre: undefined, decade: undefined })
    expect(gridQuery(c({ filters: ['inProgress', 'hdr'] }), false)).toEqual({ sort: 'added', filter: 'hdr', genre: undefined, decade: undefined })
    expect(gridQuery(c({ filters: ['inProgress'] }), false)).toEqual({ sort: 'added', filter: '', genre: undefined, decade: undefined })
    expect(gridQuery(c({ filters: ['unwatched', '4k'], rating: 'PG-13', genre: 'Drama' }), true)).toEqual({ sort: 'added', filter: '', filters: ['unwatched', '4k'], genre: 'Drama', decade: undefined, contentRating: 'PG-13' })
  })

  it('words what narrows the grid', () => {
    expect(filterSummary(DEFAULT_CHOICES)).toBe('Off')
    expect(filterSummary(c({ filters: ['unwatched'], genre: 'Thriller', decade: 1980 }))).toBe('Unwatched, Thriller, 1980s')
    expect(filterSummary(c({ filters: ['unwatched', 'hdr'], rating: 'PG-13' }))).toBe('Unwatched, HDR, Rated PG-13')
    const fourK = c({ filters: ['4k'] })
    expect(filterRowValue('picture', fourK)).toBe('4K')
    expect(filterRowValue('watched', fourK)).toBe('Any')
    expect(filterRowValue('genre', c({ genre: 'Drama' }))).toBe('Drama')
    expect(filterRowValue('rating', c({ rating: 'unrated' }))).toBe('Not rated')
    expect(narrowedLine('movie', 23, 214)).toBe('23 of 214 movies')
  })

  it('says why nothing matches in a sentence', () => {
    expect(nothingMatches('movie', c({ filters: ['unwatched'], genre: 'Thriller', decade: 1980 }))).toBe('There are no unwatched thriller movies from the 1980s in Movies.')
    expect(nothingMatches('tv', c({ filters: ['ended'] }))).toBe('There are no ended shows in TV Shows.')
    expect(nothingMatches('movie', c({ filters: ['4k'], rating: 'PG-13' }))).toBe('There are no 4K movies rated PG-13 in Movies.')
  })

  it('keeps a library’s choices on this TV, per server and library', () => {
    const store = new Map<string, string>()
    const storage = { getItem: (k: string) => store.get(k) ?? null, setItem: (k: string, v: string) => { store.set(k, v) } }
    expect(keptChoices('s1', 1, storage)).toEqual(DEFAULT_CHOICES)
    keepChoices('s1', 1, c({ sort: 'title', filters: ['unwatched', '4k'], rating: 'R' }), storage)
    expect(keptChoices('s1', 1, storage)).toEqual(c({ sort: 'title', filters: ['unwatched', '4k'], rating: 'R' }))
    expect(keptChoices('s2', 1, storage)).toEqual(DEFAULT_CHOICES)
    store.set('wmm.tv.grid.s1.2', '{"sort":"nonsense","filter":"x"}')
    expect(keptChoices('s1', 2, storage)).toEqual(DEFAULT_CHOICES)
    store.set('wmm.tv.grid.s1.3', 'not json')
    expect(keptChoices('s1', 3, storage)).toEqual(DEFAULT_CHOICES)
    // Kept before filters combined: the one filter.
    store.set('wmm.tv.grid.s1.4', '{"sort":"title","filter":"unwatched"}')
    expect(keptChoices('s1', 4, storage)).toEqual(c({ sort: 'title', filters: ['unwatched'] }))
  })

  it('takes a See all’s one filter', () => {
    expect(choicesFrom({ sort: 'added', filter: '4k' }).filters).toEqual(['4k'])
  })
})

describe('the libraries’ counts, from API level 34', () => {
  it('says how many are left under the library’s name', () => {
    const totals = { items: 214, unmatched: 0, totalSize: 0, continuing: 0, episodes: 3418 }
    expect(countsLine('movie', { totals: { ...totals, unwatched: 37 } })).toBe('214 movies · 37 you haven’t watched')
    expect(countsLine('tv', { totals: { ...totals, items: 142, unwatched: 12 }, sort: 'title' })).toBe('142 shows · 12 with episodes you haven’t seen · A to Z')
    expect(countsLine('tv', { totals: { ...totals, items: 142 } })).toBe('142 shows · 3,418 episodes')
    expect(countsLine('movie', { totals: { ...totals, unwatched: 0 }, lists: 2 })).toBe('214 movies · 2 lists')
  })

  it('says where you are in a show on its card', () => {
    const now = new Date(2026, 10, 23, 20, 0) // a Monday
    const show = { watched: false, episodeCount: 12 }
    expect(showLeftLine({ ...show, unwatchedCount: 0 }, now)).toBe('All watched')
    expect(showLeftLine({ ...show, watched: true, unwatchedCount: 0 }, now)).toBe('All watched')
    expect(showLeftLine({ ...show, unwatchedCount: 4, newEpisodeCount: 2 }, now)).toBe('2 new episodes')
    expect(showLeftLine({ ...show, unwatchedCount: 12 }, now)).toBe('Not started')
    expect(showLeftLine({ ...show, unwatchedCount: 6 }, now)).toBe('6 left to watch')
    expect(showLeftLine({ ...show, unwatchedCount: 3, nextAirDate: '2026-11-26' }, now)).toBe('3 left · next Thursday')
    expect(showLeftLine({ ...show, unwatchedCount: 3, nextAirDate: '2026-12-26' }, now)).toBe('3 left to watch')
    expect(showLeftLine(show, now)).toBeUndefined()
  })

  it('words the next air date when it is close', () => {
    const now = new Date(2026, 10, 23, 20, 0)
    expect(nextAirWords('2026-11-23', now)).toBe('today')
    expect(nextAirWords('2026-11-24', now)).toBe('tomorrow')
    expect(nextAirWords('2026-11-29', now)).toBe('next Sunday')
    expect(nextAirWords('2026-11-30', now)).toBeUndefined()
    expect(nextAirWords('2026-11-20', now)).toBeUndefined()
    expect(nextAirWords(undefined, now)).toBeUndefined()
  })

  it('keeps the year and length on a card from an older server', () => {
    expect(gridLine({ kind: 'show', watched: false, year: 2019, episodeCount: 24 })).toBe('2019 · 24 episodes')
    expect(gridLine({ kind: 'show', watched: false, year: 2019, episodeCount: 24, unwatchedCount: 5 }, new Date(2026, 10, 23))).toBe('5 left to watch')
  })
})
