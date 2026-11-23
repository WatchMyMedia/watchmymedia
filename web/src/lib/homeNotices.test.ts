import { describe, expect, it } from 'vitest'
import { homeNotices } from './homeNotices'
import type { components } from '../api/schema'

type Recording = components['schemas']['LiveTVRecording']
type MediaRequest = components['schemas']['MediaRequest']

const now = new Date(2026, 10, 23, 20, 12).getTime()
const at = (h: number, m = 0, day = 23) => new Date(2026, 10, day, h, m).toISOString()
const rec = (over: Partial<Recording>): Recording => ({ id: 'r1', title: 'Valley Bake-Off', state: 'recording', start: at(20), end: at(21), channelId: 'c', ...over }) as Recording
const req = (over: Partial<MediaRequest>): MediaRequest => ({ id: 1, title: 'The Salt Road', mediaType: 'movie', movieId: 7, status: 'available', mine: true, createdAt: at(10), tmdbId: 1, requestedBy: 'r' , ...over }) as MediaRequest

describe('home notices', () => {
  it('says nothing when nothing happened', () => {
    expect(homeNotices({ recording: [], clashes: [], arrived: [], inbox: 0, now })).toEqual([])
  })

  it('names a recording under way and when it ends', () => {
    const [n] = homeNotices({ recording: [rec({})], clashes: [], arrived: [], inbox: 0, now, locale: 'en-US' })
    expect(n).toMatchObject({ strong: 'Recording', rest: ' Valley Bake-Off · until 9:00 PM', to: { kind: 'recordings' } })
  })

  it('flags a clash in the next day, naming what holds the tuner, and leaves later ones', () => {
    const clash = rec({ id: 'c1', state: 'conflict', title: 'Coastline Rescue', start: at(21), conflictsWith: [{ id: 'x', title: 'Valley Bake-Off', running: true, start: at(20), end: at(22), channelName: '', number: '7.1', userName: 'robin' }] })
    const later = rec({ id: 'c2', state: 'conflict', start: at(21, 0, 26) })
    const out = homeNotices({ recording: [], clashes: [clash, later], arrived: [], inbox: 0, now, locale: 'en-US' })
    expect(out).toHaveLength(1)
    expect(out[0]).toMatchObject({ strong: 'Won’t record tonight', rest: ' Coastline Rescue · Valley Bake-Off has the tuner at 9:00 PM ', action: 'Fix' })
  })

  it('links an arrived request to its title, and gathers many into one', () => {
    const one = homeNotices({ recording: [], clashes: [], arrived: [req({})], inbox: 0, now })
    expect(one[0]).toMatchObject({ strong: 'The Salt Road', to: { kind: 'movie', id: 7 }, seen: [1] })
    const show = homeNotices({ recording: [], clashes: [], arrived: [req({ mediaType: 'tv', movieId: undefined, showId: 4 })], inbox: 0, now })
    expect(show[0].to).toEqual({ kind: 'show', id: 4 })
    const many = homeNotices({ recording: [], clashes: [], arrived: [req({ id: 1 }), req({ id: 2 }), req({ id: 3 })], inbox: 0, now })
    expect(many).toHaveLength(1)
    expect(many[0]).toMatchObject({ strong: '3 of your requests', to: { kind: 'requests' }, seen: [1, 2, 3] })
  })

  it('tells an admin what waits in the Inbox', () => {
    expect(homeNotices({ recording: [], clashes: [], arrived: [], inbox: 2, now })[0]).toMatchObject({ strong: '2 waiting', rest: ' in your Inbox', to: { kind: 'inbox' } })
  })
})
