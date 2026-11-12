import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  canChooseClash, canRecord, clashHeading, conflictWords, firstGivesWay, gapWords, growingDurationMs, growingSeekMs, joinNames, manualRepeatWords, markHoldsRecord, markLook,
  reasonWords, recordingMarkLabel, recordingMarkWords, recordingProgress, recordingWords, serverRecords, showGoToNow, startRetryMs, stillRecording,
  takeoverWords, validateManual, START_RETRIES,
} from './liveRecordings'
import { timeOfDay, timeRange } from './liveGuide'

afterEach(() => { vi.unstubAllEnvs() })

describe('recording compatibility', () => {
  it('requires the version and both permissions', () => {
    expect(canRecord(18, { recording: true, canRecord: true })).toBe(false)
    expect(canRecord(19, { recording: true, canRecord: true })).toBe(true)
    expect(canRecord(19, { recording: true })).toBe(false)
  })
  it('says a recording may take a tuner only on a server that records', () => {
    expect(serverRecords(19, { recording: true })).toBe(true)
    expect(serverRecords(19, { recording: false })).toBe(false)
    expect(serverRecords(18, { recording: true })).toBe(false)
    expect(serverRecords(19, undefined)).toBe(false)
  })
})

describe('the growing player', () => {
  const g = { startedAt: '2026-11-04T21:00:00Z', endsAt: '2026-11-04T22:00:00Z' }
  it('grows from the decision’s length on this device’s clock, not the wall clock', () => {
    // The server said 23 minutes were written; 30 s later there are 30 s more.
    expect(growingDurationMs(g, 23 * 60_000, 30_000)).toBe(23 * 60_000 + 30_000)
    expect(growingDurationMs(g, 23 * 60_000, -5)).toBe(23 * 60_000)
  })
  it('stops at the planned end', () => {
    expect(growingDurationMs(g, 59 * 60_000, 5 * 60_000)).toBe(3600_000)
    expect(stillRecording(g, 59 * 60_000, 30_000)).toBe(true)
    expect(stillRecording(g, 59 * 60_000, 60_000)).toBe(false)
  })
  it('freezes where the stream ended, even without the recording’s details', () => {
    expect(growingDurationMs(g, 60_000, 45 * 60_000, 12_000)).toBe(12_000)
    expect(stillRecording(g, 60_000, 1000, 12_000)).toBe(false)
  })
  it('plays an ordinary file at its own length', () => {
    expect(growingDurationMs(undefined, 60_000, 99_000)).toBe(60_000)
    expect(stillRecording(undefined, 60_000, 0)).toBe(false)
  })
  it('keeps growing without a planned end it can read', () => {
    expect(growingDurationMs({ startedAt: 'x' }, 60_000, 1000)).toBe(61_000)
    expect(stillRecording({}, 60_000, 1000)).toBe(true)
  })
  it('offers Go to now only when well behind', () => {
    expect(showGoToNow(true, 50_000, 60_000)).toBe(false)
    expect(showGoToNow(true, 49_000, 60_000)).toBe(true)
    expect(showGoToNow(false, 0, 60_000)).toBe(false)
  })
  it('keeps seeks on the written side of the writer', () => {
    expect(growingSeekMs(999_000, 60_000)).toBe(56_000)
    expect(growingSeekMs(-5, 60_000)).toBe(0)
    expect(growingSeekMs(500, 1000)).toBe(0)
    expect(growingSeekMs(20_000, 60_000)).toBe(20_000)
  })
  it('asks again while the recording starts, then gives up', () => {
    expect([0, 1, 2, 3, 4].map((n) => startRetryMs(n))).toEqual([1000, 2000, 4000, 5000, 5000])
    expect(startRetryMs(START_RETRIES)).toBeUndefined()
    expect(startRetryMs(0, '3')).toBe(3000)
    expect(startRetryMs(0, '0')).toBe(500)
    expect(startRetryMs(0, '600')).toBe(15_000)
    const now = Date.parse('2026-11-04T21:00:00Z')
    expect(startRetryMs(0, 'Wed, 04 Nov 2026 21:00:02 GMT', now)).toBe(2000)
    expect(startRetryMs(1, 'soon')).toBe(2000)
  })
})

