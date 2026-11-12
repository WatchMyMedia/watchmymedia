import { describe, expect, it } from 'vitest'
import { episodeRedirect, validateBrowseSearch, validateEditSearch, validateNewListSearch, validatePlaySearch, validateSeasonSearch } from './routeSearch'

describe('validateSeasonSearch', () => {
  it('keeps an episode ID, as the router parses it or as text', () => {
    expect(validateSeasonSearch({ episode: 77 })).toEqual({ episode: 77 })
    expect(validateSeasonSearch({ episode: '77' })).toEqual({ episode: 77 })
  })
  it('drops anything that is not a positive whole number', () => {
    expect(validateSeasonSearch({})).toEqual({ episode: undefined, at: undefined })
    for (const episode of ['', 'abc', '7.5', 0, -3, 1.5, Number.NaN, Number.POSITIVE_INFINITY, true, null, { id: 1 }]) {
      expect(validateSeasonSearch({ episode })).toEqual({ episode: undefined, at: undefined })
      expect(validateSeasonSearch({ at: episode })).toEqual({ episode: undefined, at: undefined })
    }
  })
  it('keeps the episode an older Home card opened the season at', () => {
    expect(validateSeasonSearch({ at: 77 })).toEqual({ episode: undefined, at: 77 })
    expect(validateSeasonSearch({ at: '77' })).toEqual({ episode: undefined, at: 77 })
  })
})

describe('episodeRedirect', () => {
  it('sends a season link naming an episode to that episode’s page', () => {
    expect(episodeRedirect({ showId: '5', number: '3' }, validateSeasonSearch({ episode: '77' }))).toEqual({
      to: '/shows/$showId/seasons/$number/episodes/$episodeId',
      params: { showId: '5', number: '3', episodeId: '77' },
    })
  })
  it('sends an older Home card’s ?at= to that episode’s address too', () => {
    expect(episodeRedirect({ showId: '5', number: '3' }, validateSeasonSearch({ at: '77' }))).toEqual({
      to: '/shows/$showId/seasons/$number/episodes/$episodeId',
      params: { showId: '5', number: '3', episodeId: '77' },
    })
  })
  it('leaves the season page alone without one', () => {
    expect(episodeRedirect({ showId: '5', number: '3' }, validateSeasonSearch({}))).toBeNull()
    expect(episodeRedirect({ showId: '5', number: '0' }, validateSeasonSearch({ episode: 'abc' }))).toBeNull()
  })
})

describe('validatePlaySearch', () => {
  it('reads start as seconds, keeping 0 as the beginning', () => {
    expect(validatePlaySearch({ start: 0 }).start).toBe(0)
    expect(validatePlaySearch({ start: 1234.5 }).start).toBe(1234.5)
    expect(validatePlaySearch({ start: '90' }).start).toBe(90)
    expect(validatePlaySearch({}).start).toBeUndefined()
  })
  it('keeps a back path on this site, query and all', () => {
    expect(validatePlaySearch({ back: '/' }).back).toBe('/')
    expect(validatePlaySearch({ back: '/shows/5/seasons/3?episode=77' }).back).toBe('/shows/5/seasons/3?episode=77')
  })
  it('drops a back that would leave the site', () => {
    for (const back of ['https://example.com/', '//example.com/', '/\\example.com', 'javascript:alert(1)', 'shows/5', '', 42]) {
      expect(validatePlaySearch({ back }).back).toBeUndefined()
    }
  })
  it('marks a trailer only when asked', () => {
    expect(validatePlaySearch({ trailer: true }).trailer).toBe(true)
    expect(validatePlaySearch({ trailer: 'true' }).trailer).toBe(true)
    for (const trailer of [undefined, false, 'false', 1, '']) {
      expect(validatePlaySearch({ trailer }).trailer).toBeUndefined()
    }
  })
})

describe('validateBrowseSearch', () => {
  it('keeps a row’s See all options the grid knows', () => {
    expect(validateBrowseSearch({ view: 'all', sort: 'rating', filter: 'unwatched', genre: 'Comedy', decade: '1990', library: 4 })).toEqual({
      q: undefined, view: 'all', sort: 'rating', filter: 'unwatched', genre: 'Comedy', decade: 1990, library: 4,
    })
  })

  it('drops options it doesn’t know, as an old or hand-typed address may have', () => {
    expect(validateBrowseSearch({ view: 'genres', sort: 'random', filter: 'all', decade: 'x', library: -1, genre: '' })).toEqual({
      q: undefined, view: undefined, sort: undefined, filter: undefined, genre: undefined, decade: undefined, library: undefined,
    })
  })

  it('keeps a search and the Unmatched view as before', () => {
    expect(validateBrowseSearch({ q: 'alien', view: 'unmatched' })).toMatchObject({ q: 'alien', view: 'unmatched' })
  })
})

describe('validateEditSearch and validateNewListSearch', () => {
  it('opens Edit rows, and Add a row only over it', () => {
    expect(validateEditSearch({ edit: true, add: 'true' })).toEqual({ edit: true, add: true })
    expect(validateEditSearch({ add: true })).toEqual({ edit: undefined, add: undefined })
    expect(validateEditSearch({ edit: 'no' })).toEqual({ edit: undefined, add: undefined })
    expect(validateBrowseSearch({ edit: '1' })).toMatchObject({ edit: true })
  })

  it('makes a list for Home unless a library’s screen is named', () => {
    expect(validateNewListSearch({ screen: 'library-4', back: 'settings' })).toEqual({ screen: 'library-4', back: 'settings' })
    expect(validateNewListSearch({ screen: 'library-x' })).toEqual({ screen: 'home', back: undefined })
    expect(validateNewListSearch({})).toEqual({ screen: 'home', back: undefined })
  })
})
