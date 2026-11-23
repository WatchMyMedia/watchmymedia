import { describe, expect, it } from 'vitest'
import type { Recording, RecordingRule } from '../../../src/api/recordings'
import { formatBytes } from '../../../src/lib/format'
import { clashAlert, clashCount, episodeWords, isNewRecording, lengthWords, recordedDay, recordedShows, recordingNowWords, roomWords, scheduledDays } from './recordingsModel'

// Monday 23 November 2026, 8:12 PM local.
const at = (h: number, m = 0, day = 0) => new Date(2026, 10, 23 + day, h, m).getTime()
const NOW = at(20, 12)
const iso = (t: number) => new Date(t).toISOString()
const MIN = 60_000

function rec(title: string, start: number, minutes: number, extra: Partial<Recording> = {}): Recording {
  return {
    id: `${title}-${start}`, title, start: iso(start), end: iso(start + minutes * MIN), state: 'done', number: '2.1', channelName: 'HRBR', channelId: 'c', sourceId: 's',
    series: false, padBefore: 0, padAfter: 0, bytes: 0, gaps: [], canCancel: true, userName: 'Robin', ...extra,
  }
}

describe('Ready to watch', () => {
  const rescue = [
    rec('Coastline Rescue', at(21, 0, -6), 60, { season: 2, episode: 4, episodeTitle: 'The Low Tide', series: true }),
    rec('Coastline Rescue', at(21, 0, -13), 60, { season: 2, episode: 3, episodeTitle: 'Night Crossing', series: true }),
    rec('Coastline Rescue', at(21, 0, -20), 60, { season: 2, episode: 2, episodeTitle: 'The Bar', series: true }),
  ]
  const film = rec('The Iron Coast', at(19, 30, -1), 112)
  const failed = rec('Penvale Tonight', at(22, 0, -1), 30, { state: 'failed', reason: 'missed' })

  it('puts a show’s recordings on one card, the newest show first', () => {
    const shows = recordedShows([...rescue, film, failed, rec('Now', at(20), 60, { state: 'recording' })], NOW)
    expect(shows.map((s) => s.title)).toEqual(['Penvale Tonight', 'The Iron Coast', 'Coastline Rescue'])
    expect(shows[2]).toMatchObject({ line: '3 episodes · newest Tuesday', playable: true })
    expect(shows[2].items.map((r) => r.episode)).toEqual([4, 3, 2])
    expect(shows[1].line).toBe('1 h 52 min')
    expect(shows[0]).toMatchObject({ playable: false })
  })

  it('words an episode in its show’s panel', () => {
    expect(episodeWords(rescue[1], NOW)).toEqual({ title: 'Night Crossing', line: 'Season 2 · Episode 3 · Last Tuesday · 1 hour' })
    expect(episodeWords(rec('News', at(18), 30), NOW)).toEqual({ title: 'News', line: 'Today · 30 minutes' })
  })

  it('says how far a recording has got and when it ends', () => {
    expect(recordingNowWords(rec('Northfield Tonight', at(20), 60, { state: 'recording' }), NOW, 'en-US')).toBe('12 min so far · ends at 9:00 PM')
  })

  it('names the day something recorded', () => {
    expect([0, -1, -3, -8, -30].map((d) => recordedDay(at(9, 0, d), NOW, 'en-US'))).toEqual(['Today', 'Yesterday', 'Friday', 'Last Sunday', 'October 24'])
    expect([30, 60, 120, 112].map((m) => lengthWords(m * MIN))).toEqual(['30 minutes', '1 hour', '2 hours', '1 h 52 min'])
  })
})

