import { describe, expect, it } from 'vitest'
import {
  afterEpisodeDelete,
  afterSeasonDelete,
  arrivalSeason,
  askableSeasons,
  backAfterNext,
  deletedLine,
  episodeOnTop,
  episodePath,
  missingActions,
  onTopChip,
  seasonMark,
  seasonTabs,
  seasonWatchLine,
  showMark,
} from './showPage'

type Ep = { id: number; number: number; hasFile: boolean; watch?: { completed: boolean; positionMs: number; durationMs: number; fileId: number; updatedAt: string } }
type Se = { number: number; availableCount: number; watchedCount: number; episodeCount: number }

const ep = (number: number, over: Partial<Ep> = {}): Ep => ({ id: 100 + number, number, hasFile: true, ...over })
const watched = { completed: true, positionMs: 0, durationMs: 3_000_000, fileId: 1, updatedAt: '2026-11-01T00:00:00Z' }
const partly = { completed: false, positionMs: 1_200_000, durationMs: 3_000_000, fileId: 1, updatedAt: '2026-11-01T00:00:00Z' }
const se = (number: number, availableCount: number, watchedCount = 0, episodeCount = availableCount): Se => ({ number, availableCount, watchedCount, episodeCount })
const next = (seasonNumber: number, episodeId: number) => ({ seasonNumber, episodeId, episodeNumber: episodeId - 100, fileId: 1, title: '' })
const request = (status: 'pending' | 'approved') => ({ id: 1, status, mine: true, requestedBy: 'Sam', createdAt: '2026-11-01T00:00:00Z', seasons: [4] })
const show = (over: { seasons?: Se[]; nextEpisode?: ReturnType<typeof next>; fileCount?: number } = {}) => ({ seasons: [se(1, 3, 1)], fileCount: 3, ...over })

describe('seasonTabs', () => {
  it('puts Specials last and leaves out seasons with nothing on disk unless Show missing is on or it is open', () => {
    const seasons = [se(0, 2), se(2, 0), se(1, 4), se(3, 1)]
    expect(seasonTabs(seasons, false).map((s) => s.number)).toEqual([1, 3, 0])
    expect(seasonTabs(seasons, true).map((s) => s.number)).toEqual([1, 2, 3, 0])
    expect(seasonTabs(seasons, false, 2).map((s) => s.number)).toEqual([1, 2, 3, 0])
  })
})

describe('arrivalSeason', () => {
  it('keeps the season the address names', () => {
    expect(arrivalSeason(show({ nextEpisode: next(2, 105) }), 0)).toBe(0)
  })
  it('opens the up-next episode’s season from the show’s address', () => {
    expect(arrivalSeason(show({ seasons: [se(1, 3), se(2, 3)], nextEpisode: next(2, 105) }))).toBe(2)
  })
  it('opens the lowest season above 0 with files when nothing is up next, then Specials, then the lowest above 0', () => {
    expect(arrivalSeason(show({ seasons: [se(0, 2), se(1, 0), se(2, 3), se(3, 3)] }))).toBe(2)
    expect(arrivalSeason(show({ seasons: [se(0, 2), se(1, 0)] }))).toBe(0)
    expect(arrivalSeason(show({ seasons: [se(0, 0), se(2, 0), se(1, 0)] }))).toBe(1)
    expect(arrivalSeason(show({ seasons: [] }))).toBeUndefined()
  })
})

