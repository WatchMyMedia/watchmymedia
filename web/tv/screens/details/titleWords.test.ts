import { describe, expect, it } from 'vitest'
import type { components } from '../../../src/api/schema'
import {
  airDay, cardTitle, episodeFactLine, episodeKickerWords, episodeWatch, movieFactLine, movieShortLine, movieWatch, nextEpisodeLine, showFactLine, tabWords,
} from './titleWords'

type Episode = components['schemas']['Episode']
type MediaFile = components['schemas']['MediaFile']

const MIN = 60_000
const now = new Date(2026, 10, 23, 20, 12).getTime()
const today = new Date(now)
const watch = (positionMs: number, durationMs: number, completed = false, updatedAt = '') => ({ positionMs, durationMs, completed, updatedAt })

describe('a movie’s first lines', () => {
  const m = { year: 2026, runtimeMin: 134, contentRating: 'PG-13', genres: ['Thriller', 'Mystery'], rating: 7.62 }

  it('says the facts in one line, with the rating in words', () => {
    expect(movieFactLine(m)).toBe('2026 · 2 h 14 min · PG-13 · Thriller · rated 7.6 of 10')
    expect(movieShortLine(m)).toBe('2026 · 2 h 14 min · PG-13')
  })

  it('says what is left and when it ends, partway', () => {
    expect(movieWatch({ ...m, watch: watch(62 * MIN, 134 * MIN) }, now, 'en-US')).toEqual({ play: 'Resume', where: '1 h 12 min left', ends: 'Ends at 9:24 PM', progress: 62 / 134 })
  })

  it('says how long it is and when it would end, not started', () => {
    expect(movieWatch(m, now, 'en-US')).toEqual({ play: 'Play', where: '2 h 14 min', ends: 'Ends at 10:26 PM if you start now' })
  })

  it('says when it was watched, finished', () => {
    expect(movieWatch({ ...m, watch: watch(0, 1, true, '2026-11-02T21:00:00Z') }, now, 'en-US')).toEqual({ play: 'Play again', where: 'Watched on November 2' })
  })
})

function file(over: Partial<MediaFile> = {}): MediaFile {
  return { id: 9, libraryId: 2, path: '/s.mkv', relativePath: 's.mkv', size: 1, modifiedAt: '', status: 'present', parsed: {} as MediaFile['parsed'], firstSeenAt: '', addedAt: '', lastSeenAt: '', matchStatus: 'matched', matchLocked: false, matchIgnored: false, durationMs: 47 * MIN, ...over }
}

function episode(over: Partial<Episode> = {}): Episode {
  return { id: 7, seasonId: 3, number: 7, title: 'The Long Dark', overview: '', aired: true, airDate: '2026-10-23', hasFile: true, isNew: false, files: [file()], runtimeMin: 47, ...over }
}

describe('an episode’s first lines', () => {
  it('names the show, the season and the episode', () => {
    expect(episodeKickerWords('Harbor Lights', 3, 7)).toBe('Harbor Lights · Season 3 · Episode 7')
    expect(episodeKickerWords('Harbor Lights', 0, 2)).toBe('Harbor Lights · Specials · Episode 2')
  })

  it('says its length, rating and air day', () => {
    expect(episodeFactLine(episode(), { contentRating: 'TV-14' }, today, 'en-US')).toBe('47 min · TV-14 · aired October 23')
    expect(episodeFactLine(episode({ aired: false, airDate: '2026-11-24' }), {}, today, 'en-US')).toBe('47 min · airs tomorrow')
    expect(airDay('2026-11-22', today)).toBe('yesterday')
  })

  it('says what is left partway, and when it would end otherwise', () => {
    expect(episodeWatch(episode({ watch: watch(18 * MIN, 47 * MIN) }), now, 'en-US')).toEqual({ play: 'Resume', where: '29 min left', ends: 'Ends at 8:41 PM', progress: 18 / 47 })
    expect(episodeWatch(episode({ runtimeMin: 50 }), now, 'en-US')).toEqual({ play: 'Play', where: 'Not watched yet', ends: 'Ends at 9:02 PM if you start now' })
    expect(episodeWatch(episode({ watch: watch(0, 1, true) }), now)).toEqual({ play: 'Play again', where: 'Watched' })
    expect(episodeWatch(episode({ hasFile: false, files: [] }), now)).toBeUndefined()
  })

  it('titles a card with its number', () => {
    expect(cardTitle({ number: 8, title: 'Ebb' })).toBe('8 · Ebb')
    expect(cardTitle({ number: 9, title: '' })).toBe('9 · Episode 9')
  })
})

describe('a show’s lines', () => {
  const seasons = [1, 2, 3].map((number) => ({ number })) as components['schemas']['Season'][]

  it('says its years, seasons and whether it still airs', () => {
    expect(showFactLine({ year: 2022, status: 'Returning Series', seasons, contentRating: 'TV-14', genres: ['Drama'] })).toBe('2022 to now · 3 seasons · TV-14 · Drama · still airing')
    expect(showFactLine({ year: 2014, lastAirDate: '2019-05-01', status: 'Ended', seasons, genres: [] })).toBe('2014 to 2019 · 3 seasons · ended')
  })

  it('words the season tabs', () => {
    expect(tabWords({ availableCount: 6, watchedCount: 4, episodeCount: 6 }, true, false)).toEqual({ count: '4 of 6 watched', done: false })
    expect(tabWords({ availableCount: 2, watchedCount: 0, episodeCount: 2 }, false, false)).toEqual({ count: '2', done: false })
    expect(tabWords({ availableCount: 8, watchedCount: 8, episodeCount: 8 }, false, false)).toEqual({ done: true })
    expect(tabWords({ availableCount: 4, watchedCount: 1, episodeCount: 10 }, true, true)).toEqual({ count: '4 of 10 here', done: false })
  })

  it('names the next episode to air', () => {
    expect(nextEpisodeLine([{ number: 9, aired: false, airDate: '2026-11-26' }, { number: 8, aired: true, airDate: '2026-11-01' }], today, 'en-US')).toBe('Next episode Thu, Nov 26')
    expect(nextEpisodeLine([{ number: 9, aired: false, airDate: '2026-11-24' }], today, 'en-US')).toBe('Next episode tomorrow')
    expect(nextEpisodeLine([{ number: 8, aired: true, airDate: '2026-11-01' }], today)).toBeUndefined()
  })
})
