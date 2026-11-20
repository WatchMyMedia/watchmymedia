import { describe, expect, it } from 'vitest'
import type { LiveProgramme } from '../../../src/api/liveTV'
import type { Recording, RecordingRule } from '../../../src/api/recordings'
import { liveProblem, loggedKind, lowerQuality, stillWatchingWords } from './liveProblems'
import { DEFAULT_OPTIONS, atWords, createBody, optionsSummary, recordChoice, recordSet, recordedNote, ruleBody, stepKeep, stepPadding } from './recordModel'
import { actions, card, details, playRequest, rowCount } from './recordingsModel'
import { bannerReminder, loadSeen, markSeen, nextCheck, reminderRow, remindersHeading } from './remindersModel'
import { onNowCards, onNowFocusLine, qualityOptions, qualityShort, recordingNowLine, streamLine } from './liveWords'
import type { LiveChannel } from '../../../src/api/liveTV'
import type { LiveReminder } from '../../../src/lib/liveReminders'

const at = (h: number, m = 0, day = 0) => new Date(2026, 10, 16 + day, h, m).getTime()
const NOW = at(20, 12)
const iso = (t: number) => new Date(t).toISOString()
const channel = { id: 'ch-4-2', sourceId: 's', number: '4.2', name: 'Sleuth Hour', enabled: true, available: true } as LiveChannel
const vane: LiveProgramme = { id: 'p1', title: 'Inspector Vane', start: iso(at(21)), end: iso(at(22)), season: 3, episode: 6, episodeTitle: 'The Glass Orchard', seriesKey: 'vane' }

describe('the record panel', () => {
  it('offers this episode, every new one, every one and the options for a series', () => {
    const view = recordChoice(vane, channel, NOW, DEFAULT_OPTIONS)
    expect(view.choices.map((c) => c.label)).toEqual(['Just this episode', 'Every new episode', 'Every episode', 'Options'])
    expect(view.choices[0].detail).toBe('Today at 9:00 PM · S3 E6 · The Glass Orchard')
    expect(view.sub).toBe('Mon 9:00–10:00 PM · 4.2 Sleuth Hour')
    const film = recordChoice({ ...vane, seriesKey: undefined, start: iso(at(20)), end: iso(at(21, 30)) }, channel, NOW, DEFAULT_OPTIONS)
    expect(film.choices.map((c) => c.label)).toEqual(['Record the rest', 'Options'])
  })

  it('says what is set, and the clash with its holders', () => {
    const set = recordSet({ ...vane, recording: { id: 'r', state: 'scheduled', series: true, canCancel: true } }, channel, undefined,
      { id: 'rule', kind: 'series', title: 'Inspector Vane', userName: 'Robin', newOnly: true, anyChannel: false, keep: 0, padBefore: 1, padAfter: 2, canCancel: true, number: '4.2' }, NOW)
    expect(set.eyebrow).toBe('Will record')
    expect(set.choices.map((c) => c.id)).toEqual(['editSeries', 'cancel', 'deleteSeries'])
    const holders = [
      { id: 'h1', title: 'Inspector Vane', number: '4.2', channelName: 'Sleuth Hour', start: iso(at(21)), end: iso(at(22)), userName: 'Robin', running: false },
      { id: 'h2', title: 'Conference Football', number: '4.1', channelName: 'NFLD-HD', start: iso(at(21)), end: iso(at(22, 30)), userName: 'Sam', running: true },
    ]
    const recording = { id: 'c', title: 'Coastline Rescue', start: iso(at(21)), end: iso(at(22)), state: 'conflict', reason: 'tuners_busy', canCancel: true, userName: 'Robin', conflictsWith: holders } as Recording
    const clash = recordSet({ ...vane, title: 'Coastline Rescue', recording: { id: 'c', state: 'conflict', series: false, canCancel: true } }, channel, recording, undefined, NOW)
    expect(clash.eyebrow).toBe('Won’t record')
    expect(clash.choices.map((c) => c.label)).toEqual(['Record this instead of Inspector Vane', 'Don’t record'])
    expect(clash.lines?.[1]).toBe('Conference Football on 4.1 · set by Sam · recording now')
    expect(clash.foot).toMatch(/^Both tuners are recording at 9:00 PM/)
  })

  it('builds requests, steps the options and words them', () => {
    expect(createBody(vane, 'series', { ...DEFAULT_OPTIONS, keep: 5, padBefore: 2 })).toEqual({ programmeId: 'p1', scope: 'series', newOnly: true, anyChannel: false, keep: 5, padBefore: 2 })
    expect(createBody(vane, 'once', DEFAULT_OPTIONS)).toEqual({ programmeId: 'p1', scope: 'once' })
    expect(ruleBody({ kind: 'manual' }, { ...DEFAULT_OPTIONS, padBefore: 5 })).toEqual({ newOnly: true, anyChannel: false, keep: 0 })
    expect([stepPadding(undefined, 1), stepPadding(0, -1), stepPadding(60, 1), stepPadding(5, 1)]).toEqual([0, undefined, 60, 15])
    expect([stepKeep(0, -1), stepKeep(10, 1), stepKeep(4, 1)]).toEqual([0, 10, 5])
    expect(optionsSummary({ newOnly: false, anyChannel: true, keep: 3 }, '4.2', true)).toBe('Every episode on any channel, the latest 3 kept. An episode already recorded or in your library is skipped.')
    expect(optionsSummary({ ...DEFAULT_OPTIONS, padBefore: 1, padAfter: 2 }, '4.2', false)).toBe('Starts 1 min early and ends 2 min late.')
    expect(atWords(at(9, 0, 2), NOW)).toBe('Wed at 9:00 AM')
    expect(recordedNote({ title: 'Inspector Vane', state: 'scheduled', start: iso(at(21)) }, 'once', true, NOW)).toBe('Will record Inspector Vane today at 9:00 PM')
    expect(recordedNote({ title: 'Inspector Vane', state: 'scheduled', start: iso(at(21)) }, 'series', false, NOW)).toBe('Will record every episode of Inspector Vane')
  })
})

