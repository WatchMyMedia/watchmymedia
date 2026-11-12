import { describe, expect, it } from 'vitest'
import { mediaUrl, result, safeError, serverUrl, tvClient } from './server'

describe('Samsung server transport', () => {
  it('requires an explicit root HTTP address without embedded credentials', () => {
    expect(serverUrl(' http://fixture.example:27686/ ')).toBe('http://fixture.example:27686')
    for (const bad of ['fixture.example', 'file:///tmp', 'https://name:password@fixture.example', 'https://fixture.example/api', 'https://fixture.example/?token=secret']) {
      expect(() => serverUrl(bad)).toThrow()
    }
  })

  it('attaches a scoped media token only to media on the selected server', () => {
    const url = new URL(mediaUrl('https://fixture.example', '/api/files/7/stream?play=abc', 'm1.fixture'))
    expect(url.searchParams.get('access_token')).toBe('m1.fixture')
    expect(url.searchParams.get('play')).toBe('abc')
    for (const bad of ['https://foreign.example/api/files/7/stream', '//foreign.example/api/playback/a/master.m3u8', '/api/auth/me', 'javascript:alert(1)']) {
      expect(() => mediaUrl('https://fixture.example', bad, 'm1.fixture')).toThrow()
    }
  })

  it('uses bearer headers and omits cookies without putting the session in the URL', async () => {
    let request: Request | undefined
    const fetcher: typeof fetch = async (input) => {
      request = input as Request
      return Response.json({ id: 'fixture' })
    }
    await tvClient('https://fixture.example', 'fixture-session', fetcher).GET('/api/server')
    expect(request?.credentials).toBe('omit')
    expect(request?.headers.get('Authorization')).toBe('Bearer fixture-session')
    expect(request?.url).toBe('https://fixture.example/api/server')
  })

  it('does not display remote errors or native exception URLs', () => {
    expect(() => result({ response: new Response('', { status: 403 }), error: { detail: 'secret-token' } })).toThrow('server refused')
    expect(safeError(new Error('https://fixture.example/?access_token=secret'))).not.toContain('secret')
  })
})