describe('state and reason words', () => {
  it('words every reason as the apps do, and never shows a code', () => {
    expect(reasonWords('already_scheduled')).toBe('Already set to record')
    expect(reasonWords('rule_filtered')).toBe('Left out by the series options')
    expect(reasonWords('disk_full')).toBe('Stopped: the disk is nearly full')
    expect(reasonWords('disk_full', true)).toBe('Not enough free space')
    expect(reasonWords('something_new')).toBeUndefined()
    expect(recordingWords('failed', 'something_new')).toBe('Didn’t record')
    expect(recordingWords('failed', 'missed')).toBe('Missed: the server wasn’t running')
    expect(recordingWords('failed', 'listing_gone')).toBe('No longer in the listings')
    expect(recordingWords('failed')).toBe('Didn’t record')
    expect(recordingWords('mystery')).toBe('')
  })
  it('tells skips apart', () => {
    expect(recordingWords('skipped', 'already_recorded')).toBe('Already recorded')
    expect(recordingWords('skipped', 'in_library')).toBe('Already in your library')
    expect(recordingWords('skipped', 'not_new')).toBe('Not a new episode')
    expect(recordingWords('skipped', 'listing_gone')).toBe('No longer in the listings')
  })
  it('names the clash', () => {
    expect(conflictWords('tuners_busy')).toBe('Won’t record: the tuners are busy')
    expect(conflictWords('no_tuners', [{ title: 'Inspector Vane' }])).toBe('Won’t record: no tuner is free for recordings')
    expect(conflictWords('tuners_busy', [{ title: 'Inspector Vane' }, { title: 'Conference Football' }])).toBe('Won’t record: Inspector Vane and Conference Football have the tuners')
    expect(conflictWords(undefined, [{ title: 'Inspector Vane' }])).toBe('Won’t record: Inspector Vane has the tuner')
    expect(joinNames(['A', 'B', 'C'])).toBe('A, B and C')
  })
  it('words a recording under way and one finishing', () => {
    expect(recordingWords('recording')).toBe('Recording now')
    expect(recordingWords('recording', 'stop_requested')).toBe('Stopping…')
    expect(recordingWords('done', 'finish_pending')).toBe('Finishing…')
    expect(recordingWords('partial', 'signal_lost')).toBe('Part recorded · The signal dropped')
    expect(recordingWords('partial', 'tuners_busy')).toBe('Part recorded · The tuners were busy')
  })
  it('words a gap with its times', () => {
    vi.stubEnv('TZ', 'America/New_York')
    const range = (a: number, b: number) => timeRange(a, b, '–', 'en-US')
    expect(gapWords({ start: '2026-11-05T02:12:00Z', end: '2026-11-05T02:14:00Z', reason: 'signal_lost' }, range)).toBe('Missing 9:12–9:14 PM: the signal dropped')
    expect(gapWords({ start: '2026-11-05T02:00:00Z', end: '2026-11-05T02:02:00Z', reason: 'started_late' }, range)).toBe('Missing 9:00–9:02 PM: started late')
    expect(gapWords({ start: '2026-11-05T02:12:00Z', end: '2026-11-05T02:14:00Z', reason: 'new_code' }, range)).toBe('Missing part: 9:12–9:14 PM')
  })
})

describe('guide marks', () => {
  it('marks only what will record, is recording or clashes', () => {
    expect(markLook({ state: 'scheduled' })).toBe('dot')
    expect(markLook({ state: 'scheduled', series: true })).toBe('ring')
    expect(markLook({ state: 'recording', series: true })).toBe('rec')
    expect(markLook({ state: 'conflict' })).toBe('hollow')
    for (const state of ['skipped', 'failed', 'done', 'partial', 'gone']) {
      expect(markLook({ state })).toBeUndefined()
      expect(markHoldsRecord({ state })).toBe(false)
      expect(recordingMarkLabel({ state })).toBe('')
    }
    expect(markHoldsRecord(undefined)).toBe(false)
  })
  it('names the mark for screen readers', () => {
    expect(recordingMarkLabel({ state: 'scheduled' })).toBe(', will record')
    expect(recordingMarkLabel({ state: 'recording' })).toBe(', recording')
    expect(recordingMarkLabel({ state: 'conflict', reason: 'tuners_busy' })).toBe(', won’t record: the tuners are busy')
    expect(recordingMarkLabel({ state: 'conflict', reason: 'no_tuners' })).toBe(', won’t record: no tuner is free for recordings')
  })
  it('words the details from the reason', () => {
    expect(recordingMarkWords({ state: 'skipped', reason: 'not_new' })).toBe('Not a new episode')
    expect(recordingMarkWords({ state: 'skipped', reason: 'in_library', series: true })).toBe('Already in your library')
    expect(recordingMarkWords({ state: 'scheduled', series: true })).toBe('Will record · Series')
    expect(recordingMarkWords({ state: 'conflict', series: true })).toBe('Won’t record: the tuners are busy')
  })
})

