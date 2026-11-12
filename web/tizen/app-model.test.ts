import { afterEach, describe, expect, it, vi } from 'vitest'
import { AppModel, PAGE_SIZE } from './app-model'
import type { Player } from './avplay'
import { readSession, saveSession } from './session'
import type { SavedSession } from './types'

const server = { id: 'fixture-server', name: 'Synthetic TV fixture', apiLevel: 5, setupRequired: false }
const saved: SavedSession = { baseUrl: 'https://fixture.example', serverId: server.id, serverName: server.name, token: 'fixture-bearer', sessionId: 'public-session-id' }
const file = { id: 1, relativePath: 'Signal Garden.mp4', durationMs: 60_000 }
function setup(overrides: { page?: (request: Request) => Promise<Response>; approved?: boolean } = {}) {
  const entries = new Map<string, string>()
  const storage: Storage = { get length() { return entries.size }, getItem: (k) => entries.get(k) ?? null,
    setItem: (k, v) => { entries.set(k, v) }, removeItem: (k) => { entries.delete(k) },
    clear: () => entries.clear(), key: (i) => [...entries.keys()][i] ?? null }
  const calls: Request[] = []
  vi.stubGlobal('fetch', async (request: Request) => {
    calls.push(request)
    const path = new URL(request.url).pathname
    if (path === '/api/server') return Response.json(server)
    if (path === '/api/auth/me') return Response.json({ user: { name: 'viewer' } })
    if (path === '/api/auth/pair') return Response.json({ code: 'ABC234', secret: 'private-claim-secret', linkPath: '/link?code=ABC234', intervalMs: 1000, expiresAt: new Date(Date.now() + 60_000).toISOString() })
    if (path === '/api/auth/pair/claim') return Response.json(overrides.approved === false ? { status: 'pending' } : { status: 'approved', token: saved.token, sessionId: saved.sessionId })
    if (path === '/api/libraries') return Response.json({ items: [{ id: 1, name: 'Movies' }] })
    if (path === '/api/libraries/1/files') return overrides.page?.(request) ?? Response.json({ items: [file], total: 1, offset: 0, limit: PAGE_SIZE })
    if (path === '/api/auth/logout') return new Response(null, { status: 204 })
    if (path === '/api/files/1/playback') return Response.json({ protocol: 'file', mode: 'direct', url: '/api/files/1/stream', playId: 'test-play', durationMs: 60_000, resumeMs: 0, startMs: 0,
      video: { copy: true, codec: 'h264', toneMap: false, width: 1280, height: 720 } })
    if (path === '/api/auth/media-token') return Response.json({ token: 'media-only' })
    if (path === '/api/files/1/progress') return Response.json({ playId: 'test-play' })
    throw new Error(`Unexpected fixture route ${path}`)
  })
  const player: Player = { name: 'Test only', open: vi.fn(async () => {}), stop: vi.fn(async () => {}), pause: vi.fn(async () => {}), resume: vi.fn(async () => {}), seek: vi.fn(async () => {}) }
  return { storage, calls, player, create: () => new AppModel(storage, player) }
}
afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals() })

