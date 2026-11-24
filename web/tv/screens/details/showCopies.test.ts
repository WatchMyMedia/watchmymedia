import { describe, expect, it } from 'vitest'
import type { components } from '../../../src/api/schema'
import { copiesView, type CopyServer } from './copies'
import { elsewhereId, elsewhereLine, mergeSeason } from './showCopies'

type Episode = components['schemas']['Episode']

const home: CopyServer = { id: 'home', name: 'Home', current: true, away: false }
const cabin: CopyServer = { id: 'cabin', name: 'Cabin', current: false, away: true }
const file = (id: number, height = 1080) => ({ id, width: height === 2160 ? 3840 : 1920, height, durationMs: 46 * 60_000, libraryId: 1, path: '', relativePath: '', size: 1, modifiedAt: '', status: 'present' as const,
  parsed: { kind: 'episode' as const, title: '' }, firstSeenAt: '', addedAt: '', lastSeenAt: '', matchStatus: 'matched' as const, matchLocked: false, matchIgnored: false })
const ep = (id: number, n: number, hasFile: boolean, over: Partial<Episode> = {}): Episode => ({
  id, number: n, seasonId: 1, title: `Episode ${n}`, overview: '', hasFile, aired: true, isNew: false, runtimeMin: 46, files: hasFile ? [file(id * 10)] : [], ...over,
})

describe('a show’s season across servers', () => {
  const page = { server: home, episodes: [ep(1, 1, true), ep(2, 2, true), ep(3, 3, false)] }
  const other = { server: cabin, episodes: [ep(101, 1, true), ep(102, 2, false), ep(103, 3, true), ep(104, 4, true)] }

  it('lists each number once, the page’s own where it has the file, another server’s where only that one does', () => {
    const { episodes, copies } = mergeSeason(2, page, [other])
    expect(episodes.map((e) => [e.id, e.number, e.on?.id])).toEqual([[1, 1, undefined], [2, 2, undefined], [3, 3, 'cabin'], [elsewhereId(2, 4), 4, 'cabin']])
    expect(copies.get(1)?.map((c) => [c.server.id, c.titleId])).toEqual([['home', 1], ['cabin', 101]])
    expect(copies.get(2)?.map((c) => c.server.id)).toEqual(['home'])
    expect(copies.get(3)?.map((c) => [c.server.id, c.titleId])).toEqual([['cabin', 103]])
    // An episode only Cabin has plays Cabin's file.
    expect(episodes[2].files[0].id).toBe(1030)
  })

  it('says where an episode only another server has is', () => {
    expect(elsewhereLine({ on: cabin, runtimeMin: 46 })).toBe('On Cabin · 46 min')
    expect(elsewhereLine({ runtimeMin: 46 })).toBeUndefined()
  })

  it('keeps the chooser’s button for an episode with one copy while the show is on two servers', () => {
    const { copies } = mergeSeason(2, page, [other])
    const one = copiesView(home, copies.get(2) ?? [], undefined, true)
    expect(one.button).toBe('Home · 1080p')
    expect(one.groups.map((g) => g.heading)).toEqual(['Home server'])
    const elsewhere = copiesView(cabin, copies.get(3) ?? [], undefined, true)
    expect(elsewhere.button).toBe('Cabin · 1080p')
    expect(elsewhere.also).toBe('On Cabin server · away from home')
    expect(copiesView(home, copies.get(2) ?? [], undefined).button).toBeUndefined()
  })
})
