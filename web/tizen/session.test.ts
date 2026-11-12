import { afterEach, describe, expect, it, vi } from 'vitest'
import { pair, readSession, restoreSession, saveSession } from './session'
import type { SavedSession } from './types'

const server = { id: 'fixture-1', name: 'Prototype fixture', version: 'test', apiLevel: 5, setupRequired: false, turnstile: false }
const pending = { code: 'ABC234', secret: 'private-polling-secret', linkPath: '/link?code=ABC234', intervalMs: 2000, expiresAt: '2099-01-01T00:00:00Z' }
const saved: SavedSession = { baseUrl: 'https://fixture.example', serverId: server.id, serverName: server.name, token: 'fixture-session', sessionId: 'fixture-public-id' }

afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers() })

describe('Samsung pairing', () => {
  it('keeps the claim secret out of the display callback and claims at the server interval', async () => {
    vi.useFakeTimers()
    const calls: Request[] = []
    vi.stubGlobal('fetch', async (req: Request) => {
      calls.push(req)
      if (req.url.endsWith('/api/server')) return Response.json(server)
      if (req.url.endsWith('/api/auth/pair')) return Response.json(pending)
      expect(await req.json()).toEqual({ secret: pending.secret })
      return Response.json({ status: 'approved', token: saved.token, sessionId: saved.sessionId })
    })
    const show = vi.fn()
    const pairing = pair(saved.baseUrl, { deviceId: 'test-device' }, new AbortController().signal, show)
    await vi.advanceTimersByTimeAsync(1999)
    expect(calls).toHaveLength(2)
    expect(show.mock.calls[0][0]).not.toHaveProperty('secret')
    await vi.advanceTimersByTimeAsync(1)
    expect((await pairing).saved).toEqual(saved)
    expect(calls).toHaveLength(3)
  })

  it('cancels polling without sending a claim after leaving pairing', async () => {
    vi.useFakeTimers()
    const fetcher = vi.fn(async (req: Request) => Response.json(req.url.endsWith('/api/server') ? server : pending))
    vi.stubGlobal('fetch', fetcher)
    const cancel = new AbortController()
    const pairing = pair(saved.baseUrl, { deviceId: 'test-device' }, cancel.signal, vi.fn()).catch((error: unknown) => error)
    await vi.advanceTimersByTimeAsync(100)
    cancel.abort()
    await vi.advanceTimersByTimeAsync(10_000)
    expect(await pairing).toBeInstanceOf(DOMException)
    expect(fetcher).toHaveBeenCalledTimes(2)
  })

  it('does not send a saved token to a different server at the remembered address', async () => {
    const fetcher = vi.fn(async () => Response.json({ ...server, id: 'replacement-server' }))
    vi.stubGlobal('fetch', fetcher)
    await expect(restoreSession(saved)).rejects.toThrow('different server')
    expect(fetcher).toHaveBeenCalledTimes(1)
    expect((fetcher.mock.calls[0] as unknown as [Request])[0].headers.has('Authorization')).toBe(false)
  })

  it('round-trips a saved session and treats corrupted storage as signed out', () => {
    const values = new Map<string, string>()
    const storage: Storage = {
      get length() { return values.size },
      getItem: (key) => values.get(key) ?? null,
      setItem: (key, value) => { values.set(key, value) },
      removeItem: (key) => { values.delete(key) },
      clear: () => values.clear(),
      key: (index) => [...values.keys()][index] ?? null,
    }
    saveSession(storage, saved)
    expect(readSession(storage)).toEqual(saved)
    storage.setItem('watchmymedia.tizen.prototype.session', '{broken')
    expect(readSession(storage)).toBeUndefined()
  })
})
