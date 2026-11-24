import { describe, expect, it } from 'vitest'
import { clockWords, copiesView, copyQuality, latestPlace, orderCopies, placeWords, playCopy, serverHeading, type CopyServer, type ServerCopy } from './copies'

const MIN = 60_000
const home: CopyServer = { id: 'home', name: 'Home', current: true, away: false }
const cabin: CopyServer = { id: 'cabin', name: 'Cabin', current: false, away: true }
const den: CopyServer = { id: 'den', name: 'Den server', current: false, away: false }
const uhd = { id: 1, width: 3840, height: 2160, hdr: 'hdr10' as const, durationMs: 118 * MIN }
const hd = { id: 2, width: 1920, height: 1080, durationMs: 118 * MIN }
const at = (h: number) => new Date(Date.UTC(2026, 10, 23, h)).toISOString()
const place = (min: number, h: number, fileId?: number) => ({ positionMs: min * MIN, durationMs: 118 * MIN, completed: false, updatedAt: at(h), fileId })

const homeCopy = (watch?: ServerCopy['watch']): ServerCopy => ({ server: home, titleId: 102, files: [uhd, hd], watch })
const cabinCopy = (watch?: ServerCopy['watch']): ServerCopy => ({ server: cabin, titleId: 5102, files: [{ ...hd, id: 51 }], watch })

describe('a title’s copies across servers', () => {
  it('names a version and a group in a few words', () => {
    expect(copyQuality(uhd)).toBe('4K HDR')
    expect(copyQuality({ ...uhd, hdr: 'dolbyvision' })).toBe('4K Dolby Vision')
    expect(copyQuality(hd)).toBe('1080p')
    expect(serverHeading('Home')).toBe('Home server')
    expect(serverHeading('Den server')).toBe('Den server')
    expect(placeWords(place(66, 1))).toBe('52 min left')
    expect(placeWords(undefined)).toBe('Not started')
    expect(placeWords({ ...place(0, 1), completed: true })).toBe('Watched')
    expect(clockWords((66 * 60 + 12) * 1000)).toBe('1:06:12')
  })

  it('puts the current server first, then Servers’ order, a server away from home last', () => {
    const copies = orderCopies([cabinCopy(), { server: den, titleId: 9, files: [hd] }, homeCopy()], ['den', 'cabin'])
    expect(copies.map((c) => c.server.id)).toEqual(['home', 'den', 'cabin'])
  })

  it('plays the chosen copy, else the latest place on any server, else the current server’s', () => {
    const copies = [homeCopy(place(66, 1, 1)), cabinCopy(place(80, 3, 51))]
    expect(latestPlace(copies)?.copy.server.id).toBe('cabin')
    expect(playCopy(copies, undefined)).toMatchObject({ copy: { server: { id: 'cabin' } }, file: { id: 51 } })
    expect(playCopy(copies, { server: 'home', file: 2 })).toMatchObject({ copy: { server: { id: 'home' } }, file: { id: 2 } })
    // A choice whose copy is gone falls back.
    expect(playCopy(copies, { server: 'den', file: 7 })?.copy.server.id).toBe('cabin')
    expect(playCopy([homeCopy(), cabinCopy()], undefined)).toMatchObject({ copy: { server: { id: 'home' } }, file: { id: 1 } })
  })
})

describe('what the page says about them', () => {
  it('says the title is also on the other server, and names the copy Play uses', () => {
    const v = copiesView(home, [homeCopy(place(66, 3, 1)), cabinCopy()], undefined)
    expect(v.also).toBe('Also on Cabin')
    expect(v.button).toBe('Home · 4K HDR')
    expect(v.play).toEqual({ ref: { server: 'home', file: 1 } })
    expect(v.playLabel).toBeUndefined()
    expect(v.groups.map((g) => [g.heading, g.note, g.rows.map((r) => `${r.quality} ${r.place}${r.chosen ? ' plays next' : ''}`)])).toEqual([
      ['Home server', undefined, ['4K HDR 52 min left plays next', '1080p 52 min left']],
      ['Cabin server', 'Away from home, so it may be converted to keep up.', ['1080p Not started']],
    ])
  })

  it('resumes from the latest place, on whichever copy plays', () => {
    const copies = [homeCopy(place(30, 1, 1)), cabinCopy(place(66, 3, 51))]
    const v = copiesView(home, copies, undefined)
    expect(v.also).toBe('Also on Cabin · you got further there')
    expect(v.playLabel).toBe('Resume · from Cabin')
    expect(v.button).toBe('Cabin · 1080p')
    expect(v.where?.text).toBe('1 h 06 min in, on Cabin')
    expect(v.resumeNote).toBe('Resume starts at 1:06:00, where you got to on Cabin, whichever you choose.')
    // Home's 4K copy chosen: it plays from Cabin's place.
    const chosen = copiesView(home, copies, { server: 'home', file: 1 })
    expect(chosen.play).toEqual({ ref: { server: 'home', file: 1 }, startMs: 66 * MIN })
    expect(chosen.playLabel).toBeUndefined()
    expect(chosen.button).toBe('Home · 4K HDR')
  })

  it('says where a title opened from another server is, and draws no button with one copy', () => {
    const v = copiesView(cabin, [cabinCopy(place(63, 2, 51))], undefined)
    expect(v.also).toBe('On Cabin server · away from home')
    expect(v.button).toBeUndefined()
    expect(v.groups).toEqual([])
    expect(v.play).toEqual({ ref: { server: 'cabin', file: 51 } })
    expect(copiesView(home, [homeCopy()], undefined).also).toBeUndefined()
  })
})
