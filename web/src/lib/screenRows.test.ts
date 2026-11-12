import { describe, expect, it } from 'vitest'
import type { components } from '../api/schema'
import {
  attribution, drawable, homeRows, isListRow, libraryRows, libraryScreen, posterSubtitle, screenLibraryId, screenTarget, seeAllTarget, titleParts, webLink,
  type ScreenRow,
} from './screenRows'

type BrowseItem = components['schemas']['BrowseItem']
type HomeData = components['schemas']['HomeData']

const item = (over: Partial<BrowseItem> = {}): BrowseItem => ({
  id: 1, kind: 'movie', title: 'Harbor Lights', libraryId: 2, addedAt: new Date().toISOString(), genres: [], hdr: false, isNew: false, overview: '', totalSize: 0, watched: false, ...over,
})
const home = (over: Partial<HomeData> = {}): HomeData => ({
  continueWatching: [], recentlyAddedShows: [], recentlyAired: [], recentlyAddedMovies: [], recentlyReleased: [],
  totals: { movies: 0, shows: 0, episodes: 0, files: 0, totalSize: 0 }, ...over,
} as HomeData)
const libraries = [{ id: 2, type: 'movie' }, { id: 3, type: 'tv' }, { id: 7, type: 'movie' }]

describe('screens', () => {
  it('names a library’s screen as the layout routes take it', () => {
    expect(libraryScreen(4)).toBe('library-4')
    expect(screenLibraryId('library-4')).toBe(4)
    expect(screenLibraryId('home')).toBeUndefined()
  })
})

describe('which rows are drawn', () => {
  it('draws the styles it knows when they have something in them', () => {
    expect(drawable({ id: 'a', style: 'poster', title: '', items: [item()] })).toBe(true)
    expect(drawable({ id: 'a', style: 'wide', title: '', items: [item()] })).toBe(true)
    expect(drawable({ id: 'a', style: 'poster', title: '', items: [] })).toBe(false)
    expect(drawable({ id: 'a', style: 'episode', title: '' })).toBe(false)
    expect(drawable({ id: 'continue', style: 'continue', title: '', continue: [{} as never] })).toBe(true)
  })

  it('draws nothing for live, which has no items, or a style it doesn’t know', () => {
    expect(drawable({ id: 'live', style: 'live', title: 'Live TV' })).toBe(false)
    expect(drawable({ id: 'x', style: 'carousel', title: '', items: [item()] })).toBe(false)
  })
})

describe('rows from a reply', () => {
  it('uses an arranged reply’s rows as they come', () => {
    const rows: ScreenRow[] = [{ id: 'list:3', style: 'poster', title: 'Top rated', items: [item()] }, { id: 'continue', style: 'continue', title: 'Continue watching' }]
    expect(homeRows(home({ rows }))).toBe(rows)
    expect(libraryRows({ recentlyAdded: [], recentlyAired: [], recentlyReleased: [], rows }, 'movie')).toBe(rows)
  })

  it('draws nothing for an arranged reply with every row hidden, whose fixed arrays are empty', () => {
    expect(homeRows(home()).filter(drawable)).toEqual([])
  })

  it('makes today’s rows, in today’s order, from an unarranged reply', () => {
    const rows = homeRows(home({ recentlyAddedMovies: [item()], recentlyAired: [{} as never], movieLibraryId: 2 }))
    expect(rows.map((r) => r.id)).toEqual(['continue', 'recentlyAddedShows', 'recentlyAired', 'recentlyAddedMovies', 'recentlyReleased'])
    expect(rows.filter(drawable).map((r) => r.id)).toEqual(['recentlyAired', 'recentlyAddedMovies'])
    expect(rows[3].seeAll).toEqual({ kind: 'library', libraryId: 2 })
    expect(libraryRows({ recentlyAdded: [], recentlyAired: [], recentlyReleased: [] }, 'tv').map((r) => r.id)).toEqual(['recentlyAdded', 'recentlyAired'])
    expect(libraryRows({ recentlyAdded: [], recentlyAired: [], recentlyReleased: [] }, 'movie').map((r) => r.style)).toEqual(['poster', 'wide'])
  })
})

describe('headings', () => {
  it('splits a built-in row’s title into the heading and its muted part', () => {
    expect(titleParts({ id: 'recentlyAddedMovies', title: 'Recently added · Movies' })).toEqual(['Recently added', 'Movies'])
    expect(titleParts({ id: 'continue', title: 'Continue watching' })).toEqual(['Continue watching', undefined])
  })

  it('keeps a list’s name whole', () => {
    expect(isListRow({ id: 'list:5' })).toBe(true)
    expect(titleParts({ id: 'list:5', title: 'Sci-fi · the 1980s' })).toEqual(['Sci-fi · the 1980s', undefined])
  })

  it('says when a title was added or released under its poster; a show card’s episode code is the card’s own links', () => {
    expect(posterSubtitle({ id: 'recentlyAddedMovies' }, item())).toBe('added just now')
    expect(posterSubtitle({ id: 'recentlyAddedShows' }, item({ kind: 'show', subtitle: 'S01E02' }))).toBe('added just now')
    expect(posterSubtitle({ id: 'recentlyReleased' }, item({ releaseDate: '2026-10-01' }))).toBe('2026-10-01')
    expect(posterSubtitle({ id: 'list:2' }, item())).toBeUndefined()
  })
})

