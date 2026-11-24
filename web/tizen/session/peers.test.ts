import { afterEach, describe, expect, it, vi } from 'vitest'
import { AppSession, REQUIRED_API_LEVEL } from './session'
import { ApiError } from '../../src/api/client'
import { leftOutReason, otherServers, PeerError, PeerServer, serversLeftOut } from './peers'
import { ServerStore } from './servers'
import { accountOn, memoryStorage, sentTo, stubServers, type StubServer } from './testServers'

const living = (): StubServer => ({ origin: 'http://192.0.2.10:27587', id: 'srv-living', name: 'Living room server', token: 'token-living' })
const cabin = (): StubServer => ({ origin: 'http://192.0.2.31:27587', id: 'srv-cabin', name: 'Cabin server', token: 'token-cabin',
  routes: { 'GET /api/search': (req) => req.headers.get('Authorization') === 'Bearer token-cabin'
    ? Response.json({ movies: [{ id: 7, title: 'Harbor Nights' }], shows: [], episodes: [] }) : undefined } })
const den = (): StubServer => ({ origin: 'http://192.0.2.52:27587', id: 'srv-den', name: 'Den server', token: 'token-den' })

const remembered = (s: StubServer) => ({ id: s.id, name: s.name, baseUrl: s.origin, version: 'v1.8.0', account: accountOn(s), lastUsedAt: 0 })
const never = new AbortController().signal

afterEach(() => { vi.unstubAllGlobals() })

describe('asking a server other than the current one', () => {
  it('asks which server it is first, then searches it with its own sign-in at its own address', async () => {
    const a = living(), b = cabin()
    const { calls } = stubServers([a, b])
    const noted = vi.fn()
    const peer = new PeerServer(remembered(b), { onInfo: noted })
    expect(peer.info).toBeUndefined()
    const results = await peer.search('harbor', never)
    expect(results.movies.map((m) => m.title)).toEqual(['Harbor Nights'])
    // Its media token is asked for once there is something of it to draw, a moment after the results.
    await vi.waitFor(() => expect(calls).toHaveLength(3))
    expect(calls.map((r) => `${r.method} ${r.url}`)).toEqual([`GET ${b.origin}/api/server`, `GET ${b.origin}/api/search?q=harbor`, `POST ${b.origin}/api/auth/media-token`])
    expect(calls[0].headers.has('Authorization')).toBe(false)
    expect(calls[1].headers.get('Authorization')).toBe('Bearer token-cabin')
    expect(calls.every((r) => r.credentials === 'omit')).toBe(true)
    expect(peer.info).toMatchObject({ id: 'srv-cabin' })
    expect(noted).toHaveBeenCalledWith(expect.objectContaining({ id: 'srv-cabin', apiLevel: REQUIRED_API_LEVEL }))
    // It is asked which server it is once; its pictures come from it with its own media token.
    await peer.search('harbo', never)
    expect(calls.filter((r) => r.url.endsWith('/api/server'))).toHaveLength(1)
    await vi.waitFor(() => expect(peer.media.imageUrl('/a.jpg', 'w342')).toBe(`${b.origin}/api/images/w342/a.jpg?access_token=media-srv-cabin`))
    expect(peer.media.mediaUrl(`${a.origin}/api/images/w342/a.jpg`)).toBeUndefined()
    peer.stop()
  })

  it('sends no sign-in when another server answers at its address, or one this app can’t use', async () => {
    const b = cabin()
    const { calls } = stubServers([b])
    b.routes = { 'GET /api/server': () => Response.json({ id: 'someone-else', name: 'Other', version: 'v1.8.0', apiLevel: REQUIRED_API_LEVEL, setupRequired: false, turnstile: false }) }
    const moved = new PeerServer(remembered(b))
    await expect(moved.search('harbor', never)).rejects.toMatchObject({ name: 'PeerError', reason: 'moved' })
    b.routes = {}
    b.apiLevel = REQUIRED_API_LEVEL - 1
    const old = new PeerServer(remembered(b))
    await expect(old.search('harbor', never)).rejects.toBeInstanceOf(PeerError)
    expect(calls.every((r) => !r.headers.has('Authorization'))).toBe(true)
    expect(moved.info).toBeUndefined()
  })

  it('tells a failure asking again won’t change from one it might', () => {
    expect(leftOutReason(new PeerError('tooOld'))).toBe('tooOld')
    expect(leftOutReason(new ApiError(new Response(null, { status: 401 }), undefined))).toBe('signedOut')
    expect(leftOutReason(new PeerError('moved'))).toBeUndefined()
    expect(leftOutReason(new ApiError(new Response(null, { status: 503 }), undefined))).toBeUndefined()
    expect(leftOutReason(new TypeError('Failed to fetch'))).toBeUndefined()
  })

  it('says when its sign-in ended there', async () => {
    const b = cabin()
    b.routes = { 'GET /api/search': () => new Response(null, { status: 401 }) }
    stubServers([b])
    const signedOut = vi.fn()
    const peer = new PeerServer(remembered(b), { onSignedOut: signedOut })
    await expect(peer.search('harbor', never)).rejects.toMatchObject({ status: 401 })
    expect(signedOut).toHaveBeenCalledTimes(1)
  })

  it('lists the other servers this TV is signed in to, in Servers’ order, and keeps each sign-in to its own address', async () => {
    const a = living(), b = cabin(), c = den(), d: StubServer = { origin: 'http://192.0.2.77:27587', id: 'srv-loft', name: 'Loft server', token: 'token-loft' }
    const { calls } = stubServers([a, b, c, d])
    const storage = memoryStorage()
    const store = new ServerStore(storage)
    const now = Date.now()
    store.remember(remembered(a), now)
    store.remember(remembered(c), now - 1000)
    store.remember({ id: d.id, name: d.name, baseUrl: d.origin }, now - 2000)
    store.remember(remembered(b), now - 3000)
    const app = new AppSession(storage)
    app.start()
    await vi.waitFor(() => expect(app.snapshot().screen).toBe('signedIn'))

    const peers = otherServers(app)
    // Not the current one, nor the one signed out here; the most recently used first.
    expect(peers.map((p) => p.name)).toEqual(['Den server', 'Cabin server'])
    expect(serversLeftOut(app)).toEqual([{ id: 'srv-loft', name: 'Loft server', reason: 'signedOut' }])
    await Promise.all(peers.map((p) => p.search('harbor', never)))
    expect(sentTo(calls, 'token-living')).toEqual([a.origin])
    expect(sentTo(calls, 'token-cabin')).toEqual([b.origin])
    expect(sentTo(calls, 'token-den')).toEqual([c.origin])
    expect(calls.some((r) => r.url.startsWith(d.origin))).toBe(false)
    // What a server says of itself is kept; a sign-in that ended there shows on Servers.
    expect(app.store.get('srv-cabin')?.apiLevel).toBe(REQUIRED_API_LEVEL)
    app.signedOutOf('srv-cabin')
    expect(app.store.get('srv-cabin')?.account).toBeUndefined()
    expect(otherServers(app).map((p) => p.name)).toEqual(['Den server'])
    // One that last said it is too old for this app isn't asked either, and says why.
    app.store.note('srv-den', { name: 'Den server', apiLevel: REQUIRED_API_LEVEL - 1 })
    expect(otherServers(app)).toEqual([])
    expect(serversLeftOut(app).map((l) => [l.name, l.reason])).toEqual([['Den server', 'tooOld'], ['Loft server', 'signedOut'], ['Cabin server', 'signedOut']])
    for (const p of peers) p.stop()
    app.shutdown()
  })
})

