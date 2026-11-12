import { afterEach, describe, expect, it, vi } from 'vitest'
import type { LiveProgramme } from '../api/liveTV'
import {
  availableFilters, channelInFilter, clampStart, dayStart, duration, episodeLine, fetchBlock, GUIDE_FILTERS, guideDays, HALF_HOUR, halfHourStart, HOUR,
  latestStart, layoutCells, moveSelection, nowAndNext, panelPicture, programmeLabel, progress, shortDayTime, showListings, startShowing, timeRange, windowSlots,
} from './liveGuide'

const T = (iso: string) => Date.parse(iso)
function programme(id: string, start: string, end: string, changes: Partial<LiveProgramme> = {}): LiveProgramme {
  return { id, start, end, title: `Show ${id}`, ...changes }
}
function strip(cells: ReturnType<typeof layoutCells>) {
  return cells.map((c) => c.kind === 'programme'
    ? { id: c.programme.id, left: Math.round(c.left * 100) / 100, width: Math.round(c.width * 100) / 100, before: c.before, after: c.after }
    : { gap: true, left: Math.round(c.left * 100) / 100, width: Math.round(c.width * 100) / 100, listingsEnd: c.listingsEnd })
}

afterEach(() => { vi.unstubAllEnvs() })

describe('the listings gate', () => {
  it('shows listings only from level 13 with the guide capability', () => {
    expect(showListings(13, { guide: true })).toBe(true)
    expect(showListings(14, { guide: true })).toBe(true)
    expect(showListings(12, { guide: true })).toBe(false)
    expect(showListings(undefined, { guide: true })).toBe(false)
    expect(showListings(13, { guide: false })).toBe(false)
    expect(showListings(13, undefined)).toBe(false)
  })
})