describe('a list row’s line', () => {
  const tmdb = { kind: 'tmdb', name: 'TMDB', url: 'https://www.themoviedb.org/list/8' }

  it('links From <source> inside the server’s subtitle', () => {
    expect(attribution('18 of 50 · From TMDB', tmdb)).toEqual([{ text: '18 of 50 · ' }, { text: 'From TMDB', href: 'https://www.themoviedb.org/list/8' }])
  })

  it('adds the source when the subtitle doesn’t name it', () => {
    expect(attribution('18 of 50', { kind: 'mdblist', name: 'MDBList' })).toEqual([{ text: '18 of 50' }, { text: ' · ' }, { text: 'From MDBList', href: undefined }])
    expect(attribution(undefined, tmdb)).toEqual([{ text: 'From TMDB', href: 'https://www.themoviedb.org/list/8' }])
    expect(attribution('18 of 50', undefined)).toEqual([{ text: '18 of 50' }])
    expect(attribution(undefined, undefined)).toEqual([])
  })

  it('links only to web pages', () => {
    expect(webLink('javascript:alert(1)')).toBeUndefined()
    expect(webLink('not a url')).toBeUndefined()
    expect(webLink('https://mdblist.com/lists/a/b')).toBe('https://mdblist.com/lists/a/b')
    expect(attribution('From TMDB', { ...tmdb, url: 'data:text/html,hi' })).toEqual([{ text: 'From TMDB', href: undefined }])
  })
})

describe('See all', () => {
  it('opens a list’s page', () => {
    expect(seeAllTarget({ seeAll: { kind: 'list', listId: 9 } }, libraries)).toEqual({ kind: 'list', listId: 9 })
    expect(seeAllTarget({ seeAll: { kind: 'list' }, listId: 4 }, libraries)).toEqual({ kind: 'list', listId: 4 })
    expect(seeAllTarget({ seeAll: { kind: 'list' } }, libraries)).toBeUndefined()
  })

  it('opens the library’s grid with the link’s sort and filter', () => {
    expect(seeAllTarget({ seeAll: { kind: 'library', libraryId: 2, sort: 'added', filter: 'unwatched', decade: 1990, genre: 'Comedy' } }, libraries)).toEqual({
      kind: 'library', type: 'movie', search: { view: 'all', sort: 'added', filter: 'unwatched', genre: 'Comedy', decade: 1990, library: undefined },
    })
  })

  it('opens the Overview when the link sets no options, and names a library that isn’t the first of its type', () => {
    expect(seeAllTarget({ seeAll: { kind: 'library', libraryId: 3 } }, libraries)).toEqual({ kind: 'library', type: 'tv', search: { view: undefined, sort: undefined, filter: undefined, genre: undefined, decade: undefined, library: undefined } })
    expect(seeAllTarget({ seeAll: { kind: 'library', libraryId: 7 } }, libraries)).toMatchObject({ type: 'movie', search: { library: 7 } })
  })

  it('takes the library from the row’s items when the link leaves it out', () => {
    expect(seeAllTarget({ seeAll: { kind: 'library' }, items: [item({ libraryId: 3 })] }, libraries)).toMatchObject({ type: 'tv' })
  })

  it('drops options the grid doesn’t know, and hides a link it can’t follow', () => {
    expect(seeAllTarget({ seeAll: { kind: 'library', libraryId: 2, sort: 'shuffle', filter: 'all' } }, libraries)).toMatchObject({ search: { view: undefined, sort: undefined, filter: undefined } })
    expect(seeAllTarget({ seeAll: { kind: 'library', libraryId: 99 } }, libraries)).toBeUndefined()
    expect(seeAllTarget({ seeAll: { kind: 'collection', listId: 1 } }, libraries)).toBeUndefined()
    expect(seeAllTarget({}, libraries)).toBeUndefined()
  })
})

describe('screenTarget', () => {
  it('goes to Home, or the library’s page', () => {
    expect(screenTarget('home', libraries)).toEqual({ to: '/' })
    expect(screenTarget('library-3', libraries)).toEqual({ to: '/tv', library: undefined })
    expect(screenTarget('library-7', libraries)).toEqual({ to: '/movies', library: 7 })
    expect(screenTarget('library-99', libraries)).toEqual({ to: '/' })
  })
})
