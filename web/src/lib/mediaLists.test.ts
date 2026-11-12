import { describe, expect, it } from 'vitest'
import {
  byteLength, sentence, canEditList, canRefresh, catalogueLine, listCountWords, listMeta, chartLabel, chartLine, editorLine, listFromLine, previewFromLine, previewHaveWords, previewRestWords, retryWords, tmdbMode, holdsWords, notFoundWords, sourceWords, chartsFor, draftFromList, draftProblem, emptyDraft, entryLimit, entryView, hashText, importFormatLabel, inLibraryWords,
  knownKind, listMediaType, listPatch, mediaTypeChoices, previewKey, refreshWords, sameSource, screenName, sourceLink, sourceName, sourceOf, suggestName,
  MAX_IMPORT_BYTES, type MediaList, type MediaListEntry,
} from './mediaLists'

const libraries = [{ id: 2, type: 'movie', name: 'Films' }, { id: 3, type: 'tv', name: 'Series' }]
const list = (over: Partial<MediaList> = {}): MediaList => ({
  id: 5, name: 'Popular movies', mediaType: 'movie', source: { kind: 'tmdb', chart: 'popular' }, owner: 'me', canEdit: true, screen: 'home', limit: 50,
  entries: 50, inLibrary: 12, fetching: false, createdAt: '2026-11-01T00:00:00Z', updatedAt: '2026-11-01T00:00:00Z', ...over,
})
const entry = (over: Partial<MediaListEntry> = {}): MediaListEntry => ({ rank: 1, mediaType: 'movie', title: 'Harbor Lights', found: true, tmdbId: 11, ...over })

describe('kinds and media types', () => {
  it('offers only the kinds it knows', () => {
    expect(knownKind('tmdb')).toBe(true)
    expect(knownKind('trakt')).toBe(false)
  })

  it('lets TMDB lists be movies or TV, other kinds both, and takes a library list’s from its library', () => {
    expect(mediaTypeChoices('tmdb')).toEqual(['movie', 'tv'])
    expect(mediaTypeChoices('import')).toEqual(['all', 'movie', 'tv'])
    expect(mediaTypeChoices('library')).toEqual([])
    expect(listMediaType(emptyDraft('library', { libraryId: 3 }), libraries)).toBe('tv')
    expect(listMediaType(emptyDraft('tmdb', { mediaType: 'all' }), libraries)).toBe('movie')
    expect(listMediaType(emptyDraft('mdblist'), libraries)).toBe('all')
  })

  it('offers the charts that fit the media type, in the server’s order', () => {
    const charts = ['popular', 'now-playing', 'on-the-air', 'discover', 'collection', 'list']
    expect(chartsFor(charts, 'movie')).toEqual(['popular', 'now-playing', 'discover', 'collection', 'list'])
    expect(chartsFor(charts, 'tv')).toEqual(['popular', 'on-the-air', 'discover', 'list'])
    expect(chartsFor(undefined, 'tv')).toContain('airing-today')
  })

  it('names charts, and one it doesn’t know by its words', () => {
    expect(chartLabel('top-rated')).toBe('Top rated')
    expect(chartLabel('most-anticipated')).toBe('Most anticipated')
  })
})

