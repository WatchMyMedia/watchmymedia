import { afterEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../src/api/client'
import { AppSession, REQUIRED_API_LEVEL } from './session'
import { MAX_SERVERS, ServerStore } from './servers'
import { accountOn, memoryStorage, sentTo, stubServers, type StubServer } from './testServers'

const living = (): StubServer => ({ origin: 'http://192.0.2.10:27587', id: 'srv-living', name: 'Living room server', token: 'token-living' })
const cabin = (): StubServer => ({ origin: 'http://192.0.2.31:27587', id: 'srv-cabin', name: 'Cabin server', token: 'token-cabin' })
const den = (): StubServer => ({ origin: 'http://192.0.2.52:27587', id: 'srv-den', name: 'Den server', token: 'token-den' })

/** A TV signed in to each of `servers`, the first used last, and open on it. */
async function signedInTo(servers: StubServer[], signedOut: StubServer[] = []) {
  const storage = memoryStorage()
  const store = new ServerStore(storage)
  const now = Date.now()
  servers.forEach((s, i) => store.remember({ id: s.id, name: s.name, baseUrl: s.origin, version: 'v1.8.0', account: accountOn(s) }, now - i * 60_000))
  signedOut.forEach((s, i) => store.remember({ id: s.id, name: s.name, baseUrl: s.origin, version: 'v1.8.0' }, now - (servers.length + i) * 60_000))
  const app = new AppSession(storage)
  app.start()
  await vi.waitFor(() => expect(app.snapshot().screen).toBe('signedIn'))
  return { app, storage, first: session(app) }
}

function session(app: AppSession) {
  const state = app.snapshot()
  if (state.screen !== 'signedIn') throw new Error(`on ${state.screen}, not signed in`)
  return state.session
}

afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers() })

