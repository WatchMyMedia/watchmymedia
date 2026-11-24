import { describe, expect, it } from 'vitest'
import {
  addedWords,
  chipCounts,
  episodesWords,
  filtersActive,
  leftToWatch,
  libraryCountsLine,
  needsMatchWords,
  newFlag,
  parsePrefs,
  pictureChoices,
  posterMin,
  prefsKey,
  ratedChoices,
  serverFilters,
  showCardLine,
  showChips,
  splitFilter,
  startingChoices,
  youWords,
} from './browsePrefs'

describe('a library’s remembered choices', () => {
  it('keys them by library', () => {
    expect(prefsKey(4)).toBe('wmm.library.4')
  })

  it('reads back what it knows and leaves out the rest', () => {
    expect(parsePrefs(JSON.stringify({ sort: 'added', filter: 'unwatched', genre: 'Drama', decade: 1990, tab: 'all', view: 'list', size: 4, columns: { quality: true, size: true } }))).toEqual({
      sort: 'added',
      filter: 'unwatched',
      genre: 'Drama',
      decade: 1990,
      tab: 'all',
      view: 'list',
      size: 4,
      columns: { size: true },
    })
    expect(parsePrefs(JSON.stringify({ sort: 'loudness', filter: 'mine', tab: 'files', view: 'cards', size: 9, decade: -1 }))).toEqual({})
    expect(parsePrefs(JSON.stringify({ filter: '' }))).toEqual({ filter: '' })
    expect(parsePrefs(JSON.stringify({ filter: 'inProgress', picture: 'hdr', rated: 'PG-13', tab: 'collections' }))).toEqual({ filter: 'inProgress', picture: 'hdr', rated: 'PG-13', tab: 'collections' })
    expect(parsePrefs('not json')).toEqual({})
    expect(parsePrefs('null')).toEqual({})
    expect(parsePrefs(null)).toEqual({})
  })

  it('starts from a See all’s choices when the address brings any, else what was remembered', () => {
    const prefs = { sort: 'rating' as const, filter: 'unwatched' as const, genre: 'Drama' }
    const none = { picture: '', rated: '' }
    expect(startingChoices({}, prefs)).toEqual({ sort: 'rating', filter: 'unwatched', genre: 'Drama', decade: 0, ...none })
    expect(startingChoices({}, { ...prefs, picture: '4k', rated: 'R' })).toEqual({ sort: 'rating', filter: 'unwatched', picture: '4k', rated: 'R', genre: 'Drama', decade: 0 })
    expect(startingChoices({ sort: 'added' }, prefs)).toEqual({ sort: 'added', filter: '', genre: '', decade: 0, ...none })
    expect(startingChoices({ genre: 'Western' }, prefs)).toEqual({ sort: 'title', filter: '', genre: 'Western', decade: 0, ...none })
    expect(startingChoices({ filter: 'hdr' }, prefs)).toEqual({ sort: 'title', filter: '', picture: 'hdr', rated: '', genre: '', decade: 0 })
    expect(startingChoices({}, {})).toEqual({ sort: 'title', filter: '', genre: '', decade: 0, ...none })
  })

  it('reads a picture filter kept before chips and pictures combined', () => {
    expect(parsePrefs(JSON.stringify({ filter: '4k' }))).toEqual({ picture: '4k' })
    expect(splitFilter('unwatched')).toEqual({ filter: 'unwatched', picture: '' })
    expect(splitFilter(undefined)).toEqual({ filter: '', picture: '' })
  })

  it('sends the chip and the picture together', () => {
    expect(serverFilters({ filter: 'inProgress', picture: '4k' })).toEqual(['inProgress', '4k'])
    expect(serverFilters({ filter: '', picture: '' })).toEqual([])
  })

  it('offers Clear filters only when something narrows the grid', () => {
    const none = { filter: '', picture: '', rated: '', genre: '', decade: 0 } as const
    expect(filtersActive(none)).toBe(false)
    expect(filtersActive({ ...none, picture: 'hdr' })).toBe(true)
    expect(filtersActive({ ...none, filter: 'following' })).toBe(true)
    expect(filtersActive({ ...none, rated: 'unrated' })).toBe(true)
    expect(filtersActive({ ...none, genre: 'Drama' })).toBe(true)
    expect(filtersActive({ ...none, decade: 1980 })).toBe(true)
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
    expect(libraryCountsLine('movie', { items: 236, episodes: 0, continuing: 0, unwatched: 58, addedThisMonth: 12 })).toBe('236 movies · 58 you haven’t watched · 12 added this month')
    expect(libraryCountsLine('movie', { items: 5, episodes: 0, continuing: 0, unwatched: 4, addedThisMonth: 0 })).toBe('5 movies · 4 you haven’t watched')
  })

  it('counts shows, episodes and those still airing', () => {
    expect(libraryCountsLine('tv', { items: 48, episodes: 1912, continuing: 23 }, 19)).toBe('48 shows · 1,912 episodes · 23 still airing')
    expect(libraryCountsLine('tv', { items: 1, episodes: 1, continuing: 0 }, 0)).toBe('1 show · 1 episode')
    expect(libraryCountsLine('tv', { items: 48, episodes: 1912, continuing: 23, newEpisodes: 4 })).toBe('48 shows · 1,912 episodes · 23 still airing · 4 with new episodes for you')
  })

  it('tells an admin how many files need a match', () => {
    expect(needsMatchWords({ unmatchedCount: 2, pendingCount: 1 })).toBe('3 files need a match')
    expect(needsMatchWords({ unmatchedCount: 1, pendingCount: 0 })).toBe('1 file needs a match')
    expect(needsMatchWords({ unmatchedCount: 0, pendingCount: 0 })).toBeUndefined()
  })

  it('offers the filters the library has', () => {
    expect(showChips('movie').map((c) => c.label)).toEqual(['All', 'Unwatched', 'In progress'])
    expect(showChips('tv').map((c) => c.value)).toEqual(['', 'unwatched', 'following', 'continuing', 'ended'])
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

  it('counts each chip from the totals', () => {
    expect(chipCounts('movie', { items: 5, continuing: 0, unwatched: 4, inProgress: 1 })).toEqual({ unwatched: 4, inProgress: 1 })
    expect(chipCounts('tv', { items: 48, continuing: 23, unwatched: 19, following: 9 })).toEqual({ unwatched: 19, following: 9, continuing: 23, ended: 25 })
    expect(chipCounts('tv', undefined)).toEqual({})
  })

  it('lists the ratings with their counts, unrated last as the server sends it', () => {
    expect(ratedChoices(['PG', 'PG-13', 'unrated'], { PG: 2, 'PG-13': 1, unrated: 2 })).toEqual([
      { value: '', label: 'Any' },
      { value: 'PG', label: 'PG (2)' },
      { value: 'PG-13', label: 'PG-13 (1)' },
      { value: 'unrated', label: 'Not rated (2)' },
    ])
    expect(ratedChoices(undefined, undefined)).toEqual([{ value: '', label: 'Any' }])
  })

  it('says what is left of a show', () => {
    expect(leftToWatch({ unwatchedCount: 2, episodeCount: 9, watched: false })).toEqual({ text: '2 episodes', left: true })
    expect(leftToWatch({ unwatchedCount: 0, episodeCount: 9, watched: true })).toEqual({ text: 'All watched', left: false })
    expect(leftToWatch({ unwatchedCount: 9, episodeCount: 9, watched: false })).toEqual({ text: 'Not started', left: false })
    expect(leftToWatch({ episodeCount: 9, watched: false })).toBeUndefined()
    expect(showCardLine({ seasonCount: 3, unwatchedCount: 2, episodeCount: 30, watched: false })).toBe('3 seasons · 2 left')
    expect(showCardLine({ seasonCount: 1, unwatchedCount: 0, episodeCount: 6, watched: true })).toBe('1 season · all watched')
    expect(showCardLine({ seasonCount: 2, unwatchedCount: 6, episodeCount: 6, watched: false })).toBe('2 seasons · not started')
    expect(showCardLine({ episodeCount: 6, watched: false })).toBe('6 episodes')
    expect(showCardLine({ watched: false, year: 2019 })).toBe('2019')
  })

  it('flags how many episodes are new', () => {
    expect(newFlag({ kind: 'show', isNew: true, newEpisodeCount: 2 })).toBe('2 new')
    expect(newFlag({ kind: 'show', isNew: true })).toBe('New')
    expect(newFlag({ kind: 'movie', isNew: true })).toBe('New')
    expect(newFlag({ kind: 'show', isNew: false, newEpisodeCount: 0 })).toBeUndefined()
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