const rec = (extra: Partial<Recording>): Recording => ({
  id: 'r', userName: 'Robin', sourceId: 's', channelId: 'c', number: '2.1', channelName: 'HRBR-HD', title: 'Harbor Evening News',
  start: iso(at(20)), end: iso(at(21)), state: 'scheduled', series: false, padBefore: 1, padAfter: 2, bytes: 0, gaps: [], canCancel: true, ...extra,
})

describe('the recordings', () => {
  it('words each card by its state', () => {
    expect(card({ kind: 'recording', section: 'now', item: rec({ state: 'recording', startedAt: iso(at(19, 59)) }) }, NOW)).toMatchObject({ line: '8:00–9:00 PM · 13 min so far', badge: { label: 'REC', tone: 'rec' } })
    expect(card({ kind: 'recording', section: 'recorded', item: rec({ state: 'partial', gaps: [{ start: iso(at(20, 12)), end: iso(at(20, 14)), reason: 'signal_lost' }] }) }, NOW).line).toBe('Mon · missing 8:12–8:14 PM')
    expect(card({ kind: 'recording', section: 'recorded', item: rec({ state: 'failed', reason: 'missed' }) }, NOW)).toMatchObject({ dim: true, line: 'Mon · Missed: the server wasn’t running' })
    expect(rowCount('upcoming', 4)).toBe('4 · soonest first')
  })

  it('offers what each can do, first what OK does', () => {
    expect(actions({ kind: 'recording', section: 'now', item: rec({ state: 'recording', ruleId: 'x', series: true }) }).map((a) => a.id)).toEqual(['watchStart', 'stop', 'editSeries'])
    expect(actions({ kind: 'recording', section: 'recorded', item: rec({ state: 'done', canDelete: true, canCancel: false }) }).map((a) => a.id)).toEqual(['watch', 'delete'])
    const holders = [{ id: 'h', title: 'X', number: '1', channelName: 'n', start: iso(at(21)), end: iso(at(22)), userName: 'Sam', running: false }]
    expect(actions({ kind: 'recording', section: 'conflicts', item: rec({ state: 'conflict', reason: 'tuners_busy', conflictsWith: holders }) }).map((a) => a.id)).toEqual(['instead', 'cancel', 'guide'])
    // Someone else's, set by Robin: Sam may only open it.
    expect(actions({ kind: 'recording', section: 'upcoming', item: rec({ canCancel: false }) }).map((a) => a.id)).toEqual(['guide'])
    const rule = { id: 'rule', kind: 'series', title: 'Inspector Vane', userName: 'Robin', newOnly: true, anyChannel: false, keep: 0, padBefore: 1, padAfter: 2, canCancel: true, number: '4.2' } as RecordingRule
    expect(details({ kind: 'rule', section: 'series', item: rule }, NOW, 'Sam')).toMatchObject({ status: 'Series · every new episode', ok: { id: 'editSeries', label: 'Edit series' }, hold: 'Delete series' })
    expect(details({ kind: 'recording', section: 'now', item: rec({ state: 'recording', startedAt: iso(at(20)) }) }, NOW, 'Robin')).toMatchObject({ status: 'Recording now · 12 min so far · ends 9:00 PM', ok: { id: 'watchStart' } })
  })

  it('plays a recording from its start while it records', () => {
    expect(playRequest(rec({ state: 'recording' }))).toEqual({ kind: 'recording', id: 'r', startMs: 0, title: 'Harbor Evening News' })
    expect(playRequest(rec({ state: 'done' }))).toEqual({ kind: 'recording', id: 'r', title: 'Harbor Evening News' })
    expect(playRequest(rec({ state: 'scheduled' }))).toBeUndefined()
  })
})

