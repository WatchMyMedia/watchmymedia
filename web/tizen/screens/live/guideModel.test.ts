import { describe, expect, it } from 'vitest'
import type { LiveChannel, LiveProgramme } from '../../../src/api/liveTV'
import { HALF_HOUR, HOUR } from '../../../src/lib/liveGuide'
import { cellAt, channelMenu, firstRow, guideBounds, guideStep, heroFor, holdHint, nowFraction, parseTarget, programmeMenu, slotLabels, targetId, type GuideRowData, type GuideTarget } from './guideModel'

// Monday 8:12 PM local, as on the boards.
const at = (h: number, m = 0, day = 0) => new Date(2026, 10, 16 + day, h, m).getTime()
const NOW = at(20, 12)
const iso = (t: number) => new Date(t).toISOString()
const prog = (id: string, start: number, minutes: number, extra: Partial<LiveProgramme> = {}): LiveProgramme =>
  ({ id, title: id, start: iso(start), end: iso(start + minutes * 60_000), ...extra })

const rows: GuideRowData[] = [
  { id: 'a', listings: true, programmes: [prog('a1', at(19), 60), prog('a2', at(20), 30), prog('a3', at(20, 30), 30), prog('a4', at(21), 120)] },
  { id: 'b', listings: false, programmes: [] },
  { id: 'c', listings: true, programmes: [prog('c1', at(20), 60), prog('c2', at(21), 60), prog('c3', at(22), 60)] },
]
const START = at(20)
const bounds = guideBounds(NOW, at(19, 0, 2))
const p = (channelId: string, programmeId: string): GuideTarget => ({ kind: 'programme', channelId, programmeId })

describe('the guide’s arrows', () => {
  it('names cells by channel and programme, and reads them back', () => {
    for (const t of [p('a', 'a2'), { kind: 'channel', channelId: 'a' }, { kind: 'none', channelId: 'b' }] as GuideTarget[]) expect(parseTarget(targetId(t))).toEqual(t)
    expect(parseTarget('row/1')).toBeUndefined()
  })

  it('keeps the focused row fourth from the top until the end of the list', () => {
    expect([0, 3, 4, 10, 19].map((i) => firstRow(i, 20))).toEqual([0, 0, 1, 7, 13])
    expect(firstRow(2, 4)).toBe(0)
  })

  it('starts at now’s half hour and ends where the listings do', () => {
    expect(bounds.earliest).toBe(at(20))
    expect(bounds.latest).toBe(at(17, 0, 2))
  })

  it('goes programme by programme, and to the channel from the first', () => {
    expect(guideStep(rows, p('a', 'a2'), 'right', START, at(20), bounds)).toEqual({ target: p('a', 'a3'), start: START, anchor: at(20, 30) })
    expect(guideStep(rows, p('a', 'a2'), 'left', START, at(20), bounds)).toEqual({ target: { kind: 'channel', channelId: 'a' }, start: START, anchor: at(20) })
    expect(guideStep(rows, { kind: 'channel', channelId: 'a' }, 'left', START, at(20), bounds)).toBe('rail')
    expect(guideStep(rows, { kind: 'channel', channelId: 'c' }, 'right', START, at(21), bounds)).toEqual({ target: p('c', 'c2'), start: START, anchor: at(21) })
  })

  it('moves the window half an hour at its edge, keeping focus while more of the programme is out of view', () => {
    const step = guideStep(rows, p('a', 'a4'), 'right', START, at(21), bounds)
    expect(step).toEqual({ target: p('a', 'a4'), start: START + HALF_HOUR, anchor: at(21) })
    // Back again: the one before is in view; the one before that brings the window back.
    expect(guideStep(rows, p('a', 'a4'), 'left', START + HALF_HOUR, at(21), bounds)).toMatchObject({ target: p('a', 'a3'), start: START + HALF_HOUR })
    expect(guideStep(rows, p('a', 'a3'), 'left', START + HALF_HOUR, at(20, 30), bounds)).toMatchObject({ target: p('a', 'a2'), start: START })
    // The next programme beyond the window brings the window to it.
    expect(guideStep(rows, p('c', 'c2'), 'right', START, at(21), bounds)).toEqual({ target: p('c', 'c3'), start: START + HALF_HOUR, anchor: at(22) })
  })

  it('keeps to the same time up and down, through a channel without listings', () => {
    const down = guideStep(rows, p('a', 'a3'), 'down', START, at(20, 30), bounds)
    expect(down).toEqual({ target: { kind: 'none', channelId: 'b' }, start: START, anchor: at(20, 30) })
    expect(guideStep(rows, { kind: 'none', channelId: 'b' }, 'down', START, at(20, 30), bounds)).toMatchObject({ target: p('c', 'c1') })
    expect(guideStep(rows, { kind: 'none', channelId: 'b' }, 'down', START, at(21, 15), bounds)).toMatchObject({ target: p('c', 'c2') })
    expect(guideStep(rows, p('a', 'a2'), 'up', START, at(20), bounds)).toBe('above')
    expect(guideStep(rows, p('c', 'c1'), 'down', START, at(20), bounds)).toBeUndefined()
    expect(guideStep(rows, { kind: 'channel', channelId: 'a' }, 'down', START, at(20), bounds)).toMatchObject({ target: { kind: 'channel', channelId: 'b' } })
  })

  it('finds the cell at a time, or the next in view', () => {
    expect(cellAt(rows[2], at(19), START, START + 2 * HOUR)).toEqual(p('c', 'c1'))
    expect(cellAt({ id: 'd', listings: true, programmes: [] }, at(20), START, START + 2 * HOUR)).toEqual({ kind: 'none', channelId: 'd' })
  })

  it('labels the half hours and places now', () => {
    expect(slotLabels(START)).toEqual(['8:00 PM', '8:30 PM', '9:00 PM', '9:30 PM'])
    expect(nowFraction(START, NOW)).toBeCloseTo(0.1)
    expect(nowFraction(START + HOUR, NOW)).toBeUndefined()
  })
})