describe('the source the API takes', () => {
  it('sends only a chart’s name for a chart', () => {
    expect(sourceOf(emptyDraft('tmdb', { chart: 'trending-week', ref: 'left over', yearFrom: '1990' }))).toEqual({ kind: 'tmdb', chart: 'trending-week' })
  })

  it('sends discover’s options as numbers, and leaves out the empty ones', () => {
    expect(sourceOf(emptyDraft('tmdb', { chart: 'discover', genres: [878, 12], yearFrom: '1980', yearTo: ' 1989 ', minRating: '7.5', minVotes: '', language: 'ko', sortBy: 'votes' }))).toEqual({
      kind: 'tmdb', chart: 'discover', genres: [12, 878], yearFrom: 1980, yearTo: 1989, minRating: 7.5, language: 'ko', sortBy: 'votes',
    })
  })

  it('sends a list’s or collection’s link as typed', () => {
    expect(sourceOf(emptyDraft('tmdb', { chart: 'collection', ref: ' 10 ' }))).toEqual({ kind: 'tmdb', chart: 'collection', ref: '10' })
    expect(sourceOf(emptyDraft('mdblist', { ref: 'garycrawfordgc/latest-tv-shows' }))).toEqual({ kind: 'mdblist', ref: 'garycrawfordgc/latest-tv-shows' })
  })

  it('sends a library’s filter as the grid has it', () => {
    expect(sourceOf(emptyDraft('library', { libraryId: 2, sort: 'rating', filter: '4k', genre: 'Comedy', decade: 1990 }))).toEqual({
      kind: 'library', libraryId: 2, sort: 'rating', filter: '4k', genre: 'Comedy', decade: 1990,
    })
    expect(sourceOf(emptyDraft('library', { libraryId: 2 }))).toEqual({ kind: 'library', libraryId: 2, sort: 'added' })
  })

  it('sends an import’s text whole', () => {
    expect(sourceOf(emptyDraft('import', { text: 'tt0083658\n' }))).toEqual({ kind: 'import', text: 'tt0083658\n' })
  })
})

describe('what the options still need', () => {
  it('asks for a TMDB list’s or MDBList list’s link', () => {
    expect(draftProblem(emptyDraft('tmdb', { chart: 'list' }))).toMatch(/link or ID/)
    expect(draftProblem(emptyDraft('mdblist'))).toMatch(/user\/name/)
    expect(draftProblem(emptyDraft('tmdb', { chart: 'popular' }))).toBeUndefined()
  })

  it('checks discover’s years and votes', () => {
    expect(draftProblem(emptyDraft('tmdb', { chart: 'discover', yearFrom: '1990', yearTo: '1980' }))).toBe('The first year is after the last.')
    expect(draftProblem(emptyDraft('tmdb', { chart: 'discover', yearFrom: '85' }))).toMatch(/four digits/)
    expect(draftProblem(emptyDraft('tmdb', { chart: 'discover', minVotes: '1.5' }))).toMatch(/whole number/)
    expect(draftProblem(emptyDraft('tmdb', { chart: 'discover', yearFrom: '1980', yearTo: '1980' }))).toBeUndefined()
  })

  it('needs a library, and an import’s text of at most 1 MB', () => {
    expect(draftProblem(emptyDraft('library'))).toBe('Choose a library.')
    expect(draftProblem(emptyDraft('import', { text: '  ' }))).toMatch(/Paste/)
    expect(draftProblem(emptyDraft('import', { text: 'x'.repeat(MAX_IMPORT_BYTES) }))).toBeUndefined()
    expect(draftProblem(emptyDraft('import', { text: 'é'.repeat(MAX_IMPORT_BYTES / 2 + 1) }))).toMatch(/1 MB/)
    expect(byteLength('é')).toBe(2)
  })

  it('takes 1 to 250 entries', () => {
    expect(entryLimit('50')).toBe(50)
    expect(entryLimit('250')).toBe(250)
    expect(entryLimit('0')).toBeUndefined()
    expect(entryLimit('251')).toBeUndefined()
    expect(entryLimit('2.5')).toBeUndefined()
    expect(entryLimit('')).toBeUndefined()
  })
})

describe('the preview’s cache key', () => {
  it('stands for the text by its hash, and changes when the text does', () => {
    const body = (text: string) => ({ mediaType: 'all' as const, limit: 50, source: { kind: 'import', text } })
    expect(previewKey(body('tt1\ntt2'))).not.toContain('tt1')
    expect(previewKey(body('tt1\ntt2'))).toBe(previewKey(body('tt1\ntt2')))
    expect(previewKey(body('tt1\ntt2'))).not.toBe(previewKey(body('tt1\ntt3')))
    expect(hashText('abc')).toMatch(/^[0-9a-f]+:3$/)
  })
})

