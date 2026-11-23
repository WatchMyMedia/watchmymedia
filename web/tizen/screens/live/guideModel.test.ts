import { describe, expect, it } from 'vitest'
import type { LiveChannel, LiveProgramme } from '../../../src/api/liveTV'
import { HALF_HOUR, HOUR } from '../../../src/lib/liveGuide'
import { cellAt, cellLine, dayHeading, firstRow, guideBounds, guideStep, laterWords, nowFraction, pageStep, parseTarget, programmePanel, slotLabels, stripFor, targetId, type GuideRowData, type GuideTarget } from './guideModel'

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

  it('keeps the focused row third from the top until the end of the list', () => {
    expect([0, 3, 4, 10, 19].map((i) => firstRow(i, 20))).toEqual([0, 1, 2, 8, 14])
    expect(firstRow(2, 4)).toBe(0)
  })

  it('starts at now’s half hour and ends where the listings do', () => {
    expect(bounds.earliest).toBe(at(20))
    expect(bounds.latest).toBe(at(17, 0, 2))
  })

  it('goes programme by programme, and to the menu from the first', () => {
    expect(guideStep(rows, p('a', 'a2'), 'right', START, at(20), bounds)).toEqual({ target: p('a', 'a3'), start: START, anchor: at(20, 30) })
    expect(guideStep(rows, p('a', 'a2'), 'left', START, at(20), bounds)).toBe('rail')
    expect(guideStep(rows, { kind: 'none', channelId: 'b' }, 'left', START, at(20), bounds)).toBe('rail')
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

  it('moves a screen of channels on CH, stopping at the ends', () => {
    const many: GuideRowData[] = Array.from({ length: 14 }, (_, i) => ({ id: `r${i}`, listings: false, programmes: [] }))
    expect(pageStep(many, { kind: 'none', channelId: 'r1' }, 'down', START, at(20))).toMatchObject({ target: { kind: 'none', channelId: 'r7' } })
    expect(pageStep(many, { kind: 'none', channelId: 'r10' }, 'down', START, at(20))).toMatchObject({ target: { kind: 'none', channelId: 'r13' } })
    expect(pageStep(many, { kind: 'none', channelId: 'r13' }, 'down', START, at(20))).toBeUndefined()
    expect(pageStep(many, { kind: 'none', channelId: 'r3' }, 'up', START, at(20))).toMatchObject({ target: { kind: 'none', channelId: 'r0' } })
  })

  it('labels the half hours and places now', () => {
    expect(slotLabels(START)).toEqual(['8:00 PM', '8:30', '9:00', '9:30'])
    expect(nowFraction(START, NOW)).toBeCloseTo(0.1)
    expect(nowFraction(START + HOUR, NOW)).toBeUndefined()
  })
})

const channel: LiveChannel = { id: 'ch-4-1', sourceId: 's', number: '4.1', name: 'NFLD-HD', enabled: true, available: true }

