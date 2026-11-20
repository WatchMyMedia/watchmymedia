import { afterEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../src/api/client'
import { authScope } from '../../src/lib/authScope'
import { AppSession, REQUIRED_API_LEVEL, serverProblem } from './session'
import { ServerStore } from './servers'

const BASE = 'http://192.168.1.20:27587'
const info = { id: 'srv-1', name: 'Lakeside', version: 'v1.8.0', apiLevel: REQUIRED_API_LEVEL, setupRequired: false, turnstile: false }

function memoryStorage(): Storage {
  const m = new Map<string, string>()
  return { get length() { return m.size }, getItem: (k) => m.get(k) ?? null, setItem: (k, v) => { m.set(k, v) },
    removeItem: (k) => { m.delete(k) }, clear: () => m.clear(), key: (i) => [...m.keys()][i] ?? null }
}

type Route = (req: Request) => Response | Promise<Response> | undefined
function stubServer(overrides: Record<string, Route> = {}) {
  const calls: Request[] = []
  let claims = 0
  const routes: Record<string, Route> = {
    'GET /api/server': () => Response.json(info),
    'POST /api/auth/pair': () => Response.json({ code: `KX7P4${claims}`, secret: 'claim-secret', linkPath: '/link?code=KX7P4M', intervalMs: 1000, expiresAt: new Date(Date.now() + 600_000).toISOString() }),
    'POST /api/auth/pair/claim': () => ++claims === 1 ? new Response(null, { status: 404 }) : claims === 2 ? Response.json({ status: 'pending' })
      : Response.json({ status: 'approved', token: 'session-token', sessionId: 'sess-1', user: { id: 4, name: 'Sam', isAdmin: false } }),
    'GET /api/auth/me': () => Response.json({ id: 4, name: 'Sam', isAdmin: false }),
    'POST /api/auth/media-token': () => Response.json({ token: 'media', param: 'access_token', expiresAt: new Date(Date.now() + 3600_000).toISOString() }),
    'POST /api/auth/logout': () => new Response(null, { status: 204 }),
    'GET /api/events': () => new Response(new ReadableStream({ start() { /* Stays open. */ } })),
    'GET /api/libraries': () => Response.json({ items: [] }),
    ...overrides,
  }
  vi.stubGlobal('fetch', async (input: Request | string, init?: RequestInit) => {
    const req = typeof input === 'string' ? new Request(input, init) : input
    calls.push(req)
    const url = new URL(req.url)
    if (url.origin !== BASE) throw new TypeError('Failed to fetch')
    const reply = await routes[`${req.method} ${url.pathname}`]?.(req)
    if (!reply) throw new Error(`unexpected ${req.method} ${url.pathname}`)
    return reply
  })
  return { calls }
}

afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers() })