describe('editing a list', () => {
  it('reads a list back into the builder’s options', () => {
    const d = draftFromList({ mediaType: 'tv', source: { kind: 'tmdb', chart: 'discover', genres: [18], yearFrom: 2000, minRating: 7 } })
    expect(d).toMatchObject({ kind: 'tmdb', mediaType: 'tv', chart: 'discover', genres: [18], yearFrom: '2000', yearTo: '', minRating: '7' })
    expect(sourceOf(d)).toEqual({ kind: 'tmdb', chart: 'discover', genres: [18], yearFrom: 2000, minRating: 7, sortBy: 'popularity' })
  })

  it('compares sources by what they fetch', () => {
    expect(sameSource({ kind: 'tmdb', chart: 'discover', genres: [2, 1], language: '' }, { chart: 'discover', kind: 'tmdb', genres: [1, 2] })).toBe(true)
    expect(sameSource({ kind: 'tmdb', chart: 'popular' }, { kind: 'tmdb', chart: 'top-rated' })).toBe(false)
    expect(sameSource({ kind: 'import', format: 'imdb' }, { kind: 'import', text: 'tt1' })).toBe(true)
  })

  it('sends only what changed', () => {
    const l = list()
    const same = draftFromList(l)
    expect(listPatch(l, same, { name: 'Popular movies', limit: 50, shared: false }, false)).toBeUndefined()
    expect(listPatch(l, same, { name: ' Big ones ', limit: 100, shared: false }, false)).toEqual({ name: 'Big ones', limit: 100 })
    expect(listPatch(l, { ...same, chart: 'top-rated' }, { name: 'Popular movies', limit: 50, shared: false }, false)).toEqual({ source: { kind: 'tmdb', chart: 'top-rated' } })
  })

  it('lets only admins share, and never sends an import’s source again', () => {
    const l = list({ source: { kind: 'import', format: 'imdb' }, mediaType: 'all' })
    expect(listPatch(l, draftFromList(l), { name: l.name, limit: 50, shared: true }, false)).toBeUndefined()
    expect(listPatch(l, draftFromList(l), { name: l.name, limit: 50, shared: true }, true)).toEqual({ shared: true })
  })

  it('offers Edit for kinds it knows and Refresh for lists that are fetched', () => {
    expect(canEditList(list())).toBe(true)
    expect(canEditList(list({ source: { kind: 'trakt' } }))).toBe(false)
    expect(canEditList(list({ canEdit: false }))).toBe(false)
    expect(canRefresh(list())).toBe(true)
    expect(canRefresh(list({ fetching: true }))).toBe(false)
    expect(canRefresh(list({ source: { kind: 'import' } }))).toBe(false)
    expect(canRefresh(list({ source: { kind: 'library' } }))).toBe(false)
  })
})

describe('names', () => {
  const genres = [{ id: 878, name: 'Science Fiction' }, { id: 12, name: 'Adventure' }]

  it('suggests a name from a chart or a query', () => {
    expect(suggestName(emptyDraft('tmdb', { chart: 'top-rated', mediaType: 'tv' }))).toBe('Top rated TV shows')
    expect(suggestName(emptyDraft('tmdb', { chart: 'trending-week' }))).toBe('Trending movies this week')
    expect(suggestName(emptyDraft('tmdb', { chart: 'discover', genres: [878], yearFrom: '1980', yearTo: '1989' }), { genres })).toBe('Science Fiction movies, 1980–1989')
    expect(suggestName(emptyDraft('tmdb', { chart: 'discover', language: 'ko' }), { languageName: () => 'Korean' })).toBe('Movies, in Korean')
  })

  it('suggests a name from an MDBList list, a library filter or a file', () => {
    expect(suggestName(emptyDraft('mdblist', { ref: 'https://mdblist.com/lists/linaspurinis/top-watched-movies-of-the-week' }))).toBe('Top watched movies of the week')
    expect(suggestName(emptyDraft('mdblist', { ref: '12345' }))).toBe('MDBList list')
    expect(suggestName(emptyDraft('library', { libraryId: 2, filter: 'unwatched', genre: 'Comedy', decade: 1990 }), { libraries })).toBe('Unwatched Comedy movies from the 1990s')
    expect(suggestName(emptyDraft('library', { libraryId: 3, sort: 'rating' }), { libraries })).toBe('TV shows by rating')
    expect(suggestName(emptyDraft('import', { fileName: 'watchlist.csv' }))).toBe('Watchlist')
    expect(suggestName(emptyDraft('import'))).toBe('Imported list')
  })
})