const reminder = (extra: Partial<LiveReminder>): LiveReminder => ({ id: 'm', number: '2.1', channelName: 'HRBR-HD', programmeId: 'p', title: 'Dollar Dash', episodeTitle: 'Bargain Bins', season: 3, episode: 14, start: iso(at(20, 30)), end: iso(at(21)), state: 'set', ...extra })

describe('reminders', () => {
  it('words the list', () => {
    expect(reminderRow(reminder({}), NOW)).toMatchObject({ sub: 'S3 E14 · Bargain Bins · 2.1 HRBR-HD', state: 'In 18 min', when: 'Mon 8:30 PM' })
    expect(reminderRow(reminder({ start: iso(at(20, 0, 1)) }), NOW).state).toBe('Tomorrow')
    expect(reminderRow(reminder({ state: 'moved', start: iso(at(21, 30)) }), NOW)).toMatchObject({ state: 'Moved to 9:30 PM', tone: 'moved' })
    expect(reminderRow(reminder({ state: 'gone' }), NOW)).toMatchObject({ state: 'No longer in the listings', when: 'Was Mon 8:30 PM' })
    expect(remindersHeading(1, 5)).toBe('1 reminder · soonest first · each 5 min before it starts, set in Settings › Playback')
  })

  it('shows each due reminder once, and remembers it a day', () => {
    const due = reminder({ start: iso(at(20, 15)) })
    const items = [due, reminder({ id: 'later', start: iso(at(22)) })]
    expect(bannerReminder(items, 5, NOW, {})?.id).toBe('m')
    const store = new Map<string, string>()
    const storage = { getItem: (k: string) => store.get(k) ?? null, setItem: (k: string, v: string) => { store.set(k, v) } } as Storage
    const seen = markSeen(storage, {}, due, NOW)
    expect(bannerReminder(items, 5, NOW, seen)).toBeUndefined()
    expect(loadSeen(storage, NOW)).toEqual(seen)
    expect(loadSeen(storage, NOW + 2 * 86_400_000)).toEqual({})
    expect(nextCheck(items, 5, NOW)).toBe(NOW + 15_000)
    expect(nextCheck([items[1]], 5, NOW)).toBe(at(21, 55))
  })
})

