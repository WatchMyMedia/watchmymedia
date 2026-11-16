import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { components } from '../api/schema'
import {
  bannerFor, browserState, canFollow, followLine, stopFailLine, clearBanner, episodeNotices, forgetCursor, forgetOnSignOut, groupNotice, loadBanner, loadCursor, moreShowsNotice, noteFollowAsked,
  notifySwitch, personKey, runCheck, saveBanner, saveCursor, setNotifySwitch, shouldCheck, shouldExplainFollow,
} from './newEpisodes'

type Group = components['schemas']['NewEpisodeGroup']

function memoryStorage() {
  const m = new Map<string, string>()
  return {
    getItem: (k: string) => m.get(k) ?? null,
    setItem: (k: string, v: string) => void m.set(k, v),
    removeItem: (k: string) => void m.delete(k),
    key: (i: number) => [...m.keys()][i] ?? null,
    get length() { return m.size },
  }
}

beforeEach(() => { vi.stubGlobal('localStorage', memoryStorage()) })
afterEach(() => { vi.unstubAllGlobals() })

const at = '2026-11-16T12:00:00Z'
/** A show's group as the feed sends it: earliest first, the earliest to open. */
function group(title: string, episodes: [number, number, string?][], { count = episodes.length, id = 7 } = {}): Group {
  return {
    show: { id, tmdbId: 1000 + id, title, posterPath: `/p${id}.jpg` },
    count,
    episodes: episodes.map(([season, number, t], i) => ({ id: 500 + i, season, number, title: t, arrivedAt: at })),
    open: { episodeId: 500, season: episodes[0][0], number: episodes[0][1] },
  }
}

describe('when this browser checks', () => {
  it('needs a followed show, the switch on and notifications allowed', () => {
    expect(shouldCheck({ follows: 2, on: true, permission: 'granted' })).toBe(true)
    expect(shouldCheck({ follows: 0, on: true, permission: 'granted' })).toBe(false)
    expect(shouldCheck({ follows: 2, on: false, permission: 'granted' })).toBe(false)
    expect(shouldCheck({ follows: 2, on: true, permission: 'default' })).toBe(false)
    expect(shouldCheck({ follows: 2, on: true, permission: 'denied' })).toBe(false)
    // A plain http:// address has no notifications at all.
    expect(shouldCheck({ follows: 2, on: true, permission: 'unsupported' })).toBe(false)
  })
  it('offers Notify me from API level 29', () => {
    expect(canFollow(29)).toBe(true)
    expect(canFollow(30)).toBe(true)
    expect(canFollow(28)).toBe(false)
    expect(canFollow(undefined)).toBe(false)
  })
})

describe('a check', () => {
  it('asks without after the first time and only keeps the cursor', async () => {
    const fetch = vi.fn(async () => ({ cursor: '1841', shows: [group('The Sleuth Files', [[3, 7]])] }))
    expect(await runCheck(undefined, fetch)).toEqual({ cursor: '1841', shows: [] })
    expect(fetch).toHaveBeenCalledWith(undefined)
  })
  it('asks after the cursor and passes on what arrived', async () => {
    const shows = [group('The Sleuth Files', [[3, 7, 'The Long Night']])]
    const fetch = vi.fn(async () => ({ cursor: '1850', shows }))
    expect(await runCheck('1841', fetch)).toEqual({ cursor: '1850', shows })
    expect(fetch).toHaveBeenCalledWith('1841')
  })
  it('keeps the cursor when the server can’t be reached', async () => {
    expect(await runCheck('1841', async () => undefined)).toBeUndefined()
  })
})

describe('what a notification says (notify-system, notify-many)', () => {
  it('names one episode with its title, or its code alone', () => {
    const n = groupNotice(group('The Sleuth Files', [[3, 7, 'The Long Night']]))
    expect([n.title, n.body]).toEqual(['New episode · The Sleuth Files', 'S3 E7 · The Long Night'])
    expect(groupNotice(group('The Sleuth Files', [[3, 7]])).body).toBe('S3 E7')
  })
  it('counts several and gives the first to the last, across seasons when they cross one', () => {
    const one = groupNotice(group('Night Ferry', [[1, 4], [1, 5], [1, 6]]))
    expect([one.title, one.body]).toEqual(['3 new episodes · Night Ferry', 'S1 E4 to E6'])
    const across = groupNotice(group('Harbor Lights', [[2, 10], [3, 1], [3, 2]]))
    expect([across.title, across.body]).toEqual(['3 new episodes · Harbor Lights', 'S2 E10 to S3 E2'])
  })
  it('reads a special by the same rule', () => {
    expect(groupNotice(group('Copperline', [[0, 3]])).body).toBe('S0 E3')
    expect(groupNotice(group('Copperline', [[4, 1], [0, 1]])).body).toBe('S4 E1 to S0 E1')
  })
  it('opens the show’s page at the earliest new episode, tagged by show', () => {
    const n = groupNotice(group('Night Ferry', [[1, 4], [1, 5], [1, 6]], { id: 12 }))
    expect(n.path).toBe('/shows/12/seasons/1/episodes/500')
    expect(n.key).toBe('new-episodes:12')
  })
  it('makes more than five shows five, then one more with no body that opens Home', () => {
    const groups = Array.from({ length: 8 }, (_, i) => group(`Show ${i}`, [[1, 1]], { id: i + 1 }))
    const notices = episodeNotices(groups)
    expect(notices.map((n) => n.title)).toEqual([
      'New episode · Show 0', 'New episode · Show 1', 'New episode · Show 2', 'New episode · Show 3', 'New episode · Show 4', '3 more shows have new episodes',
    ])
    expect(notices[5]).toMatchObject({ body: '', path: '/', key: 'new-episodes:more' })
    expect(episodeNotices(groups.slice(0, 6))[5].title).toBe('1 more show has new episodes')
    expect(episodeNotices(groups.slice(0, 5))).toHaveLength(5)
    expect(moreShowsNotice(2).title).toBe('2 more shows have new episodes')
  })
})

