import { describe, expect, it } from 'vitest'
import { onNowCards } from './onNow'
import type { components } from '../api/schema'

type LiveChannel = components['schemas']['LiveTVChannel']

const now = new Date(2026, 10, 23, 20, 12).getTime()
const at = (h: number, m = 0) => new Date(2026, 10, 23, h, m).toISOString()
const ch = (over: Partial<LiveChannel>): LiveChannel =>
  ({ id: 'c', number: '4.1', name: 'NFLD', enabled: true, available: true, sourceId: 's', favourite: false, ...over }) as LiveChannel

describe('On now cards', () => {
  it('says until when, what is left and what is next', () => {
    const [card] = onNowCards([ch({ now: { id: 'p', title: 'The Sleuth Files', start: at(20), end: at(21) } as never, next: { id: 'q', title: 'Conference Football', start: at(21), end: at(22) } as never })], now, 'en-US')
    expect(card).toMatchObject({ channel: '4.1 NFLD', title: 'The Sleuth Files', until: 'Until 9:00 PM · 48 min left', next: 'Next: Conference Football', pct: 20, recording: false })
    expect(card.label).toBe('The Sleuth Files on 4.1 NFLD, until 9:00 PM, 48 min left, next, Conference Football')
  })

  it('words a movie by its year', () => {
    const [card] = onNowCards([ch({ now: { id: 'p', title: 'The Iron Coast', kind: 'movie', airDate: '1958-01-01', start: at(19, 30), end: at(21, 30) } as never })], now, 'en-US')
    expect(card.until).toBe('Movie · 1958 · until 9:30 PM')
  })

  it('marks a recording, and puts favourites first, then by number', () => {
    const p = (id: string) => ({ id, title: id, start: at(20), end: at(21) }) as never
    const cards = onNowCards(
      [
        ch({ id: 'a', number: '7.1', now: p('A') }),
        ch({ id: 'b', number: '2.1', now: p('B') }),
        ch({ id: 'c', number: '12.1', now: p('C'), favourite: true }),
        ch({ id: 'd', number: '3.1', now: { ...(p('D') as object), recording: { id: 'r', state: 'recording' } } as never }),
      ],
      now,
    )
    expect(cards.map((c) => c.channelId)).toEqual(['c', 'b', 'd', 'a'])
    expect(cards[2].recording).toBe(true)
  })

  it('leaves out channels that are off, unavailable or have nothing on', () => {
    const p = { id: 'p', title: 'X', start: at(20), end: at(21) } as never
    expect(onNowCards([ch({ enabled: false, now: p }), ch({ available: false, now: p }), ch({})], now)).toEqual([])
  })
})