describe('the strip above the grid', () => {
  it('says what is on now, how long is left, and that OK watches', () => {
    const on = prog('Sleuth', at(20), 60, { title: 'The Sleuth Files', season: 5, episode: 3, episodeTitle: 'Cold Ledger', new: true, description: 'A ledger turns up.' })
    expect(stripFor(channel, on, { now: NOW, favourite: true, lead: 5, canRecord: true })).toEqual({
      title: 'The Sleuth Files', isNew: true, airing: true, when: '8:00–9:00 PM · 48 min left', where: '4.1 NFLD-HD',
      about: 'Season 5 · Episode 3 · Cold Ledger. A ledger turns up.', ok: 'Watch',
    })
  })

  it('says when a later programme is on, and what OK opens', () => {
    const later = prog('Game', at(21), 90, { title: 'Conference Football' })
    expect(stripFor(channel, later, { now: NOW, favourite: false, lead: 5, canRecord: true })).toMatchObject({ when: 'Tonight, 9:00–10:30 PM', ok: 'Record or remind', airing: false })
    expect(stripFor(channel, later, { now: NOW, favourite: false, lead: 5, canRecord: false }).ok).toBe('Remind me')
    expect(stripFor(channel, prog('x', at(18, 0, 1), 30), { now: NOW, favourite: false, lead: 5, canRecord: false }).when).toBe('Tomorrow, 6:00–6:30 PM')
  })

  it('without listings, plays the channel', () => {
    expect(stripFor(channel, undefined, { now: NOW, favourite: false, lead: 5, canRecord: true })).toMatchObject({ title: 'NFLD-HD', ok: 'Watch', when: 'No listings' })
  })

  it('words a cell’s second line', () => {
    expect(cellLine(prog('q', at(20), 30, { season: 12, episode: 88 }), START)).toBe('Season 12 · Episode 88')
    expect(cellLine(prog('d', at(20, 30), 30, { new: true }), START)).toBe('New')
    expect(cellLine(prog('w', at(18), 180, { live: true }), START)).toBe('Live · on since 6:00')
    expect(cellLine(prog('m', at(19, 30), 150, { kind: 'movie', airDate: '1958-04-01' }), START)).toBe('Movie · 1958 · on since 7:30')
    expect(cellLine(prog('c', at(21), 60, { new: true, episodeTitle: 'The Low Tide' }), START)).toBe('New · The Low Tide')
  })

  it('names the evening and the half hours', () => {
    expect(dayHeading(START, NOW)).toBe('Tonight')
    expect(dayHeading(at(9, 0, 1), NOW)).toBe('Tomorrow')
    expect(laterWords(at(21), at(22), NOW)).toBe('Tonight, 9:00–10:00 PM')
  })
})

describe('a programme’s panel', () => {
  const later = prog('rescue', at(21), 60, { title: 'Coastline Rescue', seriesKey: 's-rescue', season: 2, episode: 4, episodeTitle: 'The Low Tide', new: true })

  it('offers recording, a reminder, watching now and the favourite, in that order', () => {
    const view = programmePanel(channel, later, { now: NOW, favourite: false, lead: 5, canRecord: true, onNow: 'Quiz Parlor' })
    expect(view.rows.map((r) => r.label)).toEqual(['Record this episode', 'Record every new episode', 'More ways to record', 'Remind me 5 min before', 'Watch 4.1 now', 'Add to favourites'])
    expect(view.rows[4].detail).toBe('Quiz Parlor is on')
    expect(view).toMatchObject({ title: 'Coastline Rescue', when: 'Tonight, 9:00–10:00 PM', where: '4.1 NFLD-HD', about: 'Season 2 · Episode 4 · The Low Tide. New' })
    expect(view.foot).toBe('Reminders show while WatchMyMedia is open on this TV.')
  })

  it('without recording, offers the reminder and watching', () => {
    const view = programmePanel(channel, later, { now: NOW, favourite: true, lead: 0, canRecord: false })
    expect(view.rows.map((r) => r.id)).toEqual(['remind', 'watch', 'unfavourite'])
    expect(view.rows[0].label).toBe('Remind me when it starts')
  })

  it('offers a set recording’s options in place of recording it again', () => {
    const set = { ...later, recording: { id: 'r', state: 'scheduled', series: true } }
    const view = programmePanel(channel, set, { now: NOW, favourite: false, lead: 5, canRecord: true })
    expect(view.rows[0]).toMatchObject({ id: 'recordOptions', next: true })
    expect(view.rows.some((r) => r.id === 'recordOnce')).toBe(false)
  })

  it('records the rest of what is on, and says when a channel can’t play', () => {
    const on = prog('y', at(20), 60, { title: 'Quiz Parlor' })
    const view = programmePanel({ ...channel, available: false }, on, { now: NOW, favourite: false, lead: 5, canRecord: true })
    expect(view.rows.map((r) => r.label)).toEqual(['Record the rest', 'More ways to record', 'Watch 4.1', 'Add to favourites'])
    expect(view.rows[2]).toMatchObject({ disabled: true, detail: 'Not available right now' })
  })
})