describe('where a list comes from', () => {
  it('names the source, and an import by what it was read as', () => {
    expect(sourceName({ kind: 'tmdb' })).toBe('TMDB')
    expect(sourceName({ kind: 'library' })).toBe('your library')
    expect(sourceName({ kind: 'import', format: 'letterboxd' })).toBe('a Letterboxd export')
    expect(sourceName({ kind: 'trakt' })).toBe('trakt')
    expect(importFormatLabel('imdb')).toBe('an IMDb list export')
    expect(importFormatLabel('json')).toBeUndefined()
  })

  it('links to the list on TMDB or MDBList, and nowhere else', () => {
    expect(sourceLink({ kind: 'tmdb', chart: 'list', ref: '8136' }, 'movie')).toBe('https://www.themoviedb.org/list/8136')
    expect(sourceLink({ kind: 'tmdb', chart: 'collection', ref: 'https://www.themoviedb.org/collection/10-star-wars-collection' }, 'movie')).toBe('https://www.themoviedb.org/collection/10-star-wars-collection')
    expect(sourceLink({ kind: 'tmdb', chart: 'list', ref: 'https://example.com/list/1' }, 'movie')).toBeUndefined()
    expect(sourceLink({ kind: 'tmdb', chart: 'top-rated' }, 'tv')).toBe('https://www.themoviedb.org/tv/top-rated')
    expect(sourceLink({ kind: 'tmdb', chart: 'discover' }, 'movie')).toBeUndefined()
    expect(sourceLink({ kind: 'mdblist', ref: 'user/top-250' }, 'all')).toBe('https://mdblist.com/lists/user/top-250')
    expect(sourceLink({ kind: 'mdblist', ref: 'https://evil.example/lists/a/b' }, 'all')).toBeUndefined()
    expect(sourceLink({ kind: 'import' }, 'all')).toBeUndefined()
  })

  it('says when it was fetched and why it failed', () => {
    const now = new Date().toISOString()
    expect(refreshWords(list({ fetchedAt: now }))).toEqual({ fetched: 'Updated just now', error: undefined })
    expect(refreshWords(list({ fetching: true })).fetched).toBe('Fetching now…')
    expect(refreshWords(list({ error: 'MDBList refused the key', errorAt: now })).error).toBe('Couldn’t update just now: MDBList refused the key')
    expect(refreshWords(list({ source: { kind: 'library' } })).fetched).toBe('Follows your library as it changes')
    expect(refreshWords(list({ source: { kind: 'import' }, createdAt: now })).fetched).toBe('Imported just now')
  })

  it('names the screen a list was made on', () => {
    expect(screenName('home', libraries)).toBe('Home')
    expect(screenName('library-3', libraries)).toBe('Series')
    expect(screenName('library-9', libraries)).toBe('Movies')
  })
})

describe('titles on a list’s page', () => {
  it('shows the library’s card for what it has', () => {
    expect(entryView(entry({ item: {} as never }))).toEqual({ kind: 'library' })
  })

  it('offers Request when the person may ask, else says where the request stands', () => {
    expect(entryView(entry({ requestState: 'requestable' }))).toEqual({ kind: 'missing', request: true, label: 'Request' })
    expect(entryView(entry({ requestState: 'requestable', tmdbId: undefined }))).toEqual({ kind: 'missing', request: false, label: 'Not in your library' })
    expect(entryView(entry({ requestState: 'downloading' }))).toEqual({ kind: 'missing', request: false, label: 'Downloading' })
    expect(entryView(entry({ requestState: 'waitlisted' }))).toEqual({ kind: 'missing', request: false, label: 'Not in your library' })
    expect(entryView(entry())).toEqual({ kind: 'missing', request: false, label: 'Not in your library' })
  })

  it('shows an import TMDB couldn’t find as Not found', () => {
    expect(entryView(entry({ found: false, tmdbId: undefined }))).toEqual({ kind: 'notFound' })
  })

  it('counts what the library has', () => {
    expect(inLibraryWords(23, 50)).toBe('23 of 50 in your library')
  })
})