const channel: LiveChannel = { id: 'ch-4-1', sourceId: 's', number: '4.1', name: 'NFLD-HD', enabled: true, available: true }

describe('the hero and Hold OK', () => {
  it('says what is on now, with its progress and the hold', () => {
    const on = prog('Sleuth', at(20), 60, { title: 'The Sleuth Files', season: 5, episode: 3, episodeTitle: 'Cold Ledger', new: true, hd: true, categories: ['Series', 'Crime drama'] })
    const hero = heroFor(channel, on, { now: NOW, favourite: true, lead: 5, canRecord: true })
    expect(hero).toMatchObject({ when: 'On now', airing: true, episode: 'S5 E3 · Cold Ledger', categories: 'Series · Crime drama', time: 'Mon 8:00–9:00 PM', left: '48 min left', watch: 'Watch 4.1', hold: 'Record the rest' })
    expect(hero.progress).toBeCloseTo(0.2)
  })

  it('says when a later programme is on, and its reminder', () => {
    const later = prog('Game', at(21), 90, { title: 'Conference Football', reminder: true })
    const hero = heroFor(channel, later, { now: NOW, favourite: false, lead: 5, canRecord: false })
    expect(hero).toMatchObject({ when: 'Later today', startsIn: 'Starts in 48 min', later: 'On 4.1 at 9:00 PM', watch: 'Watch 4.1 now', reminderLine: 'Reminder set · 5 min before', hold: 'Remove reminder' })
    expect(heroFor(channel, prog('x', at(21, 0, 1), 30), { now: NOW, favourite: false, lead: 5, canRecord: false }).when).toBe('Tomorrow')
  })

  it('offers Record and the reminder together, and a set recording’s options', () => {
    const later = prog('x', at(22), 60)
    expect(holdHint(later, NOW, true, false, true)).toBe('Record or remind me')
    expect(holdHint({ ...later, recording: { id: 'r', state: 'scheduled', series: true } }, NOW, true, false, true)).toBe('Recording options, remind me')
    expect(holdHint(later, NOW, false, false, false)).toBeUndefined()
  })

  it('without listings, plays the channel', () => {
    const hero = heroFor(channel, undefined, { now: NOW, favourite: false, lead: 5, canRecord: true })
    expect(hero).toMatchObject({ title: 'NFLD-HD', when: 'Live', watch: 'Watch 4.1' })
    expect(hero.hold).toBeUndefined()
  })

  it('builds the menus as the boards do', () => {
    expect(channelMenu(channel, true).items.map((i) => i.label)).toEqual(['Remove from favourites', 'Watch 4.1'])
    expect(channelMenu(channel, false).subtitle).toBe('Not a favourite')
    const later = prog('x', at(22), 60, { title: 'Harbor News' })
    const menu = programmeMenu(channel, later, { now: NOW, favourite: false, lead: 5, canRecord: true })
    expect(menu.items.map((i) => i.label)).toEqual(['Record', 'Remind me', 'Watch 4.1 now'])
    expect(menu.subtitle).toBe('Mon 10:00 PM on 4.1')
    expect(menu.note).toContain('Reminders show while WatchMyMedia is open on this TV.')
    const onNow = programmeMenu(channel, prog('y', at(20), 60), { now: NOW, favourite: false, lead: 5, canRecord: false })
    expect(onNow.items.map((i) => i.label)).toEqual(['Watch 4.1'])
    const set = programmeMenu(channel, { ...later, recording: { id: 'r', state: 'recording', series: false } }, { now: at(22, 10), favourite: false, lead: 5, canRecord: true })
    expect(set.items[0]).toMatchObject({ id: 'recordOptions', label: 'Recording options' })
    expect(set.subtitle).toContain('Recording now')
  })
})
