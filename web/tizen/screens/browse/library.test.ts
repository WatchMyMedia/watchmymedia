import { describe, expect, it } from 'vitest'
import type { components } from '../../../src/api/schema'
import { homeEmpty, homeEmptyKind } from './emptyHome'
import { ALPHABET, letterAt, letterCounts, letterLabel, nextLetter, pagesAround, slots, stepRows, stripEntry, toStrip } from './gridPages'
import { choicesFrom, countsLine, DEFAULT_CHOICES, genreChips, gridGroup, narrowed, sortLabel, sortWords } from './libraryView'
import { entryCard, entryLabel, failedWords, listCount, listKicker, listLine, missingLine } from './listPage'

type Library = components['schemas']['Library']
type MediaListEntry = components['schemas']['MediaListEntry']
type MediaList = components['schemas']['MediaList']

const library = (over: Partial<Library> = {}): Library => ({
  id: 1, type: 'movie', name: 'Movies', path: '/srv/secret/movies', fileCount: 0, missingCount: 0, pendingCount: 0, unmatchedCount: 0, createdAt: '', updatedAt: '', ...over,
})

describe('an empty Home', () => {
  it('says why from the libraries, never with the server’s reason', () => {
    expect(homeEmptyKind(undefined).kind).toBe('unavailable')
    expect(homeEmptyKind([]).kind).toBe('noLibraries')
    expect(homeEmptyKind([library()]).kind).toBe('emptyLibraries')
    expect(homeEmptyKind([library({ scan: { libraryId: 1, scanned: 1, total: 3, current: 'a.mkv' } })]).kind).toBe('scanning')
    const failed = homeEmpty([library({ lastScanError: 'open /srv/secret/movies: permission denied' }), library({ id: 2, name: 'TV', match: { libraryId: 2, done: 0, total: 1, current: '' } })], true)
    expect(failed).toMatchObject({ kind: 'scanFailed', title: 'Couldn’t read the library', setup: true })
    expect(failed.body).toBe('Couldn’t read Movies. Other libraries are still being scanned. Check the library on the server’s web page.')
    expect(JSON.stringify(failed)).not.toContain('/srv')
    const two = homeEmpty([library({ lastScanError: 'x' }), library({ id: 2, name: 'TV', lastScanError: 'y' })], false)
    expect(two.title).toBe('Couldn’t read some libraries')
    expect(two.body).toBe('Couldn’t read Movies or TV. Ask the person who runs the server to check the library.')
  })

  it('tells admins to set up on the web page, and everyone else whom to ask', () => {
    expect(homeEmpty([], true).body).toContain('server’s web page')
    expect(homeEmpty([], false).body).toBe('Ask the person who runs it to add a library.')
    expect(homeEmpty([library()], false).body).toBe('Ask the person who runs the server to check the library’s folders and scan status.')
    expect(homeEmpty([library({ scan: { libraryId: 1, scanned: 0, total: 0, current: '' } })], true).setup).toBe(false)
  })
})

describe('the grid’s pages', () => {
  it('asks for the pages around focus, and only those', () => {
    expect(pagesAround(0, 0)).toEqual([0])
    expect(pagesAround(0, 500)).toEqual([0])
    expect(pagesAround(40, 500)).toEqual([0, 1])
    expect(pagesAround(200, 500)).toEqual([3, 4])
    expect(pagesAround(200, 500, 48, 8, 6)).toEqual([3, 4, 5])
    expect(pagesAround(499, 500)).toEqual([9, 10])
  })

  it('lays loaded pages out at their offsets with holes between', () => {
    const s = slots(10, new Map([[0, ['a', 'b', 'c']], [2, ['g', 'h', 'i']]]), 3)
    expect(s).toHaveLength(10)
    expect(s.slice(0, 3)).toEqual(['a', 'b', 'c'])
    expect(s[3]).toBeUndefined()
    expect(s.slice(6)).toEqual(['g', 'h', 'i', undefined])
  })

  it('moves two rows with CH and goes to the strip from the last column', () => {
    expect(stepRows(3, 100, 2)).toBe(19)
    expect(stepRows(3, 100, -2)).toBe(0)
    expect(stepRows(90, 100, 2)).toBe(99)
    expect(toStrip(7, 100)).toBe(true)
    expect(toStrip(6, 100)).toBe(false)
    expect(toStrip(99, 100)).toBe(true)
  })

  it('counts letters, skips empty ones and finds the focused title’s', () => {
    const letters = [{ letter: '#', offset: 0 }, { letter: 'A', offset: 2 }, { letter: 'M', offset: 10 }, { letter: 'Z', offset: 30 }]
    const counts = letterCounts(letters, 32)
    expect([...counts]).toEqual([['#', 2], ['A', 8], ['M', 20], ['Z', 2]])
    expect(letterAt(letters, 0)).toBe('#')
    expect(letterAt(letters, 12)).toBe('M')
    expect(nextLetter('A', counts, 1)).toBe('M')
    expect(nextLetter('M', counts, -1)).toBe('A')
    expect(nextLetter('Z', counts, 1)).toBeUndefined()
    expect(nextLetter('#', counts, -1)).toBeUndefined()
    expect(stripEntry(letters, 31)).toBe('Z')
    expect(stripEntry([{ letter: 'B', offset: 0 }], -1)).toBe('B')
    expect(ALPHABET).toHaveLength(27)
    expect(letterLabel('#', 2, 'show')).toBe('Jump to numbers, 2 shows')
    expect(letterLabel('Q', 0, 'show')).toBe('Q, no shows')
    expect(letterLabel('M', 1, 'movie')).toBe('Jump to M, 1 movie')
  })
})

