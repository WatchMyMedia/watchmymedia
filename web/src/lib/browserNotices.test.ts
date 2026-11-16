import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

// The browser glue for reminders, with stand-ins for storage, Web Locks and
// the Notification API (Vitest runs without a browser).

function memoryStorage() {
  const m = new Map<string, string>()
  return { getItem: (k: string) => m.get(k) ?? null, setItem: (k: string, v: string) => void m.set(k, v), removeItem: (k: string) => void m.delete(k) }
}

beforeEach(() => {
  vi.resetModules()
  vi.stubGlobal('localStorage', memoryStorage())
})
afterEach(() => { vi.unstubAllGlobals() })

describe('notices', () => {
  it('shows each notice once, across tabs sharing storage', async () => {
    const n = await import('./browserNotices')
    expect(n.claimNotice('r1@2026-11-03T02:00:00Z')).toBe(true)
    expect(n.claimNotice('r1@2026-11-03T02:00:00Z')).toBe(false)
    expect(n.claimNotice('r1@2026-11-03T02:30:00Z')).toBe(true)
  })
  it('remembers dismissed banners', async () => {
    const n = await import('./browserNotices')
    expect(n.dismissedNotices().has('a')).toBe(false)
    n.dismissNotice('a')
    expect(n.dismissedNotices().has('a')).toBe(true)
  })
})

describe('one tab notifies', () => {
  it('holds the lock until the tab goes, then the next tab gets it', async () => {
    const queue: (() => Promise<void>)[] = []
    let holding: Promise<void> | undefined
    const locks = {
      request: (_name: string, _o: { signal: AbortSignal }, fn: () => Promise<void>) => {
        const run = async () => { holding = fn(); await holding; holding = undefined; const next = queue.shift(); if (next) void next() }
        if (holding) queue.push(run); else void run()
        return Promise.resolve()
      },
    }
    vi.stubGlobal('navigator', { locks })
    const n = await import('./browserNotices')
    const first = vi.fn(), second = vi.fn()
    const leaveFirst = n.holdNotifierLock(first)
    n.holdNotifierLock(second)
    await Promise.resolve()
    expect(first).toHaveBeenCalledOnce()
    expect(second).not.toHaveBeenCalled()
    leaveFirst()
    await new Promise((r) => setTimeout(r, 0))
    expect(second).toHaveBeenCalledOnce()
  })
  it('lets every tab notify where Web Locks are missing: tags keep one notification each', async () => {
    vi.stubGlobal('navigator', {})
    const n = await import('./browserNotices')
    const held = vi.fn()
    n.holdNotifierLock(held)
    expect(held).toHaveBeenCalledOnce()
  })
})

describe('permission', () => {
  it('explains once, before the browser first asks', async () => {
    vi.stubGlobal('window', { isSecureContext: true, Notification: {} })
    vi.stubGlobal('Notification', { permission: 'default', requestPermission: vi.fn(async () => 'denied') })
    vi.stubGlobal('navigator', { serviceWorker: {} })
    const n = await import('./browserNotices')
    expect(n.shouldExplain()).toBe(true)
    expect(await n.askToNotify()).toBe('denied')
    expect(n.shouldExplain()).toBe(false)
  })
  it('never asks without a secure context: the banner still comes', async () => {
    vi.stubGlobal('window', { isSecureContext: false, Notification: {} })
    vi.stubGlobal('Notification', { permission: 'default' })
    vi.stubGlobal('navigator', { serviceWorker: {} })
    const n = await import('./browserNotices')
    expect(n.notifyPermission()).toBe('unsupported')
    expect(n.shouldExplain()).toBe(false)
    expect(await n.showReminder({ title: 't', body: 'b', tag: 'k' })).toBe(false)
  })
})

describe('a notification chosen in an open tab', () => {
  function worker() {
    const listeners = new Set<(e: MessageEvent) => void>()
    vi.stubGlobal('window', { isSecureContext: true, Notification: {} })
    vi.stubGlobal('Notification', { permission: 'granted' })
    vi.stubGlobal('navigator', {
      serviceWorker: {
        addEventListener: (_: string, fn: (e: MessageEvent) => void) => listeners.add(fn),
        removeEventListener: (_: string, fn: (e: MessageEvent) => void) => listeners.delete(fn),
      },
    })
    return (data: unknown) => listeners.forEach((fn) => fn({ data } as MessageEvent))
  }
  it('opens a channel, a show’s page and Home, and nothing else', async () => {
    const post = worker()
    const n = await import('./browserNotices')
    const open = vi.fn()
    const stop = n.onReminderOpened(open)
    post({ type: 'wmm.open', path: '/live-tv/watch/c1' })
    post({ type: 'wmm.open', path: '/shows/12/seasons/3/episodes/456' })
    post({ type: 'wmm.open', path: '/' })
    post({ type: 'wmm.open', path: '/settings/account' })
    post({ type: 'wmm.open', path: 'https://example.com/shows/1' })
    post({ type: 'other', path: '/shows/1' })
    expect(open.mock.calls.map((c) => c[0])).toEqual(['/live-tv/watch/c1', '/shows/12/seasons/3/episodes/456', '/'])
    stop()
    post({ type: 'wmm.open', path: '/shows/1' })
    expect(open).toHaveBeenCalledTimes(3)
  })
})

describe('locks', () => {
  it('new episodes hold their own lock, so one tab can hold both', async () => {
    const names: string[] = []
    vi.stubGlobal('navigator', { locks: { request: (name: string, _o: unknown, fn: () => Promise<void>) => { names.push(name); void fn(); return Promise.resolve() } } })
    const n = await import('./browserNotices')
    const reminders = vi.fn(), episodes = vi.fn()
    n.holdNotifierLock(reminders)
    n.holdNotifierLock(episodes, n.NEW_EPISODES_LOCK)
    expect(names).toEqual(['watchmymedia.live-reminders', 'watchmymedia.new-episodes'])
    expect(reminders).toHaveBeenCalledOnce()
    expect(episodes).toHaveBeenCalledOnce()
  })
})
