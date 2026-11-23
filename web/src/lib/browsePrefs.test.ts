import { describe, expect, it } from 'vitest'
import { addedWords, filtersActive, libraryCountsLine, needsMatchWords, parsePrefs, pictureChoices, posterMin, prefsKey, showChips, startingChoices, youWords, episodesWords } from './browsePrefs'

describe('a library’s remembered choices', () => {
  it('keys them by library', () => {
    expect(prefsKey(4)).toBe('wmm.library.4')
  })

  it('reads back what it knows and leaves out the rest', () => {
    expect(parsePrefs(JSON.stringify({ sort: 'added', filter: 'unwatched', genre: 'Drama', decade: 1990, tab: 'all', view: 'list', size: 4, columns: { quality: true } }))).toEqual({
      sort: 'added',
      filter: 'unwatched',
      genre: 'Drama',
      decade: 1990,
      tab: 'all',
      view: 'list',
      size: 4,
      columns: { quality: true, size: false },
    })
    expect(parsePrefs(JSON.stringify({ sort: 'loudness', filter: 'mine', tab: 'files', view: 'cards', size: 9, decade: -1 }))).toEqual({})
    expect(parsePrefs(JSON.stringify({ filter: '' }))).toEqual({ filter: '' })
    expect(parsePrefs('not json')).toEqual({})
    expect(parsePrefs('null')).toEqual({})
    expect(parsePrefs(null)).toEqual({})
  })

  it('starts from a See all’s choices when the address brings any, else what was remembered', () => {
    const prefs = { sort: 'rating' as const, filter: 'unwatched' as const, genre: 'Drama' }
    expect(startingChoices({}, prefs)).toEqual({ sort: 'rating', filter: 'unwatched', genre: 'Drama', decade: 0 })
    expect(startingChoices({ sort: 'added' }, prefs)).toEqual({ sort: 'added', filter: '', genre: '', decade: 0 })
    expect(startingChoices({ genre: 'Western' }, prefs)).toEqual({ sort: 'title', filter: '', genre: 'Western', decade: 0 })
    expect(startingChoices({}, {})).toEqual({ sort: 'title', filter: '', genre: '', decade: 0 })
  })

  it('offers Clear filters only when something narrows the grid', () => {
    expect(filtersActive({ filter: '', genre: '', decade: 0 })).toBe(false)
    expect(filtersActive({ filter: 'hdr', genre: '', decade: 0 })).toBe(true)
    expect(filtersActive({ filter: '', genre: 'Drama', decade: 0 })).toBe(true)
    expect(filtersActive({ filter: '', genre: '', decade: 1980 })).toBe(true)
  })

  it('sizes poster columns, smaller on phones', () => {
    expect(posterMin(3)).toBe(152)
    expect(posterMin(1)).toBe(112)
    expect(posterMin(5)).toBe(208)
    expect(posterMin(9)).toBe(208)
    expect(posterMin(3, true)).toBe(104)
  })
})

describe('a library’s words', () => {
  it('counts movies and what is left to watch', () => {
    expect(libraryCountsLine('movie', { items: 236, episodes: 0, continuing: 0 }, 58)).toBe('236 movies · 58 you haven’t watched')
    expect(libraryCountsLine('movie', { items: 1, episodes: 0, continuing: 0 }, 0)).toBe('1 movie · you’ve watched them all')
    expect(libraryCountsLine('movie', { items: 3, episodes: 0, continuing: 0 }, undefined)).toBe('3 movies')
    expect(libraryCountsLine('movie', undefined, 3)).toBe('')
  })

  it('counts shows, episodes and those still airing', () => {
    expect(libraryCountsLine('tv', { items: 48, episodes: 1912, continuing: 23 }, 19)).toBe('48 shows · 1,912 episodes · 23 still airing')
    expect(libraryCountsLine('tv', { items: 1, episodes: 1, continuing: 0 }, 0)).toBe('1 show · 1 episode')
  })

  it('tells an admin how many files need a match', () => {
    expect(needsMatchWords({ unmatchedCount: 2, pendingCount: 1 })).toBe('3 files need a match')
    expect(needsMatchWords({ unmatchedCount: 1, pendingCount: 0 })).toBe('1 file needs a match')
    expect(needsMatchWords({ unmatchedCount: 0, pendingCount: 0 })).toBeUndefined()
  })

  it('offers the filters the library has', () => {
    expect(showChips('movie').map((c) => c.label)).toEqual(['All', 'Unwatched'])
    expect(showChips('tv').map((c) => c.value)).toEqual(['', 'unwatched', 'continuing', 'ended'])
    expect(pictureChoices('movie').map((c) => c.value)).toEqual(['4k', 'hdr'])
    expect(pictureChoices('tv').map((c) => c.value)).toEqual(['4k'])
  })

  it('says where the person stands with a title', () => {
    expect(youWords({ kind: 'movie', watched: true })).toEqual({ text: 'Watched', gold: false })
    expect(youWords({ kind: 'show', watched: true })).toEqual({ text: 'All watched', gold: false })
    expect(youWords({ kind: 'movie', watched: false, progress: 0.56, runtimeMin: 118 })).toEqual({ text: '52 min left', gold: true })
    expect(youWords({ kind: 'movie', watched: false, progress: 0.3, runtimeMin: 124 })).toEqual({ text: '1 h 27 min left', gold: true })
    expect(youWords({ kind: 'movie', watched: false, progress: 0.4 })).toEqual({ text: '40% watched', gold: true })
    expect(youWords({ kind: 'movie', watched: false, isNew: true })).toEqual({ text: 'New', gold: true })
    expect(youWords({ kind: 'movie', watched: false })).toBeUndefined()
  })

  it('counts a show’s episodes', () => {
    expect(episodesWords(1)).toBe('1 episode')
    expect(episodesWords(1912)).toBe('1,912 episodes')
    expect(episodesWords(0)).toBe('')
  })
})

describe('when a title was added', () => {
  const now = new Date(2026, 10, 23, 20, 0).getTime()
  it('says today, yesterday, a day this year or a month in another', () => {
    expect(addedWords(new Date(2026, 10, 23, 9).toISOString(), now)).toBe('Today')
    expect(addedWords(new Date(2026, 10, 22, 23).toISOString(), now)).toBe('Yesterday')
    expect(addedWords(new Date(2026, 10, 20).toISOString(), now)).toMatch(/Nov 20/)
    expect(addedWords(new Date(2025, 11, 2).toISOString(), now)).toMatch(/Dec 2025/)
    expect(addedWords(undefined, now)).toBe('')
    expect(addedWords('nope', now)).toBe('')
  })
})
