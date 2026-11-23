import { describe, expect, it } from 'vitest'
import {
  behindWords, clashAlert, dayWords, groupRecordings, guideBoxMode, HD_BYTES_PER_HOUR, liveModeWords, livePlaybackWords, parseGuideView, parseVolume, primeTime,
  recordedWords, recordingEpisodeWords, roomWords, ruleWords, scheduleDays,
} from './liveWords'
import type { components } from '../api/schema'

type Recording = components['schemas']['LiveTVRecording']
type RecordingRule = components['schemas']['LiveTVRecordingRule']

const now = new Date(2026, 10, 24, 20, 12).getTime()
const at = (day: number, h: number, m = 0) => new Date(2026, 10, day, h, m).toISOString()
const rec = (over: Partial<Recording>): Recording =>
  ({ id: 'r', title: 'Inspector Vane', state: 'done', start: at(22, 21), end: at(22, 22), channelId: 'c', channelName: 'CRWN', number: '9.1', bytes: 0, userName: 'sam', userId: 1, canCancel: false, canDelete: true, sourceId: 's', programmeId: 'p', padBefore: 0, padAfter: 0, series: false, ...over }) as Recording

describe('the guide’s box', () => {
  const channels = [{ number: '4.1', name: 'NFLD' }, { number: '7.1', name: 'Lakeside' }]
  it('goes to channels that match, else searches listings', () => {
    expect(guideBoxMode('  ', channels, true)).toBe('none')
    expect(guideBoxMode('7.1', channels, true)).toBe('channels')
    expect(guideBoxMode('lake', channels, true)).toBe('channels')
    expect(guideBoxMode('sleuth', channels, true)).toBe('listings')
    expect(guideBoxMode('sleuth', channels, false)).toBe('channels')
  })
  it('reads a stored view and finds prime time', () => {
    expect(parseGuideView('now')).toBe('now')
    expect(parseGuideView('list')).toBeUndefined()
    expect(new Date(primeTime(new Date(2026, 10, 24).getTime())).getHours()).toBe(20)
  })
})

describe('the live player’s words', () => {
  it('says how far behind live', () => {
    expect(behindWords(2)).toBeUndefined()
    expect(behindWords(31)).toBe('0:31 behind')
    expect(behindWords(725)).toBe('12:05 behind')
    expect(behindWords(NaN)).toBeUndefined()
  })
  it('reads a stored volume', () => {
    expect(parseVolume('0.4')).toBe(0.4)
    expect(parseVolume('7')).toBe(1)
    expect(parseVolume(null)).toBe(1)
    expect(parseVolume('x')).toBe(1)
  })
  it('tells the stream’s story in a sentence', () => {
    expect(livePlaybackWords({ mode: 'original', quality: '1080' })).toBe('Playing the broadcast as the channel sends it.')
    expect(livePlaybackWords({ mode: 'video', modeReason: 'adaptive_family', automatic: true, actualQuality: '720', quality: 'auto' })).toBe('Converting the broadcast to 720p so it keeps up on this connection.')
    expect(livePlaybackWords({ mode: 'video', modeReason: 'deinterlace_required', quality: '1080' })).toMatch(/interlaced/)
    expect(livePlaybackWords({ mode: 'video', modeReason: 'resolution_limit', quality: '480', actualQuality: '480' })).toBe('Converting the video to 480p to keep to the quality chosen here.')
    expect(livePlaybackWords({ mode: 'audio', quality: '1080' })).toMatch(/converting the sound/)
    expect(livePlaybackWords({ quality: '1080' })).toBe('Playing the live broadcast.')
    expect(liveModeWords('remux')).toBe('Repackaged, not converted')
    expect(liveModeWords(undefined)).toBeUndefined()
  })
})