describe('the banner (banner-one, banner-more)', () => {
  it('splits the notification’s title into the heading and the show, with its body as the line', () => {
    const b = bannerFor([group('The Sleuth Files', [[3, 7, 'The Long Night']])], '1850')!
    expect([b.notice.heading, b.notice.show, b.notice.line]).toEqual(['New episode', 'The Sleuth Files', 'S3 E7 · The Long Night'])
    expect(b.notice.openLabel).toBe('Open The Sleuth Files, S3 E7')
    expect(b.more).toBe(0)
  })
  it('is one a check: the show with the newest arrival, and how many more', () => {
    const b = bannerFor([group('Night Ferry', [[1, 4], [1, 5], [1, 6]], { id: 1 }), group('A', [[1, 1]], { id: 2 }), group('B', [[1, 1]], { id: 3 })], '1850')!
    expect([b.notice.heading, b.notice.show, b.notice.line, b.notice.openLabel]).toEqual(['3 new episodes', 'Night Ferry', 'S1 E4 to E6', 'Open Night Ferry, S1 E4'])
    expect(b.more).toBe(2)
    expect(bannerFor([], '1850')).toBeUndefined()
  })
})

describe('what this browser keeps', () => {
  it('keeps a cursor per server and person, and forgets them and the banner at sign-out', () => {
    const a = personKey('srv-1', 1), b = personKey('srv-1', 2), c = personKey('srv-2', 1)
    saveCursor(a, '10')
    saveCursor(b, '20')
    saveCursor(c, '30')
    expect([loadCursor(a), loadCursor(b), loadCursor(c)]).toEqual(['10', '20', '30'])
    forgetCursor(b)
    expect(loadCursor(b)).toBeUndefined()
    saveBanner(a, bannerFor([group('The Sleuth Files', [[3, 7]])], '10')!)
    setNotifySwitch(a, false)
    forgetOnSignOut()
    expect([loadCursor(a), loadCursor(c), loadBanner(a)]).toEqual([undefined, undefined, undefined])
    // The switch is a choice, kept across sign-ins.
    expect(notifySwitch(a)).toBe(false)
  })
  it('keeps the banner until it is opened or dismissed', () => {
    const p = personKey('srv-1', 1)
    const banner = bannerFor([group('The Sleuth Files', [[3, 7]])], '10')!
    saveBanner(p, banner)
    expect(loadBanner(p)).toEqual(banner)
    expect(loadBanner(personKey('srv-1', 2))).toBeUndefined()
    clearBanner(p)
    expect(loadBanner(p)).toBeUndefined()
  })
  it('has the switch on for each person until it is turned off here', () => {
    const p = personKey('srv-1', 1)
    expect(notifySwitch(p)).toBe(true)
    setNotifySwitch(p, false)
    expect(notifySwitch(p)).toBe(false)
    expect(notifySwitch(personKey('srv-1', 2))).toBe(true)
    setNotifySwitch(p, true)
    expect(notifySwitch(p)).toBe(true)
  })
  it('explains before the browser first asks, once, apart from the reminders', () => {
    localStorage.setItem('wmm.liveReminders.asked', '1')
    expect(shouldExplainFollow('default')).toBe(true)
    expect(shouldExplainFollow('granted')).toBe(false)
    expect(shouldExplainFollow('denied')).toBe(false)
    expect(shouldExplainFollow('unsupported')).toBe(false)
    noteFollowAsked()
    expect(shouldExplainFollow('default')).toBe(false)
  })
})

describe('this browser in Settings › Notifications', () => {
  it('shows the switch, not asked yet, blocked, a plain http:// address or no notifications at all', () => {
    expect(browserState('granted', true, true)).toBe('on')
    expect(browserState('granted', false, true)).toBe('off')
    expect(browserState('default', true, true)).toBe('ask')
    expect(browserState('denied', true, true)).toBe('blocked')
    expect(browserState('unsupported', true, false)).toBe('http')
    expect(browserState('unsupported', true, true)).toBe('unsupported')
  })
})

describe('Settings › Notifications’ list (settings-shows)', () => {
  const day = 86_400_000
  const ago = (days: number) => new Date(Date.now() - days * day).toISOString()
  const show = { id: 7, title: 'The Sleuth Files' }
  it('gives the newest episode since following, and when', () => {
    expect(followLine({ show, followedAt: ago(10), latest: { season: 3, number: 7, arrivedAt: ago(2) } })).toEqual({ text: 'S3 E7 · 2 days ago', gone: false })
  })
  it('says no new episodes yet before any arrived since following', () => {
    expect(followLine({ show, followedAt: ago(10) })).toEqual({ text: 'No new episodes yet', gone: false })
    expect(followLine({ show, followedAt: ago(1), latest: { season: 3, number: 6, arrivedAt: ago(9) } })).toEqual({ text: 'No new episodes yet', gone: false })
  })
  it('says a show has left the library', () => {
    expect(followLine({ followedAt: ago(10), latest: { season: 3, number: 7, arrivedAt: ago(2) } })).toEqual({ text: 'Not in the library now', gone: true })
  })
  it('words a refused Stop notifying', () => {
    expect(stopFailLine('Copperline')).toBe('Couldn’t stop notifying, so you still follow Copperline. Check your connection and try again.')
  })
})