describe('a library’s grid', () => {
  it('takes a See all’s choices it knows, and opens newest first otherwise', () => {
    expect(choicesFrom({})).toEqual(DEFAULT_CHOICES)
    expect(choicesFrom({ sort: 'aired', filter: 'ended', genre: 'Drama', decade: 1980 })).toEqual({ sort: 'aired', filter: 'ended', genre: 'Drama', decade: 1980 })
    expect(choicesFrom({ sort: 'loudness', filter: 'odd', decade: -3 })).toEqual(DEFAULT_CHOICES)
    expect(narrowed(DEFAULT_CHOICES)).toBe(false)
    expect(narrowed({ ...DEFAULT_CHOICES, genre: 'Drama' })).toBe(true)
    expect(gridGroup(2, { sort: 'title', filter: '', genre: 'Sci-Fi & Fantasy', decade: 0 })).toBe('grid/2/title/all/Sci-Fi%20%26%20Fantasy/any')
  })

  it('words the header’s counts and sort', () => {
    expect(countsLine('movie', { totals: { items: 214, episodes: 0, continuing: 0, unmatched: 0, totalSize: 0 }, total: 9, lists: 2 })).toBe('214 movies · 2 lists')
    expect(countsLine('tv', { totals: { items: 142, episodes: 3418, continuing: 0, unmatched: 0, totalSize: 0 }, sort: 'title' })).toBe('142 shows · 3,418 episodes · A to Z')
    expect(countsLine('tv', { total: 1 })).toBe('1 show')
    expect(sortWords('runtime')).toBe('longest first')
    expect(sortLabel('tv', 'aired')).toBe('Last aired')
  })

  it('leaves out genres the other choices empty, but never the chosen one', () => {
    expect(genreChips(['Drama', 'Comedy', 'Crime'], { Drama: 3, Comedy: 0 }, 'Crime')).toEqual(['Drama', 'Crime'])
    expect(genreChips(['Drama'], undefined, '')).toEqual(['Drama'])
  })
})

const entry = (over: Partial<MediaListEntry> = {}): MediaListEntry => ({ rank: 3, title: 'Halfway to Juniper', mediaType: 'movie', found: true, tmdbId: 55, year: 1988, ...over })
const list = (over: Partial<MediaList> = {}): MediaList => ({
  id: 1, name: 'Best of the 1980s', owner: 'everyone', screen: 'home', mediaType: 'movie', source: { kind: 'tmdb', chart: 'popular' }, canEdit: false, createdAt: '', updatedAt: '',
  entries: 50, inLibrary: 18, limit: 50, fetching: false, fetchedAt: new Date(Date.now() - 3 * 3_600_000).toISOString(), ...over,
})

describe('a list’s page', () => {
  it('words the header', () => {
    expect(listKicker(list())).toBe('List · For everyone')
    expect(listKicker(list({ owner: 'me' }))).toBe('Your list')
    expect(listLine(list())).toBe('From TMDB · Updated 3 h ago')
    expect(listCount(18, 50)).toBe('18 of 50 in your library')
    expect(listCount(9, 24, 2)).toBe('9 of 24 in your library · 2 not found')
  })

  it('says a fetch failed without the server’s reason', () => {
    expect(failedWords(list())).toBeUndefined()
    const now = Date.now()
    const words = failedWords(list({ error: 'TMDB: 401 api key abc123', errorAt: new Date(now - 3_600_000).toISOString(), fetchedAt: new Date(now - 2 * 86_400_000).toISOString(), nextFetchAt: new Date(now + 3_600_000).toISOString() }), now)
    expect(words).toBe('Couldn’t update this list 1 h ago. It’s shown as it was 2 days ago. It tries again in 1 hour.')
    expect(words).not.toContain('abc123')
  })

  it('opens what the library has, asks for what it may, and nothing else', () => {
    const movie = entry({ item: { id: 9, kind: 'movie', title: 'Kestrel Point', libraryId: 1, addedAt: '', genres: [], hdr: false, isNew: false, overview: '', totalSize: 0, watched: true } })
    expect(entryCard(movie)).toEqual({ kind: 'library', hint: 'Open the movie', hold: 'Mark unwatched' })
    expect(entryLabel(movie, entryCard(movie))).toBe('3. Kestrel Point, watched')
    const ask = entry({ requestState: 'requestable' })
    expect(entryCard(ask)).toEqual({ kind: 'missing', status: 'Request', request: { kind: 'movie', tmdbId: 55, title: 'Halfway to Juniper' }, hint: 'Request it' })
    expect(entryLabel(ask, entryCard(ask))).toBe('3. Halfway to Juniper, not in your library, OK to request')
    expect(entryCard(entry({ requestState: 'downloading' }))).toMatchObject({ status: 'Downloading', request: undefined, hint: 'Nothing to request' })
    expect(entryCard(entry({ mediaType: 'tv', requestState: 'requestable' }))).toMatchObject({ request: { kind: 'tv' } })
    expect(entryCard(entry())).toMatchObject({ status: 'Not in your library', request: undefined })
    expect(entryCard(entry({ found: false, tmdbId: undefined }))).toEqual({ kind: 'notFound', hint: 'Nothing to request' })
    expect(missingLine(entry())).toBe('1988 · Not in your library')
  })
})
