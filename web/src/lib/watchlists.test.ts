import { describe, expect, it } from 'vitest'
import type { MediaListEntry } from './mediaLists'
import {
  canUseName, deleteWords, failureWords, nameProblem, pressAction, titleActionLabel, watchlistCounts, watchlistLabel, watchlistRowLine, watchlistSort, watchlistTitle, watchlistType, withoutHeld,
} from './watchlists'

const lists = [{ id: 1, name: 'Watchlist' }, { id: 2, name: 'Halloween' }, { id: 3, name: 'With the kids' }]

describe('the Watchlist button', () => {
  it.each([
    ['on none', [], lists, 'Watchlist'],
    ['on the first', [1], lists, 'On Watchlist'],
    ['on another by name', [2], lists, 'On Halloween'],
    ['on two', [1, 2], lists, 'On 2 watchlists'],
    ['on one not loaded yet', [9], undefined, 'On Watchlist'],
  ])('says what holds a title %s', (_case, holding, known, label) => {
    expect(watchlistLabel(holding, known)).toBe(label)
  })

  it.each([
    [undefined, false, 'save'],
    [0, false, 'save'],
    [1, false, 'save'],
    [1, true, 'remove'],
    [2, false, 'picker'],
    [3, true, 'picker'],
  ] as const)('with %s watchlists and saved %s, one press does %s', (count, saved, action) => {
    expect(pressAction(count, saved)).toBe(action)
  })
})

describe('the action on a missing title', () => {
  it.each([
    ['none yet', [], false, 'Add to Watchlist'],
    ['one, saved', [lists[0]], true, 'Remove from Watchlist'],
    ['one renamed', [{ name: 'Movie night' }], false, 'Add to Movie night'],
    ['several', lists, false, 'Save to watchlist…'],
  ])('with %s says %s', (_case, known, saved, label) => {
    expect(titleActionLabel(known, saved)).toBe(label)
  })
})

describe('counts', () => {
  it.each([
    [12, 9, '12 titles · 9 in your library'],
    [6, 6, '6 titles · 6 in your library'],
    [1, 0, '1 title · 0 in your library'],
    [0, 0, 'No titles yet'],
  ])('%s titles with %s in the library read %s', (titles, inLibrary, words) => {
    expect(watchlistCounts({ titles, inLibrary })).toBe(words)
  })

  it.each([
    [9, 12, '9 of 12 in your library'],
    [6, 6, undefined],
  ])('a row with %s of %s in the library has the line %s', (inLibrary, total, line) => {
    expect(watchlistRowLine(inLibrary, total)).toBe(line)
  })
})

describe('names', () => {
  it.each([
    ['a new name', 'Rainy days', undefined, undefined],
    ['one already used, whatever the case', ' halloween ', undefined, 'You already have a watchlist called Halloween.'],
    ['its own name when renaming', 'Halloween', 2, undefined],
    ['nothing yet', '  ', undefined, undefined],
  ])('%s', (_case, name, self, problem) => {
    expect(nameProblem(name, lists, self)).toBe(problem)
  })

  it.each([
    ['a new name', 'Rainy days', undefined, true],
    ['an empty one', '   ', undefined, false],
    ['one over 60 characters', 'x'.repeat(61), undefined, false],
    ['one used', 'Halloween', undefined, false],
    ['the name it has, renaming', 'Halloween', lists[1], false],
    ['another case of its own, renaming', 'HALLOWEEN', lists[1], true],
  ])('Create takes %s: %s', (_case, name, self, ok) => {
    expect(canUseName(name, lists, self)).toBe(ok)
  })
})

describe('Delete’s question', () => {
  it.each([
    [{ name: 'Halloween', inLibrary: 6 }, 'Its 6 titles stay in your library. This can’t be undone.'],
    [{ name: 'Watchlist', inLibrary: 1 }, 'Its 1 title stays in your library. This can’t be undone.'],
    [{ name: 'With the kids', inLibrary: 0 }, 'This can’t be undone.'],
  ])('for %o', (w, body) => {
    expect(deleteWords(w)).toEqual({ title: `Delete ${w.name}?`, body })
  })
})

describe('a refused change', () => {
  it.each([
    ['says the server’s reason people can act on', { status: 422, message: 'A watchlist holds up to 500 titles.' }, 'A watchlist holds up to 500 titles.'],
    ['says the fallback for anything else', { status: 500, message: 'database is locked' }, 'Couldn’t save.'],
    ['says the fallback when the server wasn’t reached', new TypeError('Failed to fetch'), 'Couldn’t save.'],
  ])('%s', (_case, error, words) => {
    expect(failureWords(error, 'Couldn’t save.')).toBe(words)
  })
})

describe('addresses and titles', () => {
  it.each([
    ['movie', 11, { mediaType: 'movie', tmdbId: 11 }],
    ['show', 12, { mediaType: 'tv', tmdbId: 12 }],
    ['tv', 13, { mediaType: 'tv', tmdbId: 13 }],
    ['movie', undefined, undefined],
    ['unmatched', 14, undefined],
  ])('a %s with TMDB id %s is %o', (kind, tmdbId, title) => {
    expect(watchlistTitle(kind, tmdbId)).toEqual(title)
  })

  it('reads only the sorts and types it knows from an address', () => {
    expect([watchlistSort('title'), watchlistSort('released'), watchlistSort('added'), watchlistSort('rank')]).toEqual(['title', 'released', undefined, undefined])
    expect([watchlistType('movie'), watchlistType('tv'), watchlistType('all')]).toEqual(['movie', 'tv', undefined])
  })
})

describe('a page of titles while removals wait for Undo', () => {
  const entry = (rank: number, tmdbId: number, inLibrary: boolean): MediaListEntry =>
    ({ rank, tmdbId, mediaType: 'movie', title: `Title ${rank}`, found: true, item: inLibrary ? ({ id: rank } as MediaListEntry['item']) : undefined })
  const page = { items: [entry(1, 101, true), entry(2, 202, false), entry(3, 303, true)], total: 12, inLibrary: 9 }

  it('leaves the held ones out and takes them off the counts', () => {
    const shown = withoutHeld(page, (t) => t.tmdbId === 101 || t.tmdbId === 202)
    expect(shown.items.map((e) => e.rank)).toEqual([3])
    expect([shown.total, shown.inLibrary]).toEqual([10, 8])
  })

  it('is the page as it was when nothing is held', () => {
    expect(withoutHeld(page, () => false)).toBe(page)
  })
})