describe('switching between the servers this TV has', () => {
  it('goes straight to a remembered server with its kept sign-in, and says which one', async () => {
    const a = living(), b = cabin()
    const { calls } = stubServers([a, b])
    const { app, storage, first } = await signedInTo([a, b])
    expect(first.server.id).toBe('srv-living')

    await app.switchTo('srv-cabin')
    const state = app.snapshot()
    expect(state).toMatchObject({ screen: 'signedIn', arrival: { how: 'switched' } })
    expect(session(app).server).toMatchObject({ id: 'srv-cabin', name: 'Cabin server' })
    expect(session(app).account.token).toBe('token-cabin')
    // The session before it ended here, with its cache; the new one has its own.
    expect(first.detached).toBe(true)
    expect(session(app).queryClient).not.toBe(first.queryClient)
    // Every query now goes to the new server, with its sign-in.
    await api.GET('/api/libraries')
    const libraries = calls[calls.length - 1]
    expect(libraries.url).toBe(`${b.origin}/api/libraries`)
    expect(libraries.headers.get('Authorization')).toBe('Bearer token-cabin')
    // It is the one used last now, so a launch opens on it.
    expect(new ServerStore(storage).last()?.id).toBe('srv-cabin')
    // It was asked which server it is before its sign-in went anywhere.
    const toCabin = calls.filter((r) => r.url.startsWith(b.origin))
    expect(new URL(toCabin[0].url).pathname).toBe('/api/server')
    expect(toCabin[0].headers.has('Authorization')).toBe(false)
    app.shutdown()
  })

  it('stays where it was when the server can’t be reached, and says so with Try again', async () => {
    const a = living(), b = { ...cabin(), down: true }
    const { calls } = stubServers([a, b])
    const { app, first } = await signedInTo([a, b])

    await app.switchTo('srv-cabin')
    expect(app.snapshot()).toMatchObject({ screen: 'signedIn', switching: { state: 'failed', id: 'srv-cabin', name: 'Cabin server', problem: 'unreachable' } })
    expect(session(app)).toBe(first)
    expect(first.detached).toBe(false)
    await api.GET('/api/libraries')
    expect(calls[calls.length - 1].url).toBe(`${a.origin}/api/libraries`)
    expect(sentTo(calls, 'token-cabin')).toEqual([])

    // Try again asks it again: still down, still here.
    app.retrySwitch()
    expect(app.snapshot()).toMatchObject({ switching: { state: 'switching', id: 'srv-cabin' } })
    await vi.waitFor(() => expect(app.snapshot()).toMatchObject({ switching: { state: 'failed' } }))
    expect(session(app)).toBe(first)

    // Back up: Try again switches.
    b.down = false
    app.retrySwitch()
    await vi.waitFor(() => expect(session(app).server.id).toBe('srv-cabin'))
    expect(first.detached).toBe(true)
    app.shutdown()
  })

  it('leaves a switch that is under way without changing anything', async () => {
    const a = living(), b = cabin()
    let answer: ((r: Response) => void) | undefined
    b.routes = { 'GET /api/server': () => new Promise<Response>((resolve) => { answer = resolve }) }
    stubServers([a, b])
    const { app, first } = await signedInTo([a, b])
    const switching = app.switchTo('srv-cabin')
    expect(app.snapshot()).toMatchObject({ switching: { state: 'switching' } })
    await vi.waitFor(() => expect(answer).toBeDefined())
    app.clearSwitch()
    expect(app.snapshot()).toMatchObject({ screen: 'signedIn' })
    expect((app.snapshot() as { switching?: unknown }).switching).toBeUndefined()
    answer?.(Response.json({ id: 'srv-cabin', name: 'Cabin server', version: 'v1.8.0', apiLevel: REQUIRED_API_LEVEL, setupRequired: false, turnstile: false }))
    await switching
    expect(session(app)).toBe(first)
    expect(first.detached).toBe(false)
    app.shutdown()
  })

  it('opens the sign-in of a server whose sign-in ended there, and Cancel comes back to the first', async () => {
    const a = living(), b = cabin()
    b.routes = { 'GET /api/auth/me': () => new Response(null, { status: 401 }) }
    stubServers([a, b])
    const { app, storage, first } = await signedInTo([a, b])

    await app.switchTo('srv-cabin')
    expect(app.snapshot()).toMatchObject({ screen: 'signIn', server: { id: 'srv-cabin', name: 'Cabin server' }, mode: 'code' })
    // It stays on this TV, signed out here; the first server's session is alive underneath.
    expect(new ServerStore(storage).get('srv-cabin')).toMatchObject({ name: 'Cabin server' })
    expect(new ServerStore(storage).get('srv-cabin')?.account).toBeUndefined()
    expect(first.detached).toBe(false)

    app.cancel()
    expect(app.snapshot()).toMatchObject({ screen: 'signedIn' })
    expect(session(app)).toBe(first)
    expect(first.detached).toBe(false)

    // Chosen again, it is the sign-in at once (no kept sign-in to check); a password signs in and switches.
    b.routes = {}
    await app.switchTo('srv-cabin')
    expect(app.snapshot()).toMatchObject({ screen: 'signIn' })
    app.usePassword()
    await app.signInWithPassword('Sam', 'a password')
    expect(app.snapshot()).toMatchObject({ screen: 'signedIn', arrival: { how: 'switched' } })
    expect(session(app).server.id).toBe('srv-cabin')
    expect(first.detached).toBe(true)
    app.shutdown()
  })

  it('never opens another server that answers at a remembered address, nor sends it the sign-in', async () => {
    const a = living(), b = cabin()
    b.routes = { 'GET /api/server': () => Response.json({ id: 'someone-else', name: 'Other', version: 'v1.8.0', apiLevel: REQUIRED_API_LEVEL, setupRequired: false, turnstile: false }) }
    const { calls } = stubServers([a, b])
    const { app, first } = await signedInTo([a, b])
    await app.switchTo('srv-cabin')
    expect(app.snapshot()).toMatchObject({ screen: 'signedIn', switching: { state: 'failed', problem: 'moved' } })
    expect(session(app)).toBe(first)
    expect(calls.filter((r) => r.url.startsWith(b.origin)).every((r) => !r.headers.has('Authorization'))).toBe(true)
    app.shutdown()
  })

  it('says a server is too old for this app, and remembers that it is', async () => {
    const a = living(), b = { ...cabin(), apiLevel: REQUIRED_API_LEVEL - 1 }
    const { calls } = stubServers([a, b])
    const { app, storage, first } = await signedInTo([a, b])
    await app.switchTo('srv-cabin')
    expect(app.snapshot()).toMatchObject({ screen: 'signedIn', switching: { state: 'failed', problem: 'tooOld' } })
    expect(session(app)).toBe(first)
    expect(new ServerStore(storage).get('srv-cabin')?.apiLevel).toBe(REQUIRED_API_LEVEL - 1)
    expect(sentTo(calls, 'token-cabin')).toEqual([])
    app.shutdown()
  })

  it('skips asking which server it is when it just answered a search here, and opens what was asked for', async () => {
    const a = living(), b = cabin()
    const { calls } = stubServers([a, b])
    const { app } = await signedInTo([a, b])
    const info = { id: 'srv-cabin', name: 'Cabin server', version: 'v1.8.0', apiLevel: REQUIRED_API_LEVEL, setupRequired: false, turnstile: false }
    const before = calls.length
    await app.switchTo('srv-cabin', { info, open: { text: 'harbor', target: { kind: 'movie', id: 7 } } })
    expect(app.snapshot()).toMatchObject({ screen: 'signedIn', arrival: { how: 'switched', open: { text: 'harbor', target: { kind: 'movie', id: 7 } } } })
    expect(calls.slice(before).some((r) => new URL(r.url).pathname === '/api/server')).toBe(false)
    // Its kept sign-in is still checked, at its own address.
    expect(calls.slice(before).find((r) => new URL(r.url).pathname === '/api/auth/me')?.url).toBe(`${b.origin}/api/auth/me`)
    app.shutdown()
  })

  it('sends each sign-in only to the server it belongs to, through every switch', async () => {
    const a = living(), b = cabin(), c = { ...den(), down: true }
    const { calls } = stubServers([a, b, c])
    const { app } = await signedInTo([a, b, c])
    await app.switchTo('srv-cabin')
    await app.switchTo('srv-den')
    await app.switchTo('srv-living')
    await api.GET('/api/libraries')
    await app.switchTo('srv-cabin')
    expect(sentTo(calls, 'token-living')).toEqual([a.origin])
    expect(sentTo(calls, 'token-cabin')).toEqual([b.origin])
    expect(sentTo(calls, 'token-den')).toEqual([])
    expect(calls.every((r) => r.credentials === 'omit')).toBe(true)
    app.shutdown()
  })
})