describe('every call to another server', () => {
  it('waits for the one answer to which server it is, whatever asks, and never signs in before it', async () => {
    const b = cabin()
    b.routes = { ...b.routes, 'GET /api/movies': (req) => req.headers.get('Authorization') === 'Bearer token-cabin' ? Response.json({ items: [] }) : undefined }
    const { calls } = stubServers([b])
    const peer = new PeerServer(remembered(b))
    const [one, two] = await Promise.all([
      peer.api.GET('/api/movies', { params: { query: { tmdbId: 1 } } }),
      peer.api.GET('/api/movies', { params: { query: { tmdbId: 2 } } }),
    ])
    expect(one.response.ok && two.response.ok).toBe(true)
    expect(calls.filter((r) => r.url.endsWith('/api/server'))).toHaveLength(1)
    expect(calls[0].url).toBe(`${b.origin}/api/server`)
    expect(calls.slice(1).every((r) => r.headers.get('Authorization') === 'Bearer token-cabin')).toBe(true)
  })

  it('sends no sign-in on any call when another server answers at the address', async () => {
    const b = cabin()
    b.routes = { 'GET /api/server': () => Response.json({ id: 'someone-else', name: 'Other', version: 'v1.8.0', apiLevel: REQUIRED_API_LEVEL, setupRequired: false, turnstile: false }) }
    const { calls } = stubServers([b])
    const peer = new PeerServer(remembered(b))
    await expect(peer.api.GET('/api/movies/{id}', { params: { path: { id: 3 } } })).rejects.toMatchObject({ reason: 'moved' })
    expect(calls.every((r) => !r.headers.has('Authorization'))).toBe(true)
  })

  it('knows a server beyond this network from its address', () => {
    expect(new PeerServer({ ...remembered(cabin()), baseUrl: 'https://cabin.example.org' }).away).toBe(true)
    expect(new PeerServer({ ...remembered(cabin()), baseUrl: 'http://192.168.1.31:27587' }).away).toBe(false)
    expect(new PeerServer({ ...remembered(cabin()), baseUrl: 'http://den.local:27587' }).away).toBe(false)
  })
})
