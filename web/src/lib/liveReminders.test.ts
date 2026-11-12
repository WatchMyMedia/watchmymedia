import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { LiveGuideChannel } from '../api/liveTV'
import { FAVOURITES, guideRows, showFavouritesChip, showGuideExtras } from './liveGuide'
import {
  dayShort, dueReminders, leadMinutes, leadText, movedTo, nextReminderAt, noticeKey, onAt, pruneNotices, reminderNotice, reminderWhen, remindState, searchSpan,
  searchTerm, searchWhen, wasWhen, type LiveReminder,
} from './liveReminders'
import { validateGuideSearch } from './routeSearch'

// Mon 2 Nov 2026, 8:12 PM in New York.
const NOW = Date.parse('2026-11-03T01:12:00Z')
const at = (local: string) => Date.parse(local)
beforeEach(() => { vi.stubEnv('TZ', 'America/New_York') })
afterEach(() => { vi.unstubAllEnvs() })

const reminder = (over: Partial<LiveReminder> = {}): LiveReminder => ({
  id: 'r1', channelId: 'c4', number: '4.1', channelName: 'NFLD-HD', programmeId: 'p1', title: 'The Sleuth Files', season: 2, episode: 5,
  start: '2026-11-03T02:00:00Z', end: '2026-11-03T03:00:00Z', state: 'set', ...over,
})
const channel = (id: string, over: Partial<LiveGuideChannel> = {}): LiveGuideChannel => ({ id, sourceId: 's', number: id, name: `Channel ${id}`, enabled: true, available: true, listings: true, programmes: [], ...over })

describe('favourites', () => {
  const start = at('2026-11-02T20:00:00-05:00'), end = start + 2 * 3600_000
  const movie = { id: 'm', title: 'The Iron Coast', start: '2026-11-03T01:00:00Z', end: '2026-11-03T03:00:00Z', kind: 'movie' }
  const channels = [channel('2.1', { favourite: true }), channel('4.1'), channel('4.3', { favourite: true, listings: false }), channel('7.1', { programmes: [movie] }), channel('20.1', { favourite: true, programmes: [movie] })]
  it('shows a favourite with or without listings, in channel order', () => {
    expect(guideRows(channels, FAVOURITES, start, end).map((c) => c.id)).toEqual(['2.1', '4.3', '20.1'])
  })
  it('leaves the other filters as they were: a channel without listings always shows', () => {
    expect(guideRows(channels, '', start, end).map((c) => c.id)).toEqual(['2.1', '4.1', '4.3', '7.1', '20.1'])
    expect(guideRows(channels, 'movie', start, end).map((c) => c.id)).toEqual(['4.3', '7.1', '20.1'])
  })
  it('shows the chip once there is a favourite, and while it is chosen', () => {
    expect(showFavouritesChip(channels, '')).toBe(true)
    expect(showFavouritesChip([channel('4.1')], '')).toBe(false)
    expect(showFavouritesChip([channel('4.1')], FAVOURITES)).toBe(true)
  })
  it('needs listings at API level 15', () => {
    expect(showGuideExtras(15, { guide: true })).toBe(true)
    expect(showGuideExtras(14, { guide: true })).toBe(false)
    expect(showGuideExtras(15, { guide: false })).toBe(false)
  })
})

describe('Remind me', () => {
  const later = { start: '2026-11-03T02:00:00Z' } // 9:00 PM, 48 min ahead
  it('offers Remind me for a programme more than the lead time ahead', () => {
    expect(remindState(later, undefined, 5, NOW)).toBe('remind')
    expect(remindState(later, undefined, 15, NOW)).toBe('remind')
    expect(remindState({ start: '2026-11-03T01:15:00Z' }, undefined, 5, NOW)).toBe('soon')
    expect(remindState({ start: '2026-11-03T01:15:00Z' }, undefined, 0, NOW)).toBe('remind')
  })
  it('says set or moved, from the reminder or the guide’s mark', () => {
    expect(remindState(later, reminder(), 5, NOW)).toBe('set')
    expect(remindState(later, reminder({ state: 'moved' }), 5, NOW)).toBe('moved')
    expect(remindState(later, true, 5, NOW)).toBe('set')
  })
  it('offers nothing for what is on now or over: that is Watch', () => {
    expect(remindState({ start: '2026-11-03T01:00:00Z' }, reminder(), 5, NOW)).toBe('none')
  })
  it('words a later programme’s day', () => {
    expect(onAt('4.1', at('2026-11-02T21:00:00-05:00'), NOW)).toBe('On 4.1 at 9:00 PM')
    expect(onAt('4.1', at('2026-11-03T20:00:00-05:00'), NOW)).toBe('On 4.1 tomorrow at 8:00 PM')
    expect(onAt('4.1', at('2026-11-04T21:00:00-05:00'), NOW)).toBe('On 4.1 Wed at 9:00 PM')
    expect(movedTo(at('2026-11-02T21:30:00-05:00'), NOW)).toBe('Moved to 9:30 PM')
    expect(movedTo(at('2026-11-03T21:30:00-05:00'), NOW)).toBe('Moved to Tue 9:30 PM')
  })
  it('keeps the lead time to the five choices, 5 by default', () => {
    for (const m of [0, 1, 5, 10, 15]) expect(leadMinutes(m)).toBe(m)
    for (const m of [undefined, null, 2, 30, -1]) expect(leadMinutes(m)).toBe(5)
    expect(leadText(5)).toBe('5 min before')
    expect(leadText(0)).toBe('when it starts')
  })
})