describe('adding a server from the signed-in app', () => {
  it('offers none of the servers this TV has, names them, and comes back on Return', async () => {
    const a = living(), b = cabin()
    stubServers([a, b])
    const { app, first } = await signedInTo([a], [b])
    app.addServer()
    expect(app.snapshot()).toMatchObject({ screen: 'servers', servers: [], address: '', adding: { registered: ['Living room server', 'Cabin server'] } })
    expect(first.detached).toBe(false)
    app.cancel()
    expect(session(app)).toBe(first)
    expect(first.detached).toBe(false)
    app.shutdown()
  })

  it('remembers the new server once it is signed in to, then opens on it and says it was added', async () => {
    const a = living(), c = den()
    stubServers([a, c])
    const { app, storage, first } = await signedInTo([a])
    app.addServer()
    app.editAddress('192.0.2.52')
    await app.connect()
    expect(app.snapshot()).toMatchObject({ screen: 'signIn', server: { id: 'srv-den' } })
    // Leaving its sign-in leaves nothing of it behind, and goes back to Add a server.
    expect(new ServerStore(storage).get('srv-den')).toBeUndefined()
    app.cancel()
    expect(app.snapshot()).toMatchObject({ screen: 'servers', servers: [], adding: { registered: ['Living room server'] } })
    expect(new ServerStore(storage).get('srv-den')).toBeUndefined()
    expect(first.detached).toBe(false)

    app.editAddress('192.0.2.52')
    await app.connect()
    app.usePassword()
    await app.signInWithPassword('Sam', 'a password')
    expect(app.snapshot()).toMatchObject({ screen: 'signedIn', arrival: { how: 'added' } })
    expect(session(app).server.id).toBe('srv-den')
    expect(first.detached).toBe(true)
    expect(new ServerStore(storage).list().map((s) => s.id)).toEqual(['srv-den', 'srv-living'])
    expect(new ServerStore(storage).get('srv-living')?.account?.token).toBe('token-living')
    app.shutdown()
  })

  it('switches to a server this TV already has when its address is typed, and stays put on its own', async () => {
    const a = living(), b = cabin()
    stubServers([a, b])
    const { app, first } = await signedInTo([a, b])
    app.addServer()
    app.editAddress('192.0.2.10')
    await app.connect()
    expect(session(app)).toBe(first)

    app.addServer()
    app.editAddress('192.0.2.31')
    await app.connect()
    expect(app.snapshot()).toMatchObject({ screen: 'signedIn', arrival: { how: 'switched' } })
    expect(session(app).server.id).toBe('srv-cabin')
    app.shutdown()
  })

  it('leaves the list as it was when the sign-in of a server this TV is signed out of is left', async () => {
    const a = living(), b = cabin()
    stubServers([a, b])
    const { app, storage, first } = await signedInTo([a], [b])
    app.addServer()
    app.editAddress('192.0.2.31')
    await app.connect()
    expect(app.snapshot()).toMatchObject({ screen: 'signIn', server: { id: 'srv-cabin' } })
    app.cancel()
    app.cancel()
    expect(session(app)).toBe(first)
    // The server the app is on is still the one used last, so a launch opens on it.
    expect(new ServerStore(storage).last()?.id).toBe('srv-living')
    app.shutdown()
  })

  it('keeps the app on its server when the address answers nothing or a server it can’t use', async () => {
    const a = living(), b = { ...cabin(), apiLevel: REQUIRED_API_LEVEL - 1 }
    stubServers([a, b])
    const { app, first } = await signedInTo([a])
    app.addServer()
    app.editAddress('192.0.2.99')
    await app.connect()
    expect(app.snapshot()).toMatchObject({ screen: 'servers', address: '192.0.2.99', adding: { registered: ['Living room server'] } })
    app.editAddress('192.0.2.31')
    await app.connect()
    expect(app.snapshot()).toMatchObject({ screen: 'problem', problem: 'tooOld' })
    // Change server goes back to Add a server; Return from there to the app.
    app.showServers()
    expect(app.snapshot()).toMatchObject({ screen: 'servers', adding: { registered: ['Living room server'] } })
    app.cancel()
    expect(session(app)).toBe(first)
    expect(first.detached).toBe(false)
    app.shutdown()
  })

  it(`keeps ${MAX_SERVERS} servers, and adds no more until one is removed`, async () => {
    const a = living()
    stubServers([a])
    const { app, storage } = await signedInTo([a])
    const store = new ServerStore(storage)
    for (let i = 1; i < MAX_SERVERS; i++) app.store.remember({ id: `srv-${i}`, name: `Server ${i}`, baseUrl: `http://192.0.2.${100 + i}:27587` }, Date.now() - i * 1000)
    expect(app.store.full).toBe(true)
    app.addServer()
    expect(app.snapshot().screen).toBe('signedIn')
    app.remove('srv-3')
    expect(store.list()).toHaveLength(MAX_SERVERS - 1)
    expect(app.store.full).toBe(false)
    app.addServer()
    expect(app.snapshot()).toMatchObject({ screen: 'servers', adding: {} })
    app.shutdown()
  })
})

