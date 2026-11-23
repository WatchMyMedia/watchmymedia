import { describe, expect, it } from 'vitest'
import { busyWords, favouritesFirst, liveBadge, nextWords, onNowLine, takeoverLine } from './livePlayerModel'

const at = (h: number, m = 0) => new Date(2026, 10, 23, h, m).toISOString()

describe('the Live TV player’s words', () => {
  it('says what is next and what is left on a channel', () => {
    expect(nextWords({ start: at(21), title: 'Station 9' }, 'en-US')).toBe('Next at 9:00 PM · Station 9')
    expect(nextWords(undefined)).toBeUndefined()
    expect(onNowLine({ programme: { id: 'p' } as never, line: '48 min left' }, { title: 'Station 9' })).toBe('48 min left · then Station 9')
    expect(onNowLine({ programme: { id: 'p' } as never, line: '48 min left' })).toBe('48 min left')
    expect(onNowLine({ programme: undefined, line: 'No listings' })).toBe('No listings')
  })

  it('puts favourites first in On now, and says so only when it changes the order', () => {
    const c = (id: string, favourite?: boolean) => ({ id, channel: { favourite } })
    expect(favouritesFirst([c('a'), c('b', true), c('c')]).cards.map((x) => x.id)).toEqual(['b', 'a', 'c'])
    expect(favouritesFirst([c('a'), c('b', true)]).favourites).toBe(true)
    expect(favouritesFirst([c('a'), c('b')]).favourites).toBe(false)
    expect(favouritesFirst([c('a', true)]).favourites).toBe(false)
  })

  it('marks live in words, and what it is when it isn’t', () => {
    expect(liveBadge({ tuning: false, paused: false, behind: false })).toEqual({ words: 'Live', live: true })
    expect(liveBadge({ tuning: false, paused: true, behind: true }).words).toBe('Paused')
    expect(liveBadge({ tuning: false, paused: false, behind: true }).words).toBe('Behind live')
    expect(liveBadge({ tuning: true, paused: false, behind: false }).words).toBe('Tuning')
  })

  it('says when a recording takes this tuner', () => {
    const now = new Date(2026, 10, 23, 20, 12).getTime()
    expect(takeoverLine(at(21), now, 'en-US')).toEqual({ strong: 'At 9:00 PM a recording needs this tuner.', soft: 'Watching stops then.' })
    expect(takeoverLine(at(20), now, 'en-US').strong).toBe('A recording needs this tuner now.')
  })

  it('says what holds the tuners and until when, when one is free, and offers what is recording', () => {
    const rescue = { id: 'r1', title: 'Coastline Rescue', end: at(22) }
    const news = { id: 'r2', title: 'Harbor Evening News', end: at(20, 30) }
    expect(busyWords([news, rescue], 2, 'en-US')).toEqual({
      why: 'Both are recording: Coastline Rescue until 10:00 PM, and Harbor Evening News until 8:30 PM.',
      what: 'One is free at 8:30 PM. Until then you can watch what is recording, from its start. Nothing is taken from another viewer or a recording.',
      watch: rescue,
    })
    expect(busyWords([news], 2, 'en-US')?.why).toBe('One is recording: Harbor Evening News until 8:30 PM.')
    expect(busyWords([news], 2, 'en-US')?.what.startsWith('Until then')).toBe(true)
    expect(busyWords([], 2)).toBeUndefined()
  })
})