describe('the shared words', () => {
  const channels = [
    { ...channel, id: 'a', number: '2.2', name: 'Harbor Weather', now: { id: 'w', title: 'Weather Now', start: iso(at(19)), end: iso(at(21)) } },
    { ...channel, id: 'b', number: '4.3', name: 'Coast Shop' },
    { ...channel, id: 'c', number: '36.1', name: 'Gone', available: false },
  ] as LiveChannel[]
  it('builds the On now row from what can play', () => {
    const cards = onNowCards(channels, NOW)
    expect(cards.map((c) => [c.title, c.line])).toEqual([['Weather Now', '48 min left'], ['Coast Shop', 'No listings']])
    expect(cards[0].label).toBe('Watch 2.2 Harbor Weather, Weather Now, 48 minutes left')
    expect(onNowFocusLine(cards[0])).toBe('Press OK to watch 2.2 Harbor Weather · Weather Now, 48 min left')
    expect(onNowFocusLine(cards[1])).toBe('Press OK to watch 4.3 Coast Shop')
    expect([recordingNowLine(0), recordingNowLine(1), recordingNowLine(2)]).toEqual([undefined, '1 recording now', '2 recordings now'])
  })

  it('words the qualities and the stream', () => {
    expect(qualityOptions(['auto', '1080', '720'], '720p').map((q) => [q.label, q.detail])).toEqual([['Automatic', 'Now 720p · follows your connection'], ['1080p · 8 Mbps', 'Live TV default'], ['720p · 4 Mbps', undefined]])
    expect(qualityShort('auto', undefined)).toBe('Auto · Starting')
    expect(streamLine({ tuning: false, quality: '1080', mode: 'video' })).toBe('Transcoding · 1080p')
    expect(streamLine({ tuning: true, quality: '1080' })).toBe('Starting')
  })
})

describe('Live TV’s problems', () => {
  it('words each failure with its actions, the first focused', () => {
    expect(liveProblem({ reason: 'tuners_busy', title: '', detail: '' }, { admin: true, quality: '1080' })).toMatchObject({ title: 'All tuners are in use', adminNote: 'You can stop someone’s viewing from Activity in the web app.' })
    expect(liveProblem({ reason: 'tuners_busy', title: '', detail: '' }, { admin: false, quality: '1080' }).adminNote).toBeUndefined()
    expect(liveProblem({ reason: 'no_signal', title: '', detail: '' }, { number: '26.1', admin: false, quality: '1080' })).toMatchObject({ title: 'No signal on 26.1', others: true, actions: [{ id: 'retry' }, { id: 'another' }] })
    expect(liveProblem({ reason: 'stream_stalled', title: '', detail: '' }, { number: '7.1', admin: false, quality: '1080' }).actions[0]).toEqual({ id: 'lower', label: 'Play at 720p' })
    expect(liveProblem({ reason: 'recording', title: '', detail: '', recordingId: 'r', recordingTitle: 'Station 9' }, { admin: false, quality: '1080' }).actions[0].id).toBe('watchRecording')
    expect(liveProblem({ reason: 'pause_expired', title: '', detail: '' }, { number: '7.1', admin: false, quality: '1080', pauseMinutes: 5 })).toMatchObject({ dialog: true, title: 'The tuner was given back' })
    expect(liveProblem({ title: 'Live TV could not start', detail: 'Try again, or choose another channel.' }, { admin: false, quality: '1080' }).title).toBe('Live TV could not start')
    expect([lowerQuality('auto'), lowerQuality('480'), lowerQuality('360')]).toEqual(['720', '360', undefined])
    expect(stillWatchingWords(59.2)).toBe('1:00')
  })

  it('logs failures for About, not the stops people chose or expect', () => {
    expect([loggedKind('no_signal'), loggedKind('encoder_failed'), loggedKind('access_revoked'), loggedKind('tuners_busy'), loggedKind(undefined)]).toEqual(['network', 'format', 'auth', 'other', 'other'])
    expect([loggedKind('pause_expired'), loggedKind('inactive'), loggedKind('recording'), loggedKind('stopped_by_admin')]).toEqual([undefined, undefined, undefined, undefined])
  })
})