describe('the TV session', () => {
  it('words a server that is not set up or too old for this TV', () => {
    expect(serverProblem({ apiLevel: 40, setupRequired: true })).toBe('notSetUp')
    expect(serverProblem({ apiLevel: REQUIRED_API_LEVEL - 1, setupRequired: false })).toBe('tooOld')
    expect(serverProblem({ apiLevel: REQUIRED_API_LEVEL, setupRequired: false })).toBeUndefined()
  })

  it('pairs from a typed address, renewing a code that ran out, and makes the web queries use its bearer client', async () => {
    vi.useFakeTimers()
    const { calls } = stubServer()
    const storage = memoryStorage()
    const app = new AppSession(storage)
    app.start()
    expect(app.snapshot().screen).toBe('servers')
    app.editAddress('192.168.1.20')
    const scope = authScope()
    const connecting = app.connect()
    await vi.advanceTimersByTimeAsync(0)
    await connecting
    expect(app.snapshot()).toMatchObject({ screen: 'signIn', mode: 'code', pairing: { state: 'waiting', code: { code: 'KX7P40', linkUrl: `${BASE}/link?code=KX7P4M` } } })
    expect(JSON.stringify(app.snapshot())).not.toContain('claim-secret')
    await vi.advanceTimersByTimeAsync(1000)
    expect(app.snapshot()).toMatchObject({ screen: 'signIn', pairing: { state: 'waiting', code: { code: 'KX7P41' } } })
    await vi.advanceTimersByTimeAsync(2000)
    const state = app.snapshot()
    expect(state.screen).toBe('signedIn')
    if (state.screen !== 'signedIn') return
    expect(authScope()).toBeGreaterThan(scope)
    expect(new ServerStore(storage).get('srv-1')?.account).toMatchObject({ token: 'session-token', name: 'Sam' })
    await api.GET('/api/libraries')
    const libraries = calls.find((r) => r.url.endsWith('/api/libraries'))!
    expect(libraries.url).toBe(`${BASE}/api/libraries`)
    expect(libraries.headers.get('Authorization')).toBe('Bearer session-token')
    expect(libraries.credentials).toBe('omit')
    expect(calls.find((r) => r.url.endsWith('/api/events'))?.headers.get('Authorization')).toBe('Bearer session-token')
    expect(state.session.media.imageUrl('/a.jpg', 'w342')).toBe(`${BASE}/api/images/w342/a.jpg?access_token=media`)
    app.shutdown()
  })

  it('restores the last sign-in on launch, each with a cache of its own', async () => {
    stubServer()
    const storage = memoryStorage()
    new ServerStore(storage).remember({ id: 'srv-1', name: 'Lakeside', baseUrl: BASE, account: { userId: 4, name: 'Sam', isAdmin: false, token: 'session-token', sessionId: 'sess-1' } })
    const first = new AppSession(storage)
    first.start()
    await vi.waitFor(() => expect(first.snapshot().screen).toBe('signedIn'))
    const one = first.snapshot()
    first.switchServer()
    expect(first.snapshot().screen).toBe('servers')
    await first.choose('srv-1')
    const two = first.snapshot()
    expect(one.screen === 'signedIn' && two.screen === 'signedIn' && one.session.queryClient !== two.session.queryClient).toBe(true)
    first.shutdown()
  })

  it('sends a remembered token only to the server it belongs to', async () => {
    const { calls } = stubServer({ 'GET /api/server': () => Response.json({ ...info, id: 'someone-else' }) })
    const storage = memoryStorage()
    new ServerStore(storage).remember({ id: 'srv-1', name: 'Lakeside', baseUrl: BASE, account: { userId: 4, name: 'Sam', isAdmin: false, token: 'session-token', sessionId: 'sess-1' } })
    const app = new AppSession(storage)
    app.start()
    await vi.waitFor(() => expect(app.snapshot()).toMatchObject({ screen: 'problem', problem: 'moved' }))
    expect(calls.every((r) => !r.headers.has('Authorization'))).toBe(true)
  })

  it('shows "too old" below the level this TV needs', async () => {
    stubServer({ 'GET /api/server': () => Response.json({ ...info, apiLevel: REQUIRED_API_LEVEL - 1 }) })
    const app = new AppSession(memoryStorage())
    app.start()
    app.editAddress('http://192.168.1.20:27587')
    await app.connect()
    expect(app.snapshot()).toMatchObject({ screen: 'problem', problem: 'tooOld', server: { name: 'Lakeside', baseUrl: BASE } })
  })

  it('keeps a typed address that answers nothing, with the error, on the server screen', async () => {
    stubServer()
    const app = new AppSession(memoryStorage())
    app.start()
    app.editAddress('10.0.0.9')
    await app.connect()
    expect(app.snapshot()).toMatchObject({ screen: 'servers', address: '10.0.0.9', busy: undefined })
    expect((app.snapshot() as { error?: unknown }).error).toBeInstanceOf(TypeError)
  })

  it('detaches on a 401 anywhere and says it was signed out from another device', async () => {
    stubServer({ 'GET /api/libraries': () => new Response(null, { status: 401 }) })
    const storage = memoryStorage()
    new ServerStore(storage).remember({ id: 'srv-1', name: 'Lakeside', baseUrl: BASE, account: { userId: 4, name: 'Sam', isAdmin: false, token: 'session-token', sessionId: 'sess-1' } })
    const app = new AppSession(storage)
    app.start()
    await vi.waitFor(() => expect(app.snapshot().screen).toBe('signedIn'))
    const signedIn = app.snapshot()
    await api.GET('/api/libraries')
    expect(app.snapshot()).toMatchObject({ screen: 'problem', problem: 'signedOut', server: { id: 'srv-1' } })
    expect(signedIn.screen === 'signedIn' && signedIn.session.detached).toBe(true)
    expect(new ServerStore(storage).get('srv-1')?.account).toBeUndefined()
    app.retry()
    await vi.waitFor(() => expect(app.snapshot()).toMatchObject({ screen: 'signIn', mode: 'code' }))
    app.shutdown()
  })

  it('signs out on the server and shows a code to link this TV again', async () => {
    const { calls } = stubServer()
    const storage = memoryStorage()
    new ServerStore(storage).remember({ id: 'srv-1', name: 'Lakeside', baseUrl: BASE, account: { userId: 4, name: 'Sam', isAdmin: false, token: 'session-token', sessionId: 'sess-1' } })
    const app = new AppSession(storage)
    app.start()
    await vi.waitFor(() => expect(app.snapshot().screen).toBe('signedIn'))
    app.signOut()
    expect(app.snapshot()).toMatchObject({ screen: 'signIn', mode: 'code' })
    await vi.waitFor(() => expect(calls.some((r) => r.url.endsWith('/api/auth/logout'))).toBe(true))
    expect(calls.find((r) => r.url.endsWith('/api/auth/logout'))!.headers.get('Authorization')).toBe('Bearer session-token')
    expect(new ServerStore(storage).get('srv-1')).toMatchObject({ name: 'Lakeside' })
    expect(new ServerStore(storage).get('srv-1')?.account).toBeUndefined()
    app.shutdown()
  })

  it('stops showing codes while the TV is away, until a new one is asked for', async () => {
    vi.useFakeTimers()
    stubServer()
    const app = new AppSession(memoryStorage())
    app.start()
    app.editAddress('192.168.1.20')
    await app.connect()
    await vi.advanceTimersByTimeAsync(0)
    app.suspend()
    expect(app.snapshot()).toMatchObject({ screen: 'signIn', pairing: { state: 'expired' } })
    await vi.advanceTimersByTimeAsync(5000)
    expect(app.snapshot()).toMatchObject({ pairing: { state: 'expired' } })
    app.newCode()
    await vi.advanceTimersByTimeAsync(0)
    expect(app.snapshot()).toMatchObject({ pairing: { state: 'waiting' } })
    app.shutdown()
  })
})