describe('removing a server, and signing out', () => {
  it('signs this TV out of a server that isn’t the current one and forgets it', async () => {
    const a = living(), b = cabin()
    const { calls } = stubServers([a, b])
    const { app, storage, first } = await signedInTo([a, b])
    app.remove('srv-cabin')
    expect(new ServerStore(storage).get('srv-cabin')).toBeUndefined()
    expect(session(app)).toBe(first)
    expect(first.detached).toBe(false)
    await vi.waitFor(() => expect(calls.some((r) => r.url === `${b.origin}/api/auth/logout`)).toBe(true))
    expect(calls.find((r) => r.url === `${b.origin}/api/auth/logout`)?.headers.get('Authorization')).toBe('Bearer token-cabin')
    expect(sentTo(calls, 'token-cabin')).toEqual([b.origin])
    app.shutdown()
  })

  it('forgets a server that can’t be reached all the same', async () => {
    const a = living(), b = { ...cabin(), down: true }
    stubServers([a, b])
    const { app, storage } = await signedInTo([a, b])
    app.remove('srv-cabin')
    expect(new ServerStore(storage).list().map((s) => s.id)).toEqual(['srv-living'])
    app.shutdown()
  })

  it('moves to the most recently used server still signed in when the current one is removed', async () => {
    const a = living(), b = cabin(), c = den()
    const { calls } = stubServers([a, b, c])
    const { app, storage, first } = await signedInTo([a, c], [b])
    app.remove('srv-living')
    expect(first.detached).toBe(true)
    await vi.waitFor(() => expect(app.snapshot()).toMatchObject({ screen: 'signedIn', arrival: { how: 'switched' } }))
    expect(session(app).server.id).toBe('srv-den')
    expect(new ServerStore(storage).get('srv-living')).toBeUndefined()
    expect(calls.find((r) => r.url === `${a.origin}/api/auth/logout`)?.headers.get('Authorization')).toBe('Bearer token-living')
    app.shutdown()
  })

  it('shows the servers when the last one signed in is removed', async () => {
    const a = living(), b = cabin()
    stubServers([a, b])
    const { app } = await signedInTo([a], [b])
    app.remove('srv-living')
    expect(app.snapshot()).toMatchObject({ screen: 'servers', servers: [{ id: 'srv-cabin' }] })
    app.shutdown()
  })

  it('keeps a server signed out of on this TV, and moves to another it is signed in to', async () => {
    const a = living(), b = cabin()
    stubServers([a, b])
    const { app, storage } = await signedInTo([a, b])
    app.signOut()
    await vi.waitFor(() => expect(app.snapshot()).toMatchObject({ screen: 'signedIn', arrival: { how: 'switched' } }))
    expect(session(app).server.id).toBe('srv-cabin')
    expect(new ServerStore(storage).get('srv-living')).toMatchObject({ name: 'Living room server' })
    expect(new ServerStore(storage).get('srv-living')?.account).toBeUndefined()
    app.shutdown()
  })

  it('ends only the session a 401 belongs to', async () => {
    const a = living(), b = cabin()
    stubServers([a, b])
    const { app, first } = await signedInTo([a, b])
    await app.switchTo('srv-cabin')
    // The session left behind hears a 401 late: the app stays on Cabin.
    app.expire(first)
    expect(session(app).server.id).toBe('srv-cabin')
    b.routes = { 'GET /api/libraries': () => new Response(null, { status: 401 }) }
    await api.GET('/api/libraries')
    expect(app.snapshot()).toMatchObject({ screen: 'problem', problem: 'signedOut', server: { id: 'srv-cabin' } })
    app.shutdown()
  })
})