describe('episodeOnTop', () => {
  it('puts the up-next episode on top from the show’s address, saying why', () => {
    const eps = [ep(1, { watch: watched }), ep(2), ep(3)]
    expect(episodeOnTop({ kind: 'show' }, eps, show({ nextEpisode: next(1, 102) }))).toEqual({ episode: eps[1], why: 'next' })
  })
  it('says resume when the up-next episode is partly watched', () => {
    const eps = [ep(1, { watch: watched }), ep(2, { watch: partly }), ep(3)]
    expect(episodeOnTop({ kind: 'show' }, eps, show({ nextEpisode: next(1, 102) })).why).toBe('resume')
  })
  it('says Start here when nothing of the show is watched', () => {
    const eps = [ep(1), ep(2)]
    expect(episodeOnTop({ kind: 'show' }, eps, show({ seasons: [se(1, 2), se(2, 3)], nextEpisode: next(1, 101) }))).toEqual({ episode: eps[0], why: 'start' })
    // Something watched in another season: up next, not a start.
    expect(episodeOnTop({ kind: 'show' }, eps, show({ seasons: [se(1, 2), se(2, 3, 1)], nextEpisode: next(1, 101) })).why).toBe('next')
  })
  it('puts the first episode on disk on top with Watched it all when nothing is up next', () => {
    const eps = [ep(1, { hasFile: false }), ep(2, { watch: watched }), ep(3, { watch: watched })]
    expect(episodeOnTop({ kind: 'show' }, eps, show())).toEqual({ episode: eps[1], why: 'again' })
  })
  it('has no reason for a show with nothing on disk', () => {
    const eps = [ep(2, { hasFile: false }), ep(1, { hasFile: false })]
    expect(episodeOnTop({ kind: 'show' }, eps, show({ fileCount: 0 }))).toEqual({ episode: eps[1] })
  })
  it('puts a season’s first unfinished episode on disk on top, else its first on disk, else its first', () => {
    const eps = [ep(3), ep(1, { watch: watched }), ep(2, { watch: partly }), ep(4, { hasFile: false })]
    expect(episodeOnTop({ kind: 'season' }, eps, show())).toEqual({ episode: eps[2] })
    const done = [ep(2, { watch: watched }), ep(1, { hasFile: false }), ep(3, { watch: watched })]
    expect(episodeOnTop({ kind: 'season' }, done, show())).toEqual({ episode: done[0] })
    const none = [ep(2, { hasFile: false }), ep(1, { hasFile: false })]
    expect(episodeOnTop({ kind: 'season' }, none, show())).toEqual({ episode: none[1] })
    expect(episodeOnTop({ kind: 'season' }, [], show())).toEqual({ episode: undefined })
  })
  it('puts the episode the address names on top, without a reason, even a watched or missing one', () => {
    const eps = [ep(1), ep(2, { watch: watched }), ep(3, { hasFile: false })]
    expect(episodeOnTop({ kind: 'episode', id: 102 }, eps, show({ nextEpisode: next(1, 101) }))).toEqual({ episode: eps[1] })
    expect(episodeOnTop({ kind: 'episode', id: 103 }, eps, show())).toEqual({ episode: eps[2] })
  })
  it('falls back to the season’s pick when the named episode is gone', () => {
    const eps = [ep(1, { watch: watched }), ep(2)]
    expect(episodeOnTop({ kind: 'episode', id: 999 }, eps, show())).toEqual({ episode: eps[1], gone: true })
  })
  it('has a chip for every reason', () => {
    expect(Object.values(onTopChip)).toEqual(['Up next', 'Up next · resume', 'Start here', 'Watched it all'])
  })
})

describe('after a delete', () => {
  it('moves to the next episode in the row, else the one before', () => {
    const eps = [ep(3), ep(1), ep(2)]
    expect(afterEpisodeDelete(eps, 102)).toEqual({ episode: eps[0], which: 'next' })
    expect(afterEpisodeDelete(eps, 103)).toEqual({ episode: eps[2], which: 'previous' })
    expect(afterEpisodeDelete([ep(1)], 101)).toBeUndefined()
    expect(afterEpisodeDelete(eps, 999)).toBeUndefined()
  })
  it('says what was deleted and what is on top now', () => {
    expect(deletedLine('Neap', 'next')).toBe('Deleted “Neap”. Showing the next episode.')
    expect(deletedLine('Ebb', 'previous')).toBe('Deleted “Ebb”. Showing the one before.')
    expect(deletedLine('Ebb')).toBe('Deleted “Ebb”.')
  })
  it('opens the next season’s tab, else the one before', () => {
    const tabs = [{ number: 1 }, { number: 2 }, { number: 0 }]
    expect(afterSeasonDelete(tabs, 1)).toBe(2)
    expect(afterSeasonDelete(tabs, 2)).toBe(0)
    expect(afterSeasonDelete(tabs, 0)).toBe(2)
    expect(afterSeasonDelete([{ number: 1 }], 1)).toBeUndefined()
  })
})