describe('Scheduled', () => {
  const vane = rec('Inspector Vane', at(21), 60, { state: 'scheduled', number: '4.2', channelName: 'Sleuth Hour', series: true, ruleId: 'rule-v' })
  const game = rec('Conference Football', at(21), 90, { state: 'scheduled', number: '4.1', channelName: 'NFLD' })
  const clash = rec('Coastline Rescue', at(21), 60, { state: 'conflict', reason: 'tuners_busy',
    conflictsWith: [{ id: vane.id, title: vane.title, number: '4.2', channelName: 'Sleuth Hour', start: vane.start, end: vane.end, userName: 'Robin', running: false }, { id: game.id, title: game.title, number: '4.1', channelName: 'NFLD', start: game.start, end: game.end, userName: 'Sam', running: false }] })
  const news = rec('Harbor Evening News', at(18, 0, 1), 30, { state: 'scheduled', series: true, ruleId: 'rule-n' })
  const rules = [{ id: 'rule-n', kind: 'series', newOnly: false } as RecordingRule, { id: 'rule-v', kind: 'series', newOnly: true } as RecordingRule]

  it('lists what will record by day, and marks what won’t', () => {
    const days = scheduledDays([news, game, clash, vane], rules, NOW, 'en-US')
    expect(days.map((d) => d.day)).toEqual(['Tonight', 'Tomorrow'])
    expect(days[0].rows.map((r) => [r.title, r.mark, r.end])).toEqual([['Conference Football', 'dot', undefined], ['Coastline Rescue', 'wont', 'Won’t record'], ['Inspector Vane', 'ring', undefined]])
    expect(days[1].rows[0]).toMatchObject({ time: '6:00 PM', sub: '2.1 HRBR · every episode · 30 minutes', mark: 'ring' })
    expect(days[0].rows[2].sub).toBe('4.2 Sleuth Hour · new episode · 1 hour')
    expect(clashCount([news, clash])).toBe('1 clash')
    expect(clashCount([news])).toBeUndefined()
  })

  it('says what clashes, what records as it stands, and whether it can be chosen', () => {
    expect(clashAlert(clash, NOW, 'en-US')).toEqual({
      title: 'Three recordings want tonight at 9:00 PM, and only two tuners are free.',
      line: 'As it stands Inspector Vane and Conference Football record and Coastline Rescue doesn’t.',
      canChoose: true,
    })
    expect(clashAlert({ ...clash, conflictsWith: [], reason: 'no_tuners' }, NOW, 'en-US').canChoose).toBe(false)
  })
})

describe('room left', () => {
  it('judges hours from what recordings have taken, else says the free space', () => {
    const done = [rec('a', at(20, 0, -1), 60, { bytes: 4_000_000_000 }), rec('b', at(21, 0, -1), 60, { bytes: 2_000_000_000 })]
    expect(roomWords(210_000_000_000, done, formatBytes)).toBe('Room for about 70 more hours')
    expect(roomWords(9_000_000_000, done, formatBytes)).toBe('Room for about 3 more hours')
    expect(roomWords(1_000_000_000, done, formatBytes)).toBe('Room for less than an hour more')
    expect(roomWords(412_000_000_000, [], formatBytes)).toBe('384 GB free')
    expect(roomWords(undefined, done, formatBytes)).toBeUndefined()
  })
})

describe('a recording’s place for the person, from API level 34', () => {
  const ep = (n: number, days: number, extra: Partial<Recording>) => rec('Coastline Rescue', at(21, 0, days), 60, { season: 2, episode: n, series: true, ...extra })

  it('says New, the time left or watched on each episode', () => {
    expect(episodeWords(ep(4, -1, { episodeTitle: 'The Low Tide', new: true }), NOW).line).toBe('Season 2 · Episode 4 · Yesterday · 1 hour')
    expect(episodeWords(ep(3, -8, { episodeTitle: 'Night Crossing', progress: { positionMs: 38 * MIN, durationMs: 60 * MIN } }), NOW).line).toBe('Season 2 · Episode 3 · Last Sunday · 22 min left')
    expect(episodeWords(ep(2, -3, { episodeTitle: 'The Bar', watched: true, progress: { positionMs: 60 * MIN, durationMs: 60 * MIN } }), NOW).line).toBe('Season 2 · Episode 2 · Friday · watched')
    expect(isNewRecording({ new: true })).toBe(true)
    expect(isNewRecording({ new: true, watched: true })).toBe(false)
    expect(isNewRecording({})).toBe(false)
  })

  it('counts a show’s new episodes on its card', () => {
    const [show] = recordedShows([ep(4, -1, { new: true }), ep(3, -8, { new: true, watched: true }), ep(2, -15, {})], NOW)
    expect(show.line).toBe('3 episodes · 1 new')
    expect(show.fresh).toBe(1)
    const [older] = recordedShows([ep(3, -8, {}), ep(2, -15, {})], NOW)
    expect(older.line).toBe('2 episodes · newest last Sunday')
    expect(older.fresh).toBe(0)
  })
})
