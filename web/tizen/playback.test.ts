import { afterEach, describe, expect, it, vi } from 'vitest'
import { PlaybackController } from './playback'
import { tvClient } from './server'
import type { Player } from './avplay'
import type { Decision, MediaFile, ProgressBody } from './types'

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => { resolve = done })
  return { promise, resolve }
}

const file = { id: 1, relativePath: 'Signal Garden.mp4' } as MediaFile
const decision: Decision = {
  protocol: 'file', mode: 'direct', url: '/api/files/1/stream', playId: 'play-1',
  durationMs: 120_000, startMs: 0, resumeMs: 20_000, reasons: [],
  audio: { copy: true, codec: 'aac', index: 0 }, video: { copy: true, codec: 'h264', toneMap: false },
  audioTracks: [], segments: [], subtitles: [], creditsMs: 0, upNextMs: 0, watchedAtMs: 110_400,
}

function fixture(options: {
  decide?: (body: Record<string, unknown>) => Promise<Decision>
  progress?: (body: ProgressBody) => Promise<Response>
} = {}) {
  const requests: { path: string; method: string; body?: Record<string, unknown> }[] = []
  const fetcher: typeof fetch = vi.fn(async (input) => {
    const request = input as Request
    const path = new URL(request.url).pathname
    const body = request.method === 'POST' || request.method === 'PUT' ? await request.text() : ''
    const parsed = body ? JSON.parse(body) as Record<string, unknown> : undefined
    requests.push({ path, method: request.method, body: parsed })
    if (path.endsWith('/playback') && request.method === 'POST') return Response.json(await (options.decide?.(parsed!) ?? decision))
    if (path.endsWith('/media-token')) return Response.json({ token: 'media-only' })
    if (path.endsWith('/progress')) return options.progress?.(parsed as ProgressBody) ?? Response.json({ playId: 'play-1' })
    if (request.method === 'DELETE') return new Response(null, { status: 204 })
    throw new Error(`Unexpected fixture request ${path}`)
  })
  const player: Player = {
    name: 'Test player', open: vi.fn(async () => {}), stop: vi.fn(async () => {}),
    pause: vi.fn(async () => {}), resume: vi.fn(async () => {}), seek: vi.fn(async () => {}),
  }
  const controller = new PlaybackController(tvClient('https://fixture.example', 'session-only', fetcher), 'https://fixture.example', { deviceId: 'test' }, player)
  return { controller, player, requests }
}

afterEach(() => vi.useRealTimers())

describe('Samsung playback orchestration', () => {
  it('resumes with a media token and sends the final position on stop', async () => {
    const f = fixture()
    await f.controller.play(file, 'safe', 'vod')
    expect(f.player.open).toHaveBeenCalledWith('https://fixture.example/api/files/1/stream?access_token=media-only', 20_000, { uhd: false })
    f.controller.onPlayerUpdate({ positionMs: 31_000 })
    await f.controller.stop()
    const progress = f.requests.filter((r) => r.path.endsWith('/progress'))
    expect(progress.at(-1)?.body).toMatchObject({ state: 'stopped', positionMs: 31_000 })
    expect(f.controller.snapshot().decision).toBeUndefined()
  })

  it('does not reopen after Stop while waiting for a playback decision', async () => {
    const reply = deferred<Decision>()
    const f = fixture({ decide: () => reply.promise })
    const playing = f.controller.play(file, 'safe', 'vod')
    await vi.waitFor(() => expect(f.requests.some((r) => r.path.endsWith('/playback'))).toBe(true))
    await f.controller.stop()
    reply.resolve({ ...decision, protocol: 'hls', sessionId: 'abandoned', url: '/api/playback/abandoned/master.m3u8' })
    await playing
    expect(f.player.open).not.toHaveBeenCalled()
    expect(f.requests).toContainEqual({ path: '/api/playback/abandoned', method: 'DELETE', body: undefined })
    expect(f.controller.snapshot().state).toBe('idle')
  })

  it('keeps only the latest Play while prior player cleanup is pending', async () => {
    const f = fixture()
    const closing = deferred<void>()
    vi.mocked(f.player.stop).mockImplementationOnce(() => closing.promise)
    const older = f.controller.play(file, 'safe', 'vod')
    const newer = f.controller.play({ ...file, id: 2 }, 'safe', 'vod')
    await newer
    closing.resolve()
    await older
    expect(f.requests.filter((r) => r.path.endsWith('/playback')).map((r) => r.path)).toEqual(['/api/files/2/playback'])
    expect(f.controller.snapshot().file?.id).toBe(2)
    await f.controller.stop()
  })

  it('orders Stop after an in-flight heartbeat without restoring the old decision', async () => {
    const late = deferred<Response>()
    let reports = 0
    const f = fixture({ progress: async () => ++reports === 2 ? late.promise : Response.json({ playId: 'play-1' }) })
    await f.controller.play(file, 'safe', 'vod')
    f.controller.onPlayerUpdate({ state: 'paused', positionMs: 45_000 })
    await vi.waitFor(() => expect(reports).toBe(2))
    const stopping = f.controller.stop('closed')
    expect(reports).toBe(2)
    late.resolve(Response.json({ playId: 'obsolete' }))
    await stopping
    expect(f.requests.filter((r) => r.path.endsWith('/progress')).map((r) => r.body?.state)).toEqual(['playing', 'paused', 'closed'])
    expect(f.controller.snapshot().decision).toBeUndefined()
  })

  it('seeks an event playlist by replacing the same viewing and converts its timeline', async () => {
    const f = fixture({ decide: async (body) => ({ ...decision,
      protocol: 'hls', playlist: 'event', sessionId: 'event-test', url: '/api/playback/event-test/index.m3u8',
      startMs: Number(body.startMs ?? 0),
    }) })
    await f.controller.play(file, 'safe', 'event')
    expect(f.player.open).toHaveBeenLastCalledWith(expect.stringContaining('/api/playback/event-test/'), 0, { uhd: false })
    f.controller.onPlayerUpdate({ positionMs: 5000 })
    expect(f.controller.snapshot().positionMs).toBe(25_000)
    await f.controller.seek(10_000)
    const decisions = f.requests.filter((r) => r.path.endsWith('/playback'))
    expect(decisions.at(-1)?.body).toMatchObject({ startMs: 35_000, playId: 'play-1' })
    expect(f.player.seek).not.toHaveBeenCalled()
    expect(f.controller.snapshot().positionMs).toBe(35_000)
    await f.controller.stop()
  })

  it('honors administrator stop instructions', async () => {
    const f = fixture({ progress: async () => Response.json({ playId: 'play-1', directive: { action: 'stop' } }) })
    await f.controller.play(file, 'safe', 'vod')
    await vi.waitFor(() => expect(f.controller.snapshot().state).toBe('error'))
    expect(f.controller.snapshot().error).toBe('Playback was stopped by the server.')
    expect(f.controller.snapshot().decision).toBeUndefined()
    await f.controller.stop()
  })

  it('reports completion at the file duration even if the last time event was early', async () => {
    const f = fixture()
    await f.controller.play(file, 'safe', 'vod')
    f.controller.onPlayerUpdate({ positionMs: 60_000 })
    f.controller.onPlayerUpdate({ state: 'ended' })
    await vi.waitFor(() => expect(f.requests.at(-1)?.body).toMatchObject({ state: 'stopped', positionMs: 120_000 }))
    await f.controller.stop()
  })
})