describe('Recordings', () => {
  it('says how much room is left, in hours', () => {
    expect(roomWords(undefined, [])).toBeUndefined()
    expect(roomWords(HD_BYTES_PER_HOUR * 70.4, [])).toBe('Room for about 70 hours')
    expect(roomWords(HD_BYTES_PER_HOUR * 0.5, [])).toBe('Room for less than an hour')
    expect(roomWords(HD_BYTES_PER_HOUR * 3.6, [])).toBe('Room for about 3 hours')
    // The server's own recordings set the rate: 4 GB an hour, 40 GB free.
    expect(roomWords(40e9, [rec({ bytes: 4e9, start: at(22, 20), end: at(22, 21) })])).toBe('Room for about 10 hours')
  })

  it('groups episodes by show, newest first, and keeps films apart', () => {
    const rules = [{ id: 'rule1', kind: 'series', keep: 5 } as RecordingRule]
    const { groups, oneOffs } = groupRecordings(
      [
        rec({ id: 'a', season: 2, episode: 6, start: at(15, 21) }),
        rec({ id: 'b', season: 2, episode: 7, start: at(22, 21), ruleId: 'rule1' }),
        rec({ id: 'c', title: 'Harbor Evening News', ruleId: 'rule1', start: at(23, 18) }),
        rec({ id: 'd', title: 'The Cable Layers', start: at(21, 20) }),
      ],
      rules,
    )
    expect(groups.map((g) => g.title)).toEqual(['Harbor Evening News', 'Inspector Vane'])
    expect(groups[1].items.map((r) => r.id)).toEqual(['b', 'a'])
    expect(groups[1].rule?.keep).toBe(5)
    expect(oneOffs.map((r) => r.id)).toEqual(['d'])
  })

  it('words episodes, days and when something recorded', () => {
    expect(recordingEpisodeWords({ season: 2, episode: 7, episodeTitle: 'The Night Train', start: at(22, 21) })).toBe('S2 E7 · The Night Train')
    expect(recordingEpisodeWords({ start: at(23, 18) }, 'en-US')).toBe('Monday, Nov 23')
    expect(dayWords(new Date(2026, 10, 24, 21).getTime(), now, 'en-US', true)).toBe('Tonight')
    expect(dayWords(new Date(2026, 10, 24, 9).getTime(), now, 'en-US', true)).toBe('Today')
    expect(dayWords(new Date(2026, 10, 25, 9).getTime(), now, 'en-US')).toBe('Tomorrow, Wed, Nov 25')
    expect(recordedWords(rec({ start: at(23, 18), end: at(23, 18, 30) }), now, 'en-US')).toBe('Recorded yesterday · 30 min')
    expect(recordedWords(rec({}), now, 'en-US')).toBe('Recorded Sun, Nov 22 · 60 min')
  })

  it('lays the schedule out by day', () => {
    const days = scheduleDays([rec({ id: 'x', start: at(25, 18) }), rec({ id: 'y', start: at(24, 21) }), rec({ id: 'z', start: at(24, 21, 30) })], now, 'en-US')
    expect(days.map((d) => [d.label, d.items.map((r) => r.id)])).toEqual([
      ['Tonight', ['y', 'z']],
      ['Tomorrow, Wed, Nov 25', ['x']],
    ])
  })

  it('turns a clash into an alert naming what holds the tuners', () => {
    const holders = [{ id: 'h1', title: 'Inspector Vane' }, { id: 'h2', title: 'Conference Football' }] as Recording['conflictsWith']
    expect(clashAlert({ title: 'Coastline Rescue', start: at(24, 21), conflictsWith: holders }, now, 'en-US')).toEqual({
      heading: 'Coastline Rescue won’t record tonight at 9:00 PM.',
      detail: 'The tuners will be recording Inspector Vane and Conference Football.',
    })
    const twice = [...(holders ?? []), { id: 'h3', title: 'Inspector Vane' }] as Recording['conflictsWith']
    expect(clashAlert({ title: 'Coastline Rescue', start: at(24, 20), conflictsWith: twice }, now, 'en-US')).toEqual({
      heading: 'Coastline Rescue isn’t recording.',
      detail: 'The tuners will be recording Inspector Vane and Conference Football.',
    })
    expect(clashAlert({ title: 'X', start: at(25, 9), reason: 'no_tuners' }, now, 'en-US').heading).toBe('X won’t record tomorrow at 9:00 AM.')
  })

  it('words a rule, saying you for your own', () => {
    expect(ruleWords({ kind: 'series', newOnly: true, anyChannel: true, number: '9.1', keep: 5, userName: 'Sam' })).toBe('New episodes · any channel · keeps the latest 5 · set by Sam')
    expect(ruleWords({ kind: 'manual', newOnly: false, anyChannel: false, number: '2.1', keep: 0, userName: 'robin' }, 'Weekdays at 6:00 PM', 'robin')).toBe('Weekdays at 6:00 PM · 2.1 · set by you')
  })
})