describe('lines under a list’s row', () => {
  const entry = (over: Partial<{ id: string; kind: string; owner: string; style: string; hidden: boolean }> = {}) => ({ id: 'list:5', kind: 'list', owner: 'me', style: 'poster', hidden: false, ...over })

  it('says where a list comes from and how much the library has', () => {
    expect(sourceWords({ kind: 'import' })).toBe('Imported')
    expect(sourceWords({ kind: 'trakt' })).toBe('From trakt')
    expect(holdsWords(list({ inLibrary: 18, entries: 50 }))).toBe('18 of 50')
    expect(holdsWords(list({ inLibrary: 18, entries: 50 }), true)).toBe('18 of 50 in your library')
    expect(holdsWords(list({ source: { kind: 'library' }, entries: 26, inLibrary: 26 }))).toBe('26 titles')
    expect(notFoundWords(3)).toBe('3 titles weren’t found on TMDB')
    expect(notFoundWords(1)).toBe('1 title wasn’t found on TMDB')
  })

  it('reads in the editor as the boards have it', () => {
    expect(editorLine(entry({ kind: 'builtin', id: 'continue' }), undefined, 'home', libraries)).toBe('Built-in')
    expect(editorLine(entry({ kind: 'builtin', id: 'live', style: 'live' }), undefined, 'home', libraries)).toMatch(/not on the web yet/)
    expect(editorLine(entry(), list({ inLibrary: 18, entries: 50 }), 'home', libraries)).toBe('Your list · 18 of 50 · From TMDB')
    expect(editorLine(entry({ owner: 'everyone' }), list({ source: { kind: 'library' }, entries: 26 }), 'home', libraries)).toBe('For everyone · 26 titles · From your library')
    expect(editorLine(entry(), list({ screen: 'library-2', inLibrary: 9 }), 'home', libraries)).toBe('Your list · made on Films · 9 of 50 · From TMDB')
    expect(editorLine(entry(), undefined, 'home', libraries)).toBe('Your list')
  })

  it('reads in Add a row as the boards have it', () => {
    expect(catalogueLine(entry({ kind: 'builtin', id: 'recentlyReleased' }), undefined, 'home', libraries)).toBe('Built-in · hidden by you')
    expect(catalogueLine(entry(), list({ screen: 'library-2', source: { kind: 'tmdb', chart: 'discover' }, inLibrary: 9 }), 'home', libraries)).toBe('Made on Films · From TMDB · 9 of 50 in your library')
    expect(catalogueLine(entry(), list({ source: { kind: 'tmdb', chart: 'trending-week' }, inLibrary: 6, entries: 20 }), 'home', libraries)).toBe('From TMDB · trending this week · 6 of 20 in your library')
    expect(catalogueLine(entry(), list({ source: { kind: 'import' }, inLibrary: 31, entries: 34 }), 'home', libraries)).toBe('Imported · 31 of 34 in your library')
  })
})

