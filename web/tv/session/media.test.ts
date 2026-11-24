import { describe, expect, it, vi } from 'vitest'
import { MediaTokens, MediaUrls, REFRESH_BEFORE_MS, tokenedUrl } from './media'

const base = 'http://192.168.1.20:27587'

describe('media addresses', () => {
  it('adds a media token only to media routes on the selected server', () => {
    for (const path of ['/api/files/7/stream', '/api/playback/s1/master.m3u8', '/api/files/7/subtitles/2.vtt', '/api/images/w500/a.jpg',
      '/api/live-tv/art/12', '/api/files/7/trickplay/v3/0.jpg', '/api/live-tv/sessions/abc/hls/index.m3u8']) {
      expect(new URL(tokenedUrl(base, path, 'm1')!).searchParams.get('access_token'), path).toBe('m1')
    }
    for (const path of ['/api/auth/me', '/api/images/../auth/me', '/api/files/7', 'https://other.example/api/images/w500/a.jpg',
      '//other.example/api/images/w500/a.jpg', 'http://user:pw@192.168.1.20:27587/api/images/w92/a.jpg', 'javascript:alert(1)']) {
      expect(tokenedUrl(base, path, 'm1'), path).toBeUndefined()
    }
    expect(tokenedUrl(base, '/api/files/7/stream?play=abc#x', 'm1')).toBe(`${base}/api/files/7/stream?play=abc&access_token=m1`)
  })

  it('fetches a new token before the old one runs out, and retries a failed fetch', async () => {
    vi.useFakeTimers()
    try {
      const start = Date.now()
      let n = 0
      const request = vi.fn(async () => {
        n++
        if (n === 2) throw new Error('offline')
        return { token: `t${n}`, expiresAt: new Date(Date.now() + 60 * 60_000).toISOString() }
      })
      const tokens = new MediaTokens(request)
      const changed = vi.fn()
      tokens.subscribe(changed)
      await tokens.start()
      expect(tokens.current()?.token).toBe('t1')
      await vi.advanceTimersByTimeAsync(60 * 60_000 - REFRESH_BEFORE_MS - 1000)
      expect(request).toHaveBeenCalledTimes(1)
      await vi.advanceTimersByTimeAsync(1000)
      expect(request).toHaveBeenCalledTimes(2)
      expect(tokens.current()?.token).toBe('t1')
      await vi.advanceTimersByTimeAsync(30_000)
      expect(tokens.current()?.token).toBe('t3')
      expect(changed).toHaveBeenCalledTimes(2)
      expect(Date.now() - start).toBeLessThan(60 * 60_000)
      tokens.stop()
    } finally { vi.useRealTimers() }
  })

  it('keeps an address until shortly before its token runs out, so pictures are not fetched again', async () => {
    let now = 1_000_000
    let n = 0
    const tokens = new MediaTokens(async () => ({ token: `t${++n}`, expiresAt: new Date(now + 60 * 60_000).toISOString() }),
      { now: () => now, setTimeout: () => 0 as unknown as ReturnType<typeof setTimeout>, clearTimeout: () => {} })
    const urls = new MediaUrls(base, tokens, () => now, 2)
    expect(urls.imageUrl('/a.jpg', 'w342')).toBeUndefined()
    await tokens.start()
    const first = urls.imageUrl('/a.jpg', 'w342')
    expect(first).toBe(`${base}/api/images/w342/a.jpg?access_token=t1`)
    await tokens.check()
    now += 52 * 60_000
    await tokens.check()
    expect(tokens.current()?.token).toBe('t2')
    expect(urls.imageUrl('/a.jpg', 'w342')).toBe(first)
    now += 4 * 60_000
    expect(urls.imageUrl('/a.jpg', 'w342')).toContain('access_token=t2')
    expect(urls.mediaUrl('/api/auth/me')).toBeUndefined()
    urls.imageUrl('/b.jpg', 'w342'); urls.imageUrl('/c.jpg', 'w342')
    expect((urls as unknown as { cache: Map<string, unknown> }).cache.size).toBe(2)
  })
})