describe('the clash', () => {
  it('heads with when the tuners are all busy', () => {
    vi.stubEnv('TZ', 'America/New_York')
    const time = (at: number) => timeOfDay(at, 'en-US')
    const holders = [{ start: '2026-11-05T02:00:00Z' }, { start: '2026-11-05T02:30:00Z' }]
    expect(clashHeading(holders, '2026-11-05T02:00:00Z', time)).toBe('Both tuners are recording at 9:30 PM')
    expect(clashHeading(holders.slice(0, 1), '2026-11-05T02:00:00Z', time)).toBe('The tuner is recording at 9:00 PM')
    expect(clashHeading([...holders, ...holders], '2026-11-05T02:00:00Z', time)).toBe('All 4 tuners are recording at 9:30 PM')
  })
  it('chooses first a holder that can give way', () => {
    expect(firstGivesWay([{ id: 'a', running: true }, { id: 'b', running: false }])).toBe('b')
    expect(firstGivesWay([{ id: 'a', running: true }])).toBeUndefined()
  })
  it('offers a choice only when a holder can give way', () => {
    expect(canChooseClash({ state: 'conflict', conflictsWith: [{ running: true }, { running: false }] })).toBe(true)
    expect(canChooseClash({ state: 'conflict', conflictsWith: [{ running: true }] })).toBe(false)
    expect(canChooseClash({ state: 'conflict', reason: 'no_tuners', conflictsWith: [{ running: false }] })).toBe(false)
    expect(canChooseClash({ state: 'conflict' })).toBe(false)
    expect(canChooseClash({ state: 'scheduled', conflictsWith: [{ running: false }] })).toBe(false)
  })
})

describe('other words', () => {
  it('words a repeating manual recording', () => {
    const rule = { start: '2026-11-03T11:00:00Z', zone: 'America/New_York' }
    expect(manualRepeatWords({ ...rule, repeat: 'daily' }, 'en-US', 'America/New_York')).toBe('Every day at 6:00 AM')
    expect(manualRepeatWords({ ...rule, repeat: 'weekdays' }, 'en-US', 'America/New_York')).toBe('Weekdays at 6:00 AM')
    expect(manualRepeatWords({ ...rule, repeat: 'weekly' }, 'en-US', 'America/New_York')).toBe('Every Tuesday at 6:00 AM')
    expect(manualRepeatWords({ ...rule, repeat: 'daily' }, 'en-US', 'Europe/London')).toBe('Every day at 6:00 AM (America/New York)')
    expect(manualRepeatWords({ repeat: 'weekly' }, 'en-US')).toBe('Every week')
  })
  it('times the takeover and the progress', () => {
    expect(takeoverWords('2026-11-04T21:02:00Z', 'Coastline Rescue', Date.parse('2026-11-04T21:00:01Z'))).toContain('In 2 min')
    expect(recordingProgress('2026-11-04T21:00:00Z', '2026-11-04T22:00:00Z', Date.parse('2026-11-04T21:30:00Z'))).toBe(50)
  })
  it('rejects manual ranges that would never record or exceed the bound', () => {
    const now = Date.parse('2026-11-04T21:00:00Z')
    expect(validateManual('2026-11-04T22:00:00Z', '2026-11-04T23:00:00Z', 'Evening news', now)).toBeUndefined()
    expect(validateManual('2026-11-04T23:00:00Z', '2026-11-04T22:00:00Z', 'Evening news', now)).toContain('after')
    expect(validateManual('2026-11-04T22:00:00Z', '2026-11-05T11:00:00Z', 'Evening news', now)).toContain('12 hours')
  })
})