describe('Make a list’s preview and lines', () => {
  const genres = [{ id: 878, name: 'Science Fiction' }]

  it('names what the list is', () => {
    expect(previewFromLine(emptyDraft('tmdb', { chart: 'popular' }))).toBe('From TMDB · Popular · movies')
    expect(previewFromLine(emptyDraft('tmdb', { chart: 'discover', genres: [878], yearFrom: '1980', yearTo: '1989', minRating: '7', sortBy: 'votes' }), { genres })).toBe(
      'From TMDB · Science Fiction · 1980–1989 · rated 7+ · most votes first',
    )
    expect(previewFromLine(emptyDraft('library', { libraryId: 2, filter: 'unwatched', genre: 'Comedy', decade: 1990, sort: 'rating' }), { libraries })).toBe(
      'From your library · Films · unwatched · Comedy · 1990s · by rating',
    )
    expect(previewFromLine(emptyDraft('import'), { format: 'lines', notFound: 3 })).toBe('Imported · one title a line · 3 not found')
    expect(previewFromLine(emptyDraft('mdblist', { ref: 'user/top' }))).toBe('From MDBList · user/top')
  })

  it('counts what the library has, and what follows the first 20', () => {
    expect(previewHaveWords('tmdb', 23, 50)).toBe('23 of 50 in your library')
    expect(previewHaveWords('library', 37, 37)).toBe('37 titles, all in your library')
    expect(previewRestWords(20, 50, 'tmdb')).toMatch(/^and 30 more\. The list’s page/)
    expect(previewRestWords(20, 37, 'library')).toBe('and 17 more.')
    expect(previewRestWords(12, 12, 'tmdb')).toBe('')
  })

  it('sorts TMDB options into chart, discover or a list', () => {
    expect(tmdbMode('popular')).toBe('chart')
    expect(tmdbMode('discover')).toBe('discover')
    expect(tmdbMode('collection')).toBe('ref')
    expect(chartLine('popular')).toBe('TMDB’s popularity, updated daily')
    expect(chartLine('new-one')).toBeUndefined()
  })

  it('says when a failed list tries again', () => {
    const now = Date.parse('2026-11-07T12:00:00Z')
    expect(retryWords('2026-11-07T18:00:00Z', now)).toBe('It tries again in 6 hours.')
    expect(retryWords('2026-11-07T12:20:00Z', now)).toBe('It tries again in 20 minutes.')
    expect(retryWords('2026-11-07T12:00:30Z', now)).toBe('It tries again soon.')
    expect(retryWords(undefined, now)).toBe('')
  })

  it('names a household list’s source in Settings', () => {
    expect(listFromLine(list({ source: { kind: 'tmdb', chart: 'discover' } }), libraries)).toBe('From TMDB · discover')
    expect(listFromLine(list({ source: { kind: 'tmdb', chart: 'trending-week' } }), libraries)).toBe('From TMDB · trending this week')
    expect(listFromLine(list({ source: { kind: 'library', libraryId: 2 } }), libraries)).toBe('From your library · Films')
    expect(listFromLine(list({ source: { kind: 'mdblist' } }), libraries)).toBe('From MDBList')
  })
})

describe('the list page’s lines', () => {
  it('says where the list is from, when it was updated and what it holds', () => {
    const now = new Date().toISOString()
    expect(listMeta(list({ fetchedAt: now, source: { kind: 'tmdb', chart: 'top-rated' } }))).toEqual({
      from: 'From TMDB', href: 'https://www.themoviedb.org/movie/top-rated', updated: 'Updated just now', type: 'Movies',
    })
    expect(listMeta(list({ source: { kind: 'import', format: 'lines' }, mediaType: 'all' }))).toMatchObject({ updated: 'One title a line', type: 'Movies and TV shows' })
    expect(listMeta(list({ source: { kind: 'import' } })).href).toBeUndefined()
    expect(listMeta(list({ source: { kind: 'import', format: 'lines' } })).from).toMatch(/^Imported /)
    expect(listMeta(list({ source: { kind: 'library' }, mediaType: 'tv' }))).toEqual({ from: 'From your library', updated: 'Follows the library', type: 'TV shows' })
    expect(listMeta(list({ source: { kind: 'mdblist', ref: 'a/b' } })).updated).toBe('Not fetched yet')
  })

  it('counts the list', () => {
    expect(listCountWords(50, 18, 3)).toBe('50 titles in the list’s order · 18 in your library · 29 missing · 3 not found')
    expect(listCountWords(26, 26)).toBe('26 titles in the list’s order · 26 in your library')
  })
})

describe('sentence', () => {
  it('ends a reason with a full stop when it has none', () => {
    expect(sentence('An admin can add an MDBList key in Settings › Lists')).toBe('An admin can add an MDBList key in Settings › Lists.')
    expect(sentence('MDBList refused the key.')).toBe('MDBList refused the key.')
    expect(sentence(undefined)).toBe('')
  })
})