describe('the Reminders list', () => {
  it('words each reminder’s time and channel', () => {
    expect(reminderWhen(reminder({ start: '2026-11-03T03:00:00Z', number: '7.1', channelName: 'LKSD-HD' }), NOW)).toBe('Today 10:00 PM · 7.1 LKSD-HD')
    expect(reminderWhen(reminder({ start: '2026-11-04T02:00:00Z' }), NOW)).toBe('Tue 9:00 PM · 4.1 NFLD-HD')
    expect(reminderWhen(reminder({ state: 'gone' }), NOW)).toBe('Was today 9:00 PM · 4.1 NFLD-HD')
    expect(reminderWhen(reminder({ channelName: undefined }), NOW)).toBe('Today 9:00 PM · 4.1')
    expect(wasWhen(reminder({ end: '2026-11-03T03:30:00Z' }))).toBe('Was Mon 9:00–10:30 PM')
  })
})

describe('delivering reminders', () => {
  const lead = 5
  it('is due from the lead time before the start until the programme ends; never once gone', () => {
    const r = reminder()
    expect(dueReminders([r], lead, at('2026-11-02T20:54:59-05:00'))).toEqual([])
    expect(dueReminders([r], lead, at('2026-11-02T20:55:00-05:00'))).toEqual([r])
    expect(dueReminders([r], lead, at('2026-11-02T21:59:00-05:00'))).toEqual([r])
    expect(dueReminders([r], lead, at('2026-11-02T22:00:00-05:00'))).toEqual([])
    expect(dueReminders([reminder({ state: 'gone' })], lead, at('2026-11-02T20:56:00-05:00'))).toEqual([])
    expect(dueReminders([reminder({ state: 'moved' })], lead, at('2026-11-02T20:56:00-05:00'))).toHaveLength(1)
  })
  it('wakes for the next one, soonest first, skipping gone and past ones', () => {
    const items = [reminder({ id: 'a', start: '2026-11-03T03:00:00Z' }), reminder({ id: 'b' }), reminder({ id: 'c', state: 'gone', start: '2026-11-03T01:30:00Z' })]
    expect(nextReminderAt(items, lead, NOW)).toBe(at('2026-11-02T20:55:00-05:00'))
    expect(nextReminderAt(items, lead, at('2026-11-02T21:56:00-05:00'))).toBeUndefined()
    expect(dueReminders(items, lead, at('2026-11-02T21:57:00-05:00')).map((r) => r.id)).toEqual(['b', 'a'])
  })
  it('notifies a moved reminder again at its new time', () => {
    expect(noticeKey(reminder())).not.toBe(noticeKey(reminder({ start: '2026-11-03T02:30:00Z' })))
  })
  it('keeps a day of delivered notices', () => {
    expect(pruneNotices({ old: NOW - 90_000_000, recent: NOW - 60_000 }, NOW)).toEqual({ recent: NOW - 60_000 })
  })
  it('says what is starting, on which channel and when', () => {
    expect(reminderNotice(reminder(), at('2026-11-02T20:55:00-05:00'))).toEqual({ heading: 'Starting in 5 min', title: 'The Sleuth Files', line: '4.1 NFLD-HD · S2 E5 · 9:00 PM', notification: 'Starting in 5 min · The Sleuth Files' })
    expect(reminderNotice(reminder(), at('2026-11-02T20:55:30-05:00')).heading).toBe('Starting in 5 min')
    expect(reminderNotice(reminder(), at('2026-11-02T21:00:00-05:00')).heading).toBe('Starting now')
    expect(reminderNotice(reminder(), at('2026-11-02T21:05:00-05:00')).heading).toBe('On now')
    expect(reminderNotice(reminder({ season: undefined, episode: undefined, episodeTitle: 'The Low Tide' }), NOW).line).toBe('4.1 NFLD-HD · The Low Tide · 9:00 PM')
  })
})

describe('listings search', () => {
  it('waits for two characters, and folds spaces', () => {
    expect(searchTerm('')).toBeUndefined()
    expect(searchTerm(' h ')).toBeUndefined()
    expect(searchTerm('  harbor   lights ')).toBe('harbor lights')
    expect(searchTerm('x'.repeat(150))).toHaveLength(100)
  })
  it('words a result’s time', () => {
    expect(searchWhen({ start: '2026-11-03T01:00:00Z', end: '2026-11-03T02:45:00Z' }, '20.1', NOW)).toBe('Now on 20.1')
    expect(searchWhen({ start: '2026-11-03T03:00:00Z', end: '2026-11-03T04:00:00Z' }, '2.1', NOW)).toBe('Today 10:00 PM on 2.1')
    expect(searchWhen({ start: '2026-11-04T02:00:00Z', end: '2026-11-04T03:00:00Z' }, '4.1', NOW)).toBe('Tue 9:00 PM on 4.1')
    expect(searchSpan({ start: '2026-11-03T01:00:00Z', end: '2026-11-03T02:45:00Z' }, NOW)).toBe(' · 8:00–9:45 PM · 1 h 33 min left')
    expect(searchSpan({ start: '2026-11-03T03:00:00Z', end: '2026-11-03T04:00:00Z' }, NOW)).toBe('')
    expect(dayShort(NOW, NOW)).toBe('Today')
  })
  it('opens the guide at a result from the Search page’s address', () => {
    expect(validateGuideSearch({ channel: 'c4', programme: 'p1', at: '2026-11-03T02:00:00Z' })).toEqual({ channel: 'c4', programme: 'p1', at: '2026-11-03T02:00:00Z' })
    expect(validateGuideSearch({ channel: 4, at: 'soon' })).toEqual({ channel: undefined, programme: undefined, at: undefined })
    expect(validateGuideSearch({ channel: 'x'.repeat(200) }).channel).toBeUndefined()
  })
})