describe('the season’s and the show’s lines', () => {
  it('counts a season’s episodes and how many are watched', () => {
    expect(seasonWatchLine(se(3, 7, 6))).toBe('7 episodes · 6 watched')
    expect(seasonWatchLine(se(3, 8, 8))).toBe('8 episodes · all watched')
    expect(seasonWatchLine(se(3, 4, 1, 10))).toBe('4 of 10 episodes · 1 watched')
    expect(seasonWatchLine(se(1, 1, 0))).toBe('1 episode · 0 watched')
    expect(seasonWatchLine(se(4, 0, 0, 6))).toBe('6 episodes · none in the library')
  })
  it('marks a season watched, or says it is', () => {
    expect(seasonMark(se(3, 7, 6))).toEqual({ label: 'Mark season 3 watched', watched: true })
    expect(seasonMark(se(3, 7, 7))).toEqual({ label: '✓ Season 3 watched', watched: false })
    expect(seasonMark(se(0, 2, 0))).toEqual({ label: 'Mark Specials watched', watched: true })
    expect(seasonMark(se(4, 0, 0, 6))).toBeUndefined()
  })
  it('marks the show watched over every season on disk', () => {
    expect(showMark([se(1, 3, 3), se(2, 3, 1)])).toEqual({ label: 'Mark show watched', watched: true })
    expect(showMark([se(1, 3, 3), se(2, 0, 0, 5)])).toEqual({ label: '✓ Show watched', watched: false })
    expect(showMark([se(1, 0, 0, 5)])).toBeUndefined()
  })
  it('offers the seasons above 0 that aired, are missing something and were not asked for', () => {
    const seasons = [
      { number: 0, episodeCount: 3, availableCount: 0, airDate: '2020-01-01' },
      { number: 1, episodeCount: 3, availableCount: 3, airDate: '2020-01-01' },
      { number: 2, episodeCount: 3, availableCount: 1, airDate: '2021-01-01' },
      { number: 3, episodeCount: 3, availableCount: 0, airDate: '2027-01-01' },
      { number: 4, episodeCount: 3, availableCount: 0, airDate: '2022-01-01', request: request('pending') },
    ]
    expect(askableSeasons(seasons, '2026-11-11')).toEqual([2])
  })
})

describe('missingActions', () => {
  const ctx = { admin: false, canRequest: true, seasonAskable: true, managed: true, seasonUnmonitored: false }
  const gap = { hasFile: false, aired: true }
  it('lets a member request the season of a missing episode, and an admin search for it', () => {
    expect(missingActions({ ...gap, monitored: true }, ctx)).toEqual({ request: true, requested: false, search: false, monitor: false, inbox: false, coming: false })
    expect(missingActions({ ...gap, monitored: true }, { ...ctx, admin: true })).toEqual({ request: false, requested: false, search: true, monitor: false, inbox: false, coming: false })
  })
  it('lets anyone who can request ask for a season not in the library, and an admin monitor it', () => {
    expect(missingActions(gap, { ...ctx, admin: true, seasonUnmonitored: true })).toEqual({ request: true, requested: false, search: false, monitor: true, inbox: false, coming: false })
    expect(missingActions(gap, { ...ctx, canRequest: false })).toEqual({ request: false, requested: false, search: false, monitor: false, inbox: false, coming: false })
    expect(missingActions(gap, { ...ctx, seasonAskable: false }).request).toBe(false)
  })
  it('shows a waiting request as requested, and to an admin as a way to the Inbox', () => {
    const e = { ...gap, request: request('pending') }
    expect(missingActions(e, ctx)).toMatchObject({ requested: true, inbox: false, request: false })
    expect(missingActions(e, { ...ctx, admin: true })).toMatchObject({ requested: false, inbox: true })
  })
  it('says it is coming while it downloads, once approved and before it airs', () => {
    const coming = { request: false, requested: false, search: false, monitor: false, inbox: false, coming: true }
    expect(missingActions({ ...gap, download: { state: 'downloading', progress: 0.5 } }, ctx)).toEqual(coming)
    expect(missingActions({ ...gap, request: request('approved') }, ctx)).toEqual(coming)
    expect(missingActions({ hasFile: false, aired: false }, ctx)).toEqual(coming)
  })
  it('has nothing for an episode with a file', () => {
    expect(missingActions({ hasFile: true, aired: true }, ctx)).toEqual({ request: false, requested: false, search: false, monitor: false, inbox: false, coming: false })
  })
})

describe('addresses', () => {
  it('builds an episode’s address', () => {
    expect(episodePath(12, 3, 77)).toBe('/shows/12/seasons/3/episodes/77')
  })
  it('moves the player’s Back on to the next episode when it was on the show', () => {
    const n = { showId: 12, seasonNumber: 3, episodeId: 78 }
    expect(backAfterNext('/shows/12/seasons/3/episodes/77', n)).toBe('/shows/12/seasons/3/episodes/78')
    expect(backAfterNext('/shows/12', n)).toBe('/shows/12/seasons/3/episodes/78')
    expect(backAfterNext('/', n)).toBe('/')
    expect(backAfterNext('/lists/4', n)).toBe('/lists/4')
    expect(backAfterNext('/showsy', n)).toBe('/showsy')
    expect(backAfterNext(undefined, n)).toBeUndefined()
  })
})