describe('prototype screen and session lifetime', () => {
  it('keeps the address and a retry action when the server cannot issue a code', async () => {
    const f = setup(), model = f.create()
    vi.stubGlobal('fetch', async () => new Response(null, { status: 503 }))
    await model.connect(saved.baseUrl)
    expect(model.snapshot()).toMatchObject({ screen: 'server', address: saved.baseUrl, busy: false, focus: 'connect', pairing: undefined })
    expect(model.snapshot().error).toContain('Check that it’s running')
  })
  it('pairs into a bounded file picker without exposing credentials in UI state', async () => {
    vi.useFakeTimers()
    const f = setup(), model = f.create()
    const connecting = model.connect(saved.baseUrl)
    await vi.advanceTimersByTimeAsync(1000); await connecting
    expect(model.snapshot()).toMatchObject({ screen: 'library', busy: false, focus: 'file-1', total: 1 })
    expect(readSession(f.storage)).toEqual(saved)
    expect(JSON.stringify(model.snapshot())).not.toMatch(/fixture-bearer|private-claim-secret/)
    const page = f.calls.find((r) => r.url.includes('/files'))!
    expect(new URL(page.url).searchParams.get('limit')).toBe(String(PAGE_SIZE))
    await model.shutdown()
  })

  it('cancels code polling and leaves no approved session when returning to server entry', async () => {
    vi.useFakeTimers()
    const f = setup({ approved: false }), model = f.create()
    const connecting = model.connect(saved.baseUrl)
    await vi.advanceTimersByTimeAsync(100)
    model.cancelPair()
    await vi.advanceTimersByTimeAsync(2000); await connecting
    expect(model.snapshot()).toMatchObject({ screen: 'server', busy: false, pairing: undefined })
    expect(f.calls.some((r) => r.url.endsWith('/claim'))).toBe(false)
    expect(readSession(f.storage)).toBeUndefined()
  })

  it('keeps navigation available for empty libraries and does not replace a signed-out screen with a late page', async () => {
    let finish!: (response: Response) => void
    const f = setup({ page: () => new Promise((resolve) => { finish = resolve }) })
    saveSession(f.storage, saved)
    const model = f.create(), connecting = model.connect(saved.baseUrl)
    await vi.waitFor(() => expect(finish).toBeTypeOf('function'))
    await model.signOut()
    finish(Response.json({ items: [file], total: 1, offset: 0, limit: PAGE_SIZE }))
    await connecting
    expect(model.snapshot()).toMatchObject({ screen: 'server', files: [], busy: false, canRestore: false })
    expect(readSession(f.storage)).toBeUndefined()
  })

  it('turns an expired account into a clean pairing screen, including stale file data', async () => {
    const f = setup({ page: async () => new Response(null, { status: 401 }) })
    saveSession(f.storage, saved)
    const model = f.create()
    await model.connect(saved.baseUrl)
    expect(model.snapshot()).toMatchObject({ screen: 'server', files: [], canRestore: false, focus: 'connect' })
    expect(model.snapshot().error).toContain('signed out')
    expect(readSession(f.storage)).toBeUndefined()
  })

  it('keeps an empty library usable and remembers a sign-in through application exit', async () => {
    const f = setup({ page: async () => Response.json({ items: [], total: 0, offset: 0, limit: PAGE_SIZE }) })
    saveSession(f.storage, saved)
    const model = f.create()
    await model.connect(saved.baseUrl)
    expect(model.snapshot()).toMatchObject({ screen: 'library', files: [], busy: false, focus: 'refresh' })
    await model.shutdown()
    expect(readSession(f.storage)).toEqual(saved)
    expect(f.calls.some((r) => r.url.endsWith('/logout'))).toBe(false)
  })

  it('stops on suspension, offers a deliberate resume, and restores file focus on Return', async () => {
    const f = setup()
    saveSession(f.storage, saved)
    const model = f.create()
    await model.connect(saved.baseUrl)
    await model.play(model.snapshot().files[0])
    expect(model.snapshot().playback.state).toBe('playing')
    model.suspend()
    expect(model.snapshot()).toMatchObject({ screen: 'player', busy: false, focus: 'replay', playback: { state: 'idle' } })
    expect(model.snapshot().notice).toContain('Resume when')
    expect(f.player.open).toHaveBeenCalledTimes(1)
    model.stopToFiles()
    expect(model.snapshot()).toMatchObject({ screen: 'library', focus: 'file-1' })
    await model.shutdown()
    expect(model.snapshot().playback.state).toBe('idle')
    expect(readSession(f.storage)).toEqual(saved)
  })

  it('revokes a new grant if app storage fails instead of claiming that sign-in succeeded', async () => {
    vi.useFakeTimers()
    const f = setup(), model = f.create()
    const original = f.storage.setItem
    f.storage.setItem = (key, value) => { if (key.endsWith('.session')) throw new Error('disk unavailable'); original(key, value) }
    const connecting = model.connect(saved.baseUrl)
    await vi.advanceTimersByTimeAsync(1000); await connecting
    expect(model.snapshot().screen).toBe('pair')
    expect(model.snapshot().error).toContain('cannot save')
    expect(f.calls.some((r) => r.url.endsWith('/logout'))).toBe(true)
  })
})