describe('guide cell layout', () => {
  const start = T('2026-11-02T01:00:00Z')
  const end = start + 2 * HOUR

  it('sizes cells by duration and clips them at the window’s edges', () => {
    const cells = layoutCells([
      programme('a', '2026-11-02T00:30:00Z', '2026-11-02T01:30:00Z'),
      programme('b', '2026-11-02T01:30:00Z', '2026-11-02T02:00:00Z'),
      programme('c', '2026-11-02T02:00:00Z', '2026-11-02T04:00:00Z'),
    ], start, end)
    expect(strip(cells)).toEqual([
      { id: 'a', left: 0, width: 25, before: true, after: false },
      { id: 'b', left: 25, width: 25, before: false, after: false },
      { id: 'c', left: 50, width: 50, before: false, after: true },
    ])
  })

  it('leaves out programmes wholly outside the window and ones with no length', () => {
    const cells = layoutCells([
      programme('gone', '2026-11-01T23:00:00Z', '2026-11-02T01:00:00Z'),
      programme('in', '2026-11-02T01:00:00Z', '2026-11-02T03:00:00Z'),
      programme('zero', '2026-11-02T02:00:00Z', '2026-11-02T02:00:00Z'),
      programme('later', '2026-11-02T03:00:00Z', '2026-11-02T04:00:00Z'),
    ], start, end)
    expect(strip(cells)).toEqual([{ id: 'in', left: 0, width: 100, before: false, after: false }])
  })

  it('keeps a gap between programmes a gap', () => {
    const cells = layoutCells([
      programme('a', '2026-11-02T01:00:00Z', '2026-11-02T01:30:00Z'),
      programme('b', '2026-11-02T02:00:00Z', '2026-11-02T03:00:00Z'),
    ], start, end)
    expect(strip(cells)).toEqual([
      { id: 'a', left: 0, width: 25, before: false, after: false },
      { gap: true, left: 25, width: 25, listingsEnd: undefined },
      { id: 'b', left: 50, width: 50, before: false, after: false },
    ])
  })

  it('starts a window with a gap when the first programme starts late', () => {
    const cells = layoutCells([programme('a', '2026-11-02T01:45:00Z', '2026-11-02T03:00:00Z')], start, end)
    expect(strip(cells)).toEqual([
      { gap: true, left: 0, width: 37.5, listingsEnd: undefined },
      { id: 'a', left: 37.5, width: 62.5, before: false, after: false },
    ])
  })

  it('marks where a channel’s listings end, and not a gap with more after it', () => {
    const ended = layoutCells([programme('a', '2026-11-02T00:00:00Z', '2026-11-02T01:30:00Z')], start, end)
    expect(strip(ended)).toEqual([
      { id: 'a', left: 0, width: 25, before: true, after: false },
      { gap: true, left: 25, width: 75, listingsEnd: T('2026-11-02T01:30:00Z') },
    ])
    const resumes = layoutCells([
      programme('a', '2026-11-02T00:00:00Z', '2026-11-02T01:30:00Z'),
      programme('b', '2026-11-02T03:30:00Z', '2026-11-02T04:00:00Z'),
    ], start, end)
    expect(resumes.at(-1)).toMatchObject({ kind: 'gap', listingsEnd: undefined })
    const allBefore = layoutCells([programme('a', '2026-11-02T00:00:00Z', '2026-11-02T00:30:00Z')], start, end)
    expect(strip(allBefore)).toEqual([{ gap: true, left: 0, width: 100, listingsEnd: T('2026-11-02T00:30:00Z') }])
    expect(strip(layoutCells([], start, end))).toEqual([{ gap: true, left: 0, width: 100, listingsEnd: undefined }])
  })

  it('starts an overlapping programme where the one before ends, in start order', () => {
    const cells = layoutCells([
      programme('b', '2026-11-02T01:20:00Z', '2026-11-02T02:00:00Z'),
      programme('a', '2026-11-02T01:00:00Z', '2026-11-02T01:30:00Z'),
      programme('inside', '2026-11-02T01:05:00Z', '2026-11-02T01:25:00Z'),
    ], start, end)
    expect(strip(cells)).toEqual([
      { id: 'a', left: 0, width: 25, before: false, after: false },
      { id: 'b', left: 25, width: 25, before: false, after: false },
      { gap: true, left: 50, width: 50, listingsEnd: T('2026-11-02T02:00:00Z') },
    ])
  })

  it('gives the repeated hour on the 25-hour day its real width', () => {
    vi.stubEnv('TZ', 'America/New_York')
    // 1:00 AM EDT (05:00Z) to 2:00 AM EST (07:00Z): two real hours, three on the clock.
    const dstStart = halfHourStart(T('2026-11-01T05:10:00Z'))
    expect(dstStart).toBe(T('2026-11-01T05:00:00Z'))
    const slots = windowSlots(dstStart, 2).map((d) => d.toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit', timeZoneName: 'short' }))
    expect(slots).toEqual(['1:00 AM EDT', '1:30 AM EDT', '1:00 AM EST', '1:30 AM EST'])
    const cells = layoutCells([
      programme('late', '2026-11-01T04:30:00Z', '2026-11-01T05:30:00Z'),
      // 1:30 AM EDT to 1:30 AM EST: the clock reads the same, an hour apart.
      programme('repeat', '2026-11-01T05:30:00Z', '2026-11-01T06:30:00Z'),
      programme('after', '2026-11-01T06:30:00Z', '2026-11-01T07:30:00Z'),
    ], dstStart, dstStart + 2 * HOUR)
    expect(strip(cells)).toEqual([
      { id: 'late', left: 0, width: 25, before: true, after: false },
      { id: 'repeat', left: 25, width: 50, before: false, after: false },
      { id: 'after', left: 75, width: 25, before: false, after: true },
    ])
    expect(timeRange(T('2026-11-01T05:30:00Z'), T('2026-11-01T06:30:00Z'), ' to ', 'en-US')).toBe('1:30 to 1:30 AM')
    // The second 1:00 AM keeps its half hour rather than jumping back an hour.
    expect(halfHourStart(T('2026-11-01T06:10:00Z'))).toBe(T('2026-11-01T06:00:00Z'))
  })

  it('skips the missing hour in spring without a gap', () => {
    vi.stubEnv('TZ', 'America/New_York')
    const springStart = halfHourStart(T('2026-03-08T06:45:00Z'))
    expect(windowSlots(springStart, 1).map((d) => d.getHours())).toEqual([1, 3])
    const cells = layoutCells([programme('a', '2026-03-08T06:30:00Z', '2026-03-08T07:30:00Z')], springStart, springStart + HOUR)
    expect(strip(cells)).toEqual([{ id: 'a', left: 0, width: 100, before: false, after: false }])
  })
})

describe('guide windows', () => {
  it('fetches 6 hours from a 3-hour block, so a 2-hour window fits one reply', () => {
    const block = fetchBlock(T('2026-11-02T04:30:00Z'))
    expect(block).toBe(T('2026-11-02T03:00:00Z'))
    expect(T('2026-11-02T05:30:00Z') + 2 * HOUR).toBeLessThanOrEqual(block + 6 * HOUR)
  })

  it('ends the latest window at the last listing, and never before now', () => {
    const now = T('2026-11-02T01:00:00Z')
    expect(latestStart(now, T('2026-11-04T00:00:00Z'), 2)).toBe(T('2026-11-03T22:00:00Z'))
    expect(latestStart(now, T('2026-11-04T00:10:00Z'), 2)).toBe(T('2026-11-03T22:30:00Z'))
    expect(latestStart(now, T('2026-11-02T01:30:00Z'), 2)).toBe(now)
    expect(latestStart(now, undefined, 2)).toBe(now)
    expect(clampStart(now - HALF_HOUR, now, now + HOUR)).toBe(now)
    expect(clampStart(now + 2 * HOUR, now, now + HOUR)).toBe(now + HOUR)
  })

  it('moves the window by half hours to show a programme, within its bounds', () => {
    const start = T('2026-11-02T01:00:00Z')
    const late = programme('x', '2026-11-02T04:00:00Z', '2026-11-02T05:00:00Z')
    expect(startShowing(late, start, 2, start, start + 6 * HOUR)).toBe(T('2026-11-02T02:30:00Z'))
    expect(startShowing(late, start, 2, start, start + HOUR)).toBe(start + HOUR)
    const early = programme('y', '2026-11-02T00:00:00Z', '2026-11-02T00:30:00Z')
    expect(startShowing(early, start, 2, start - 2 * HOUR, start)).toBe(T('2026-11-02T00:00:00Z'))
  })

  it('lists today, tomorrow and each day to the end of listings', () => {
    vi.stubEnv('TZ', 'America/New_York')
    const now = T('2026-11-02T01:12:00Z') // Sun 8:12 PM EST
    const days = guideDays(now, T('2026-11-04T00:00:00Z'), 'en-US')
    expect(days.map((d) => d.label)).toEqual(['Today', 'Tomorrow', 'Tue'])
    expect(guideDays(now, T('2026-11-03T05:00:00Z'), 'en-US').map((d) => d.label)).toEqual(['Today', 'Tomorrow'])
    expect(guideDays(now, undefined, 'en-US').map((d) => d.label)).toEqual(['Today'])
    expect(new Date(dayStart(days[1], now)).getHours()).toBe(20)
    expect(dayStart(days[0], now)).toBe(halfHourStart(now))
    expect(shortDayTime(T('2026-11-04T00:00:00Z'), now, 'en-US')).toBe('Tue 7 PM')
    expect(shortDayTime(T('2026-11-02T02:30:00Z'), now, 'en-US')).toBe('9:30 PM')
  })
})

describe('programme wording', () => {
  it('reads a cell as times, title, episode and tags', () => {
    vi.stubEnv('TZ', 'America/New_York')
    const p = programme('s', '2026-11-03T01:00:00Z', '2026-11-03T01:30:00Z', { title: 'The Sleuth Files', season: 2, episode: 4, new: true })
    expect(programmeLabel(p, 'en-US')).toBe('8:00 to 8:30 PM, The Sleuth Files, season 2 episode 4, New')
    const late = programme('l', '2026-11-03T04:30:00Z', '2026-11-03T05:30:00Z', { title: 'Late Game', live: true, season: 2026, episode: 14 })
    expect(programmeLabel(late, 'en-US')).toBe('11:30 PM to 12:30 AM, Late Game, 2026 episode 14, Live')
    expect(timeRange(T('2026-11-03T01:00:00Z'), T('2026-11-03T02:00:00Z'), '–', 'en-US')).toBe('8:00–9:00 PM')
  })

  it('writes the episode line as the boards do', () => {
    expect(episodeLine(programme('a', '', '', { season: 2, episode: 4, episodeTitle: 'The Low Tide' }))).toBe('S2 E4 · The Low Tide')
    expect(episodeLine(programme('a', '', '', { season: 2026, episode: 14 }))).toBe('2026 E14')
    expect(episodeLine(programme('a', '', '', { kind: 'movie', airDate: '1958' }))).toBe('Movie · 1958')
    expect(episodeLine(programme('a', '', '', { episodeLabel: 'Cap. 112' }))).toBe('Cap. 112')
    expect(episodeLine(programme('a', '', '', { episodeTitle: 'Rainy Day' }))).toBe('Rainy Day')
    expect(episodeLine(programme('a', '', ''))).toBe('')
  })

  it('counts progress and time left', () => {
    const p = programme('a', '2026-11-02T01:00:00Z', '2026-11-02T02:00:00Z')
    expect(progress(p, T('2026-11-02T01:12:00Z'))).toBe(20)
    expect(progress(p, T('2026-11-02T00:00:00Z'))).toBe(0)
    expect(progress(p, T('2026-11-02T03:00:00Z'))).toBe(100)
    expect(duration(48 * 60_000)).toBe('48 min')
    expect(duration(60 * 60_000)).toBe('1 h')
    expect(duration(125 * 60_000)).toBe('2 h 5 min')
    expect(duration(10_000)).toBe('1 min')
  })

  it('shows a matched TMDB still first in the wide panel, credited', () => {
    expect(panelPicture(programme('a', '', '', { pictureUrl: '/api/live-tv/art/1', stillUrl: '/api/images/w780/s.jpg' }))).toEqual({ url: '/api/images/w780/s.jpg', tmdb: true })
    expect(panelPicture(programme('a', '', '', { pictureUrl: '/api/live-tv/art/1', pictureSource: 'provider' }))).toEqual({ url: '/api/live-tv/art/1', tmdb: false })
    expect(panelPicture(programme('a', '', '', { pictureUrl: '/api/images/w780/b.jpg', pictureSource: 'tmdb' }))).toEqual({ url: '/api/images/w780/b.jpg', tmdb: true })
    expect(panelPicture(programme('a', '', ''))).toBeUndefined()
  })
})

describe('now and next', () => {
  const now = programme('now', '2026-11-02T01:00:00Z', '2026-11-02T02:00:00Z')
  const next = programme('next', '2026-11-02T02:00:00Z', '2026-11-02T02:30:00Z')
  it('moves on at the end time without asking the server', () => {
    expect(nowAndNext({ now, next }, T('2026-11-02T01:59:59Z'))).toEqual({ now, next })
    expect(nowAndNext({ now, next }, T('2026-11-02T02:00:00Z'))).toEqual({ now: next })
    expect(nowAndNext({ now, next }, T('2026-11-02T02:30:00Z'))).toEqual({ next: undefined })
    expect(nowAndNext({ next }, T('2026-11-02T01:30:00Z'))).toEqual({ next })
    expect(nowAndNext({}, T('2026-11-02T01:30:00Z'))).toEqual({ next: undefined })
  })
})

describe('guide filters', () => {
  const movie = programme('m', '2026-11-02T01:00:00Z', '2026-11-02T03:00:00Z', { categories: ['Movie', 'Drama'] })
  const football = programme('f', '2026-11-02T03:00:00Z', '2026-11-02T04:00:00Z', { categories: ['Football'] })
  it('offers only the filters the feed’s categories fill', () => {
    expect(availableFilters([{ programmes: [movie, football] }]).map((f) => f.id)).toEqual(['movie', 'sports'])
    expect(availableFilters([{ programmes: [] }])).toEqual([])
  })
  it('keeps a channel while a programme in the window has the category', () => {
    const sports = GUIDE_FILTERS.find((f) => f.id === 'sports')
    const channel = { programmes: [movie, football] }
    expect(channelInFilter(channel, sports, T('2026-11-02T01:00:00Z'), T('2026-11-02T03:00:00Z'))).toBe(false)
    expect(channelInFilter(channel, sports, T('2026-11-02T02:00:00Z'), T('2026-11-02T04:00:00Z'))).toBe(true)
    expect(channelInFilter(channel, undefined, 0, 1)).toBe(true)
  })
})

describe('moving across the guide with the keyboard', () => {
  const start = T('2026-11-02T01:00:00Z')
  const end = start + 2 * HOUR
  const rows = [
    { id: '2.1', listings: true, programmes: [programme('a1', '2026-11-02T00:30:00Z', '2026-11-02T01:30:00Z'), programme('a2', '2026-11-02T01:30:00Z', '2026-11-02T03:30:00Z')] },
    { id: '4.3', listings: false, programmes: [] },
    { id: '7.1', listings: true, programmes: [programme('c1', '2026-11-02T01:00:00Z', '2026-11-02T02:00:00Z'), programme('c2', '2026-11-02T02:00:00Z', '2026-11-02T03:00:00Z'), programme('c3', '2026-11-02T03:00:00Z', '2026-11-02T04:00:00Z')] },
  ]
  it('goes to the programme before or after, past the window’s edge', () => {
    expect(moveSelection(rows, { channelId: '7.1', programmeId: 'c2' }, 'right', start, end)).toEqual({ channelId: '7.1', programmeId: 'c3' })
    expect(moveSelection(rows, { channelId: '7.1', programmeId: 'c1' }, 'left', start, end)).toBeUndefined()
    expect(moveSelection(rows, { channelId: '7.1', programmeId: 'c3' }, 'right', start, end)).toBeUndefined()
  })
  it('goes up and down to what is on at the same time, or a channel without listings', () => {
    expect(moveSelection(rows, { channelId: '7.1', programmeId: 'c2' }, 'up', start, end)).toEqual({ channelId: '4.3' })
    expect(moveSelection(rows, { channelId: '4.3' }, 'up', start, end)).toEqual({ channelId: '2.1', programmeId: 'a1' })
    expect(moveSelection(rows, { channelId: '2.1', programmeId: 'a2' }, 'down', start, end)).toEqual({ channelId: '4.3' })
    expect(moveSelection(rows, { channelId: '4.3' }, 'down', start, end)).toEqual({ channelId: '7.1', programmeId: 'c1' })
    expect(moveSelection(rows, { channelId: '7.1', programmeId: 'c1' }, 'down', start, end)).toBeUndefined()
  })
  it('goes to the first and last programme in the window', () => {
    expect(moveSelection(rows, { channelId: '7.1', programmeId: 'c1' }, 'end', start, end)).toEqual({ channelId: '7.1', programmeId: 'c2' })
    expect(moveSelection(rows, { channelId: '2.1', programmeId: 'a2' }, 'home', start, end)).toEqual({ channelId: '2.1', programmeId: 'a1' })
    expect(moveSelection(rows, { channelId: '4.3' }, 'home', start, end)).toBeUndefined()
  })
  it('starts at the first row when the selection is gone', () => {
    expect(moveSelection(rows, { channelId: 'missing' }, 'down', start, end)).toEqual({ channelId: '2.1', programmeId: 'a1' })
  })
})
