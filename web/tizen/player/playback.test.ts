import createClient from 'openapi-fetch'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { paths } from '../../src/api/schema'
import { StillWatchingSession } from '../../src/lib/stillWatching'
import type { Decision, Progress, SubtitleSearch, SubtitleTrack } from './api'
import type { Clock } from './clock'
import { FakePlayer } from './fake-player'
import { DEFAULT_SETTINGS, FilePlayback, playbackSettings, type FileRequest, type PlaybackSettings } from './playback'

const HOUR = 3_600_000
type Sent = { method: string; path: string; body?: Record<string, unknown> }
type Route = (body: Record<string, unknown> | undefined, path: string) => unknown

function decision(patch: Partial<Decision> = {}): Decision {
  return {
    protocol: 'file', mode: 'direct', url: '/api/files/1/stream', playId: 'aaaaaaaaaaaaaaaa', durationMs: 600_000, startMs: 0, resumeMs: 0, reasons: [],
    audio: { copy: true, index: 1, codec: 'aac', channels: 2 }, video: { copy: true, codec: 'h264', toneMap: false, width: 1920, height: 1080 },
    audioTracks: [{ index: 1, codec: 'aac', channels: 2, default: true, language: 'en' }, { index: 2, codec: 'ac3', channels: 6, default: false, language: 'fr' }],
    segments: [], subtitles: [], creditsMs: 0, upNextMs: 0, watchedAtMs: 552_000,
    mediaToken: { token: 'media-1', param: 'access_token', expiresAt: new Date(Date.now() + 4 * HOUR).toISOString() },
    ...patch,
  }
}

const hls = (patch: Partial<Decision> = {}) => decision({ protocol: 'hls', mode: 'remux', playlist: 'vod', sessionId: 's1', url: '/api/playback/s1/master.m3u8', ...patch })
const vtt = (track: string) => `WEBVTT\n\n00:00:10.000 --> 00:00:12.000\n${track} one\n\n00:00:12.100 --> 00:00:15.000\n${track} two\n\n00:01:40.000 --> 00:01:45.000\n${track} later`
const textTrack: SubtitleTrack = { id: 's3', codec: 'subrip', format: 'webvtt', burnInOnly: false, default: false, external: false, forced: false, language: 'en', url: '/api/files/1/subtitles/s3.vtt' }
const imageTrack: SubtitleTrack = { id: 's4', codec: 'hdmv_pgs_subtitle', format: 'image', burnInOnly: true, default: false, external: false, forced: false, language: 'en' }

function harness(o: { decide?: Route; progress?: Route; settings?: Partial<PlaybackSettings>; routes?: Record<string, Route>; fetchText?: (url: string) => Promise<string> } = {}) {
  vi.useFakeTimers({ now: 10 * HOUR })
  const sent: Sent[] = []
  const fetcher = async (request: Request) => {
    const path = new URL(request.url).pathname
    const text = request.method === 'POST' || request.method === 'PUT' ? await request.text() : ''
    const body = text ? JSON.parse(text) as Record<string, unknown> : undefined
    sent.push({ method: request.method, path, body })
    const route = (reply: unknown) => reply instanceof Response ? reply : Response.json(reply)
    const custom = o.routes?.[`${request.method} ${path}`]
    if (custom) return route(await custom(body, path))
    if (path.endsWith('/playback') && request.method === 'POST') return route(await (o.decide?.(body, path) ?? decision()))
    if (path.endsWith('/progress')) return route(await (o.progress?.(body, path) ?? { completed: false, positionMs: 0, durationMs: 0, updatedAt: '' } satisfies Progress))
    if (path === '/api/auth/media-token') return Response.json({ token: `renewed-${sent.length}`, param: 'access_token', expiresAt: new Date(Date.now() + 4 * HOUR).toISOString() })
    if (request.method === 'DELETE') return new Response(null, { status: 204 })
    if (path.endsWith('/subtitles/search')) return Response.json({ available: false, canDownload: false, languages: [], provider: 'opensubtitles', attribution: '', quota: { remaining: 0, signedIn: false }, results: [] } satisfies SubtitleSearch)
    if (path.endsWith('/subtitles/automatic')) return Response.json({})
    throw new Error(`Unexpected ${request.method} ${path}`)
  }
  const api = createClient<paths>({ baseUrl: 'https://tv.example', fetch: fetcher as typeof fetch })
  const player = new FakePlayer()
  let stillNow = 0
  const still = new StillWatchingSession(() => stillNow)
  const clock: Clock = { now: () => Date.now(), wall: () => Date.now(), setTimeout: (f, ms) => setTimeout(f, ms), clearTimeout: (h) => clearTimeout(h as never), setInterval: (f, ms) => setInterval(f, ms), clearInterval: (h) => clearInterval(h as never) }
  const settings: PlaybackSettings = { ...DEFAULT_SETTINGS, ...o.settings }
  const saved: string[] = []
  const images: string[] = []
  const c = new FilePlayback({
    api, player, client: { deviceId: 'tv-1', platform: 'tizen' }, settings: () => settings, stillWatching: still, clock,
    mediaUrl: (path, token) => `https://tv.example${path}${path.includes('?') ? '&' : '?'}access_token=${token}`,
    saveQuality: (id) => saved.push(id), fetchText: o.fetchText ?? (async () => vtt('cue')), loadImage: async (url) => { images.push(url) },
  })
  const of = (method: string, suffix: string) => sent.filter((r) => r.method === method && r.path.endsWith(suffix))
  return {
    c, player, sent, settings, saved, images, still,
    setStill: (ms: number) => { stillNow = ms },
    decisions: () => of('POST', '/playback'),
    beats: () => of('PUT', '/progress'),
    deletes: () => sent.filter((r) => r.method === 'DELETE').map((r) => r.path),
    /** Plays on for ms, the player's time moving with the clock. */
    async run(ms: number) {
      for (let t = 0; t < ms; t += 250) { player.advance(Math.min(250, ms - t)); await vi.advanceTimersByTimeAsync(Math.min(250, ms - t)) }
    },
    settle: () => vi.advanceTimersByTimeAsync(0),
  }
}

async function started(h: ReturnType<typeof harness>, request: FileRequest = { kind: 'file', fileId: 1 }) {
  await h.c.play(request)
  await h.settle()
}

afterEach(() => vi.useRealTimers())

describe('starting and stopping', () => {
  it('plays with the decision’s media token where the server resumes, and reports the last position on stop', async () => {
    const h = harness({ decide: () => decision({ resumeMs: 120_000 }) })
    await started(h)
    expect(h.sent.some((r) => r.path === '/api/auth/media-token')).toBe(false)
    expect(h.player.opened[0]).toEqual({ url: 'https://tv.example/api/files/1/stream?access_token=media-1', options: { startMs: 120_000, uhd: false, paused: false } })
    expect(h.decisions()[0].body).toMatchObject({ quality: 'auto', playlist: 'vod', subtitleMode: 'auto', client: { deviceId: 'tv-1' }, profile: { name: 'samsung-tv' } })
    expect(h.decisions()[0].body).not.toHaveProperty('startMs')
    await h.run(5000)
    expect(h.c.snapshot()).toMatchObject({ phase: 'playing', positionMs: 125_000, stream: 'Auto · Direct play · 1080p · AAC Stereo', mode: 'direct' })
    await h.c.stop()
    expect(h.beats().at(-1)?.body).toMatchObject({ state: 'stopped', positionMs: 125_000, durationMs: 600_000, playId: 'aaaaaaaaaaaaaaaa' })
    expect(h.c.snapshot()).toMatchObject({ phase: 'idle', exited: true })
    expect(h.player.calls.at(-1)).toBe('stop')
  })

  it('asks for an event session again where it resumes when the server started it elsewhere, and ends the first', async () => {
    const h = harness({ decide: (body) => body?.startMs === undefined
      ? hls({ playlist: 'event', sessionId: 'first', startMs: 0, resumeMs: 300_000 })
      : hls({ playlist: 'event', sessionId: 'second', startMs: Number(body.startMs) - 2000, url: '/api/playback/second/index.m3u8' }) })
    await started(h)
    expect(h.decisions().map((r) => r.body?.startMs)).toEqual([undefined, 300_000])
    expect(h.decisions()[1].body?.playId).toBe('aaaaaaaaaaaaaaaa')
    expect(h.deletes()).toEqual(['/api/playback/first'])
    // The session starts on the keyframe 2 s before: the player starts 2 s into it.
    expect(h.player.opened[0].options.startMs).toBe(2000)
    await h.run(1000)
    expect(h.c.snapshot().positionMs).toBe(301_000)
  })

  it('does not open after Stop while waiting for a decision, and releases its session', async () => {
    let answer!: (d: Decision) => void
    const h = harness({ decide: () => new Promise<Decision>((resolve) => { answer = resolve }) })
    const playing = h.c.play({ kind: 'file', fileId: 1 })
    await h.settle()
    await h.c.stop()
    answer(hls({ sessionId: 'abandoned' }))
    await playing; await h.settle()
    expect(h.player.opened).toEqual([])
    expect(h.deletes()).toEqual(['/api/playback/abandoned'])
    expect(h.c.snapshot().phase).toBe('idle')
  })

  it('keeps only the latest play', async () => {
    const h = harness({ decide: (_b, path) => decision({ url: path.replace('playback', 'stream') }) })
    const older = h.c.play({ kind: 'file', fileId: 1 })
    const newer = h.c.play({ kind: 'file', fileId: 2 })
    await Promise.all([older, newer]); await h.settle()
    expect(h.player.opened.map((o) => o.url)).toEqual(['https://tv.example/api/files/2/stream?access_token=media-1'])
    expect(h.c.snapshot().request).toEqual({ kind: 'file', fileId: 2 })
  })

  it('starts a trailer at its start and leaves at its end', async () => {
    const h = harness({ decide: () => decision({ resumeMs: 90_000, durationMs: 60_000, nextEpisode: undefined }) })
    await started(h, { kind: 'trailer', fileId: 7, title: 'A Movie · Trailer' })
    expect(h.decisions()[0]).toMatchObject({ path: '/api/files/7/playback', body: { startMs: 0 } })
    expect(h.player.opened[0].options.startMs).toBe(0)
    h.player.emit({ state: 'ended' })
    await h.settle()
    expect(h.c.snapshot()).toMatchObject({ phase: 'ended', exited: true })
    expect(h.beats().at(-1)?.body).toMatchObject({ state: 'stopped', positionMs: 60_000 })
  })
})

describe('heartbeats and directives', () => {
  it('beats every 5 s at Automatic while playing, at once on a change, and every 30 s paused', async () => {
    const h = harness()
    await started(h)
    expect(h.beats().map((b) => b.body?.state)).toEqual(['buffering', 'playing'])
    await h.run(20_000)
    expect(h.beats().length).toBe(2 + 4)
    h.c.pause(); await h.settle()
    expect(h.beats().at(-1)?.body?.state).toBe('paused')
    const before = h.beats().length
    await h.run(29_000)
    expect(h.beats().length).toBe(before)
    await h.run(1_500)
    expect(h.beats().length).toBe(before + 1)
  })

  it('beats every 10 s while playing at a fixed quality, and soon after a seek lands', async () => {
    const h = harness({ settings: { quality: 'original' } })
    await started(h)
    await h.run(20_000)
    expect(h.beats().length).toBe(2 + 2)
    h.c.seekTo(300_000)
    await h.run(1_250)
    expect(h.beats().length).toBe(5)
    expect(h.beats().at(-1)?.body?.positionMs).toBeGreaterThanOrEqual(300_000)
  })

  it('counts rebuffers after the first frame, and reports startup once', async () => {
    const h = harness()
    await started(h)
    await h.run(2000)
    h.player.emit({ state: 'buffering' }); await h.run(1000)
    h.player.emit({ state: 'playing' }); await h.run(5000)
    const bodies = h.beats().map((b) => b.body!)
    expect(bodies.filter((b) => b.startupMs !== undefined)).toHaveLength(1)
    expect(bodies.at(-1)).toMatchObject({ stalls: 1, stallMs: 1000 })
  })

  it('stops for an admin, with their words, and reports nothing more', async () => {
    let calls = 0
    const h = harness({ progress: () => ++calls === 3 ? { completed: false, positionMs: 0, durationMs: 0, updatedAt: '', directive: { action: 'stop', message: 'Maintenance' } } : { completed: false, positionMs: 0, durationMs: 0, updatedAt: '' } })
    await started(h)
    await h.run(5000)
    expect(h.c.snapshot().adminStop).toEqual({ message: 'Maintenance' })
    expect(h.player.calls.at(-1)).toBe('stop')
    const count = h.beats().length
    await h.run(30_000)
    expect(h.beats().length).toBe(count)
    await h.c.stop()
    expect(h.beats().length).toBe(count)
  })

  it('takes an Automatic step down in the same play at the same position, without changing the saved default', async () => {
    const steps: ('original' | '720')[] = ['original', '720']
    const h = harness({
      decide: () => { const step = steps.shift() ?? '720'; return hls({ auto: { step, reason: '' }, sessionId: `s-${step}` }) },
      progress: (body) => ({ completed: false, positionMs: 0, durationMs: 0, updatedAt: '', directive: Number(body?.positionMs) >= 5000 ? { action: 'quality', quality: '720' } : undefined }),
    })
    await started(h)
    await h.run(5250)
    expect(h.decisions()).toHaveLength(2)
    expect(h.decisions()[1].body).toMatchObject({ playId: 'aaaaaaaaaaaaaaaa', quality: 'auto', startMs: 5000 })
    expect(h.deletes()).toEqual(['/api/playback/s-original'])
    expect(h.c.snapshot()).toMatchObject({ stream: 'Auto · 720p', note: 'Lowered to 720p for your connection.', quality: { id: 'auto', autoStep: '720' } })
    expect(h.saved).toEqual([])
  })

  it('carries on under the new play ID the server gives', async () => {
    const h = harness({ progress: () => ({ completed: false, positionMs: 0, durationMs: 0, updatedAt: '', playId: 'bbbbbbbbbbbbbbbb' }) })
    await started(h)
    await h.run(6000)
    expect(h.beats().at(-1)?.body?.playId).toBe('bbbbbbbbbbbbbbbb')
  })
})

describe('quality and tracks', () => {
  it('reloads a picked quality at the same position in the same play, paused if it was, and saves it as the default', async () => {
    const h = harness({ decide: () => hls() })
    await started(h)
    await h.run(30_000)
    h.c.pause(); await h.settle()
    h.c.setQuality('720')
    await h.settle()
    expect(h.decisions().at(-1)?.body).toMatchObject({ playId: 'aaaaaaaaaaaaaaaa', quality: '720', startMs: 30_000, profile: { maxHeight: 720, maxBitrate: 4_000_000 } })
    expect(h.player.opened.at(-1)?.options).toMatchObject({ startMs: 30_000, paused: true })
    expect(h.saved).toEqual(['720'])
    expect(h.c.snapshot()).toMatchObject({ phase: 'paused', positionMs: 30_000, quality: { id: '720' } })
  })

  it('switches a direct-play file’s own audio track when the subtitle is the viewer’s, else asks the server', async () => {
    const h = harness({ decide: (body) => decision({ audio: { copy: true, index: Number(body?.audioIndex ?? 1) } }) })
    await started(h)
    h.player.trackList = [{ kind: 'audio', index: 5 }, { kind: 'audio', index: 6 }]
    h.c.selectAudio(2); await h.settle()
    // The subtitle follows the audio from the account's settings: the server decides again.
    expect(h.decisions().at(-1)?.body).toMatchObject({ audioIndex: 2 })
    expect(h.player.calls).not.toContain('track:audio:6')
    h.c.selectSubtitle(undefined); await h.settle()
    const asked = h.decisions().length
    h.c.selectAudio(1); await h.settle()
    expect(h.player.calls).toContain('track:audio:5')
    expect(h.decisions()).toHaveLength(asked)
  })
})

describe('seeking with the remote', () => {
  it('moves the target while held, seeks once on release, and shows the target until it lands', async () => {
    const h = harness({ decide: () => decision({ trickplay: { url: '/api/files/1/trickplay/v1/{sheet}.jpg', intervalMs: 10_000, width: 320, height: 180, columns: 10, rows: 10, count: 60, sheets: 1 } }) })
    await started(h)
    h.c.beginScrub(1)
    expect(h.c.snapshot().scrub).toMatchObject({ targetMs: 10_000, step: '+10 s', holding: true, thumbnail: { width: 320, height: 180 } })
    for (let t = 0; t < 2500; t += 50) { await vi.advanceTimersByTimeAsync(50); h.c.scrubStep(1) }
    expect(h.player.calls.filter((c) => c.startsWith('seek'))).toEqual([])
    const target = h.c.snapshot().scrub!.targetMs
    expect(target).toBe(10_000 + 9 * 10_000 + 3 * 30_000)
    expect(h.c.snapshot().scrub?.step).toBe('+30 s')
    expect(h.images).toEqual(['https://tv.example/api/files/1/trickplay/v1/0.jpg?access_token=media-1'])
    h.c.commitScrub()
    expect(h.player.calls.filter((c) => c.startsWith('seek'))).toEqual([`seek:${target}`])
    await h.run(250)
    expect(h.c.snapshot().scrub).toBeUndefined()
    expect(h.c.snapshot().positionMs).toBe(target + 250)
  })

  it('a short press seeks 10 s, and Back drops the target', async () => {
    const h = harness()
    await started(h)
    await h.run(20_000)
    h.c.beginScrub(-1); h.c.commitScrub()
    expect(h.player.calls.at(-1)).toBe('seek:10000')
    h.c.beginScrub(1); h.c.cancelScrub()
    expect(h.c.snapshot().scrub).toBeUndefined()
    expect(h.player.calls.at(-1)).toBe('seek:10000')
  })

  it('seeks an event session within what it produced, and asks for a new one outside it', async () => {
    const h = harness({ decide: (body) => hls({ playlist: 'event', startMs: Number(body?.startMs ?? 60_000), sessionId: `at-${body?.startMs}` }) })
    await started(h, { kind: 'file', fileId: 1, startMs: 60_000 })
    h.player.emit({ durationMs: 30_000 })
    h.c.seekTo(80_000)
    expect(h.player.calls.at(-1)).toBe('seek:20000')
    h.c.seekTo(200_000); await h.settle()
    expect(h.decisions().at(-1)?.body).toMatchObject({ startMs: 200_000, playId: 'aaaaaaaaaaaaaaaa' })
    expect(h.player.opened.at(-1)?.options.startMs).toBe(0)
    expect(h.deletes()).toEqual(['/api/playback/at-60000'])
    await h.run(1000)
    expect(h.c.snapshot().positionMs).toBe(201_000)
  })
})

describe('skipping', () => {
  const segments = [{ id: 1, kind: 'intro' as const, startMs: 10_000, endMs: 70_000, final: false, confidence: 1, source: 'chapter' as const },
    { id: 2, kind: 'credits' as const, startMs: 580_000, final: true, confidence: 1, source: 'chapter' as const }]

  it('asks: a Skip button that seeks to the segment’s end', async () => {
    const h = harness({ decide: () => decision({ segments }), settings: { skip: { intro: 'ask' } } })
    await started(h)
    await h.run(11_000)
    expect(h.c.snapshot().skip).toEqual({ segmentId: 1, kind: 'intro', label: 'Skip Intro' })
    h.c.skip()
    expect(h.player.calls.at(-1)).toBe('seek:70000')
  })

  it('auto: skips at once, but not a segment the viewer seeked into', async () => {
    const h = harness({ decide: () => decision({ segments }), settings: { skip: { intro: 'auto' } } })
    await started(h)
    await h.run(10_250)
    expect(h.player.calls.filter((c) => c === 'seek:70000')).toHaveLength(1)
    h.c.seekTo(20_000)
    await h.run(2000)
    expect(h.player.calls.filter((c) => c === 'seek:70000')).toHaveLength(1)
    expect(h.c.snapshot().skip?.label).toBe('Skip Intro')
  })

  it('auto-delay: fills for 3 s of playing, holding while paused, then skips', async () => {
    const h = harness({ decide: () => decision({ segments }), settings: { skip: { intro: 'auto-delay' } } })
    await started(h)
    await h.run(11_500)
    const filled = h.c.snapshot().skip!.progress!
    expect(filled).toBeGreaterThan(0.4)
    expect(filled).toBeLessThan(0.7)
    h.c.pause(); await h.run(5000)
    expect(h.c.snapshot().skip?.progress).toBe(filled)
    expect(h.player.calls).not.toContain('seek:70000')
    h.c.resume(); await h.run(1750)
    expect(h.player.calls.at(-1)).toBe('seek:70000')
  })

  it('none: no button; and a skip to the end finishes the file', async () => {
    const h = harness({ decide: () => decision({ segments }), settings: { skip: { intro: 'none', credits: 'ask' } } })
    await started(h)
    await h.run(11_000)
    expect(h.c.snapshot().skip).toBeUndefined()
    h.c.seekTo(590_000); await h.run(250)
    expect(h.c.snapshot().skip?.label).toBe('Skip Credits')
    h.c.skip(); await h.settle()
    expect(h.c.snapshot()).toMatchObject({ phase: 'ended', exited: true })
    expect(h.beats().at(-1)?.body).toMatchObject({ state: 'stopped', positionMs: 600_000 })
  })
})

describe('the next episode and Still watching', () => {
  const next = { fileId: 2, episodeId: 20, showId: 3, showTitle: 'Harbor Lights', seasonNumber: 1, episodeNumber: 2, title: 'Squall Line' }
  const episode = (patch: Partial<Decision> = {}) => decision({ nextEpisode: next, upNextMs: 580_000, ...patch })

  it('counts down from the credits, holding while paused, and plays the next episode as a new play', async () => {
    const h = harness({ decide: (_b, path) => path.includes('/2/') ? decision() : episode() })
    await started(h, { kind: 'file', fileId: 1, startMs: 579_000 })
    await h.run(1000)
    expect(h.c.snapshot().next).toMatchObject({ heading: 'S01 E02 · Squall Line', seconds: 10 })
    await h.run(4000)
    expect(h.c.snapshot().next?.seconds).toBe(6)
    h.c.pause(); await h.run(5000)
    expect(h.c.snapshot().next?.seconds).toBe(6)
    h.c.resume(); await h.run(6250)
    expect(h.beats().filter((b) => b.path === '/api/files/1/progress').at(-1)?.body).toMatchObject({ state: 'stopped', positionMs: 600_000 })
    expect(h.decisions().at(-1)).toMatchObject({ path: '/api/files/2/playback' })
    expect(h.decisions().at(-1)?.body).not.toHaveProperty('playId')
    expect(h.c.snapshot().request).toEqual({ kind: 'file', fileId: 2, title: 'Harbor Lights · S01 E02 · Squall Line' })
  })

  it('says what plays next, and the segments, from the start, with no card yet', async () => {
    const segments: Decision['segments'] = [{ id: 1, kind: 'intro', startMs: 10_000, endMs: 90_000, confidence: 1, final: true, source: 'manual' }]
    const h = harness({ decide: () => episode({ segments }) })
    await started(h)
    expect(h.c.snapshot()).toMatchObject({ upNext: next, segments, next: undefined })
    const trailer = harness({ decide: () => episode() })
    await started(trailer, { kind: 'trailer', fileId: 1 })
    expect(trailer.c.snapshot().upNext).toBeUndefined()
  })

  it('hides the card for Watch credits until the end, and offers Play now without a countdown when autoplay is off', async () => {
    const h = harness({ decide: () => episode(), settings: { autoplayNext: false } })
    await started(h, { kind: 'file', fileId: 1, startMs: 581_000 })
    await h.run(500)
    expect(h.c.snapshot().next).toMatchObject({ countdownMs: undefined, seconds: undefined })
    h.c.watchCredits(); await h.run(500)
    expect(h.c.snapshot().next).toBeUndefined()
    h.player.emit({ state: 'ended' }); await h.settle()
    expect(h.c.snapshot().next).toBeDefined()
    expect(h.c.snapshot().exited).toBe(false)
  })

  it('asks after 90 minutes and three episodes in a row with no input, then goes on', async () => {
    const h = harness({ decide: () => episode({ resumeMs: 579_000 }) })
    await started(h, { kind: 'file', fileId: 1, startMs: 579_000 })
    for (let n = 0; n < 3; n++) { h.setStill(n * 30 * 60_000); await h.run(11_500) }
    expect(h.decisions().filter((r) => r.body?.startMs === undefined)).toHaveLength(3)
    h.setStill(91 * 60_000)
    await h.run(11_500)
    expect(h.c.snapshot().askStillWatching).toBe(true)
    const asked = h.decisions().length
    await h.run(20_000)
    expect(h.decisions()).toHaveLength(asked)
    h.c.keepWatching(); await h.settle()
    expect(h.decisions()).toHaveLength(asked + 1)
    expect(h.c.snapshot().askStillWatching).toBe(false)
  })

  it('asks when nobody pressed anything for 90 minutes, however few episodes played', async () => {
    const h = harness({ decide: () => episode() })
    await started(h, { kind: 'file', fileId: 1, startMs: 579_000 })
    h.setStill(90 * 60_000)
    await h.run(11_500)
    expect(h.c.snapshot().askStillWatching).toBe(true)
  })

  it('does not ask when the viewer pressed a key lately', async () => {
    const h = harness({ decide: () => episode() })
    await started(h, { kind: 'file', fileId: 1, startMs: 579_000 })
    h.setStill(90 * 60_000)
    h.c.noteInput()
    await h.run(11_500)
    expect(h.c.snapshot().askStillWatching).toBe(false)
    expect(h.decisions().at(-1)?.body?.startMs).toBeUndefined()
  })
})

describe('subtitles', () => {
  it('shows a text track’s cues by the player’s clock, through seeks', async () => {
    const h = harness({ decide: () => decision({ subtitles: [textTrack] }), fetchText: async (url) => { expect(url).toBe('https://tv.example/api/files/1/subtitles/s3.vtt?access_token=media-1'); return vtt('Line') } })
    await started(h)
    h.c.selectSubtitle('s3'); await h.settle()
    expect(h.decisions()).toHaveLength(1)
    await h.run(10_000)
    expect(h.c.snapshot().subtitles.cues.map((c) => c.lines[0])).toEqual(['Line one'])
    // The cue ends between ticks: the scheduler wakes for it.
    await h.run(2000)
    await vi.advanceTimersByTimeAsync(0)
    expect(h.c.snapshot().subtitles.cues.map((c) => c.lines[0])).toEqual([])
    await h.run(250)
    expect(h.c.snapshot().subtitles.cues.map((c) => c.lines[0])).toEqual(['Line two'])
    h.c.seekTo(101_000); await h.settle()
    expect(h.c.snapshot().subtitles.cues.map((c) => c.lines[0])).toEqual(['Line later'])
    h.c.seekTo(5_000); await h.settle()
    expect(h.c.snapshot().subtitles.cues).toEqual([])
    expect(h.c.snapshot().subtitles).toMatchObject({ selected: 's3', automatic: false, size: 'normal' })
  })

  it('reads an event session’s cues at the player’s time, as the server shifts them', async () => {
    const h = harness({ decide: () => hls({ playlist: 'event', startMs: 90_000, subtitles: [{ ...textTrack, url: '/api/files/1/subtitles/s3.vtt?shift=90000' }] }) })
    await started(h, { kind: 'file', fileId: 1, startMs: 90_000 })
    h.c.selectSubtitle('s3')
    await h.run(10_500)
    expect(h.c.snapshot().positionMs).toBe(100_500)
    expect(h.c.snapshot().subtitles.cues.map((c) => c.lines[0])).toEqual(['cue one'])
  })

  it('burns an image track in with a new decision, and turns it off with another', async () => {
    const h = harness({ decide: (body) => decision({ subtitles: [textTrack, imageTrack], burnIn: body?.burnIn ? imageTrack : undefined, mode: body?.burnIn ? 'transcode' : 'direct' }) })
    await started(h)
    h.c.selectSubtitle('s4'); await h.settle()
    expect(h.decisions().at(-1)?.body).toMatchObject({ subtitleId: 's4', burnIn: true, subtitleMode: 'off' })
    expect(h.c.snapshot().subtitles.selected).toBe('s4')
    h.c.selectSubtitle(undefined); await h.settle()
    expect(h.decisions().at(-1)?.body).toMatchObject({ subtitleMode: 'off' })
    expect(h.decisions().at(-1)?.body).not.toHaveProperty('burnIn')
  })

  it('drops a track whose cues fail to load and says so', async () => {
    const h = harness({ decide: () => decision({ subtitles: [textTrack] }), fetchText: async () => { throw new Error('404') } })
    await started(h)
    h.c.selectSubtitle('s3'); await h.settle(); await h.settle()
    expect(h.c.snapshot().subtitles.selected).toBeUndefined()
    expect(h.c.snapshot().note).toBe('Couldn’t load a subtitle. The video will keep playing.')
  })

  it('searches online once it plays, and downloads a result into a new decision for the same play', async () => {
    const downloaded = { ...textTrack, id: 'd9', source: 'opensubtitles' as const, url: '/api/files/1/subtitles/d9.vtt' }
    const h = harness({
      decide: (body) => decision({ subtitles: body?.subtitleId === 'd9' ? [downloaded] : [] }),
      routes: {
        'GET /api/files/1/subtitles/search': () => ({ available: true, canDownload: true, languages: ['en'], provider: 'opensubtitles', attribution: 'Subtitles from OpenSubtitles', quota: { remaining: 5, signedIn: false },
          results: [{ id: 'r1', language: 'en', release: 'Some.Release', hashMatch: true, hearingImpaired: false, forced: false, aiTranslated: false, downloads: 3, trusted: true }] }),
        'POST /api/files/1/subtitles/download': (body) => { expect(body).toEqual({ resultId: 'r1' }); return downloaded },
      },
    })
    await started(h)
    expect(h.c.snapshot().subtitles.online).toMatchObject({ available: true, remaining: 5, results: [{ id: 'r1', label: 'English', detail: 'matches this file · Some.Release' }] })
    h.c.downloadSubtitle('r1'); await h.settle(); await h.settle()
    expect(h.decisions().at(-1)?.body).toMatchObject({ subtitleId: 'd9', playId: 'aaaaaaaaaaaaaaaa' })
    expect(h.c.snapshot().subtitles).toMatchObject({ selected: 'd9', online: { remaining: 4, results: [{ trackId: 'd9' }] } })
  })

  it('asks for an automatic subtitle after the first frame when the account wants one, and plays it', async () => {
    const auto = { ...textTrack, id: 'd5', source: 'opensubtitles' as const }
    const h = harness({
      settings: { subtitles: { mode: 'on', languages: ['en'], automaticDownload: true } },
      decide: (body) => decision({ subtitles: body?.subtitleId ? [auto] : [], subtitleId: body?.subtitleId as string | undefined }),
      routes: { 'POST /api/files/1/subtitles/automatic': (body) => { expect(body).toEqual({ playId: 'aaaaaaaaaaaaaaaa', cancel: false }); return { track: auto } } },
    })
    await started(h); await h.settle()
    expect(h.decisions().at(-1)?.body).toMatchObject({ subtitleId: 'd5', subtitleMode: 'auto' })
    expect(h.c.snapshot().subtitles).toMatchObject({ selected: 'd5', automatic: true })
  })
})

describe('tokens, recovery and the app’s lifecycle', () => {
  it('renews the media token before it runs out and reopens the stream where it is', async () => {
    const h = harness({ decide: () => decision({ durationMs: 3 * HOUR, mediaToken: { token: 'short', param: 'access_token', expiresAt: new Date(Date.now() + 40 * 60_000).toISOString() } }) })
    await started(h)
    await h.run(39 * 60_000 - 1000)
    expect(h.player.opened).toHaveLength(1)
    await h.run(2000)
    expect(h.sent.filter((r) => r.path === '/api/auth/media-token')).toHaveLength(1)
    expect(h.player.opened).toHaveLength(2)
    expect(h.player.opened[1].url).toMatch(/access_token=renewed-/)
    expect(h.player.opened[1].options.startMs).toBeGreaterThan(38 * 60_000)
    expect(h.decisions()).toHaveLength(1)
  }, 20_000)

  it('recovers a dropped stream twice with new decisions in the same play, then says why', async () => {
    const h = harness({ decide: () => hls() })
    await started(h)
    await h.run(5000)
    for (let n = 0; n < 2; n++) { h.player.emit({ state: 'error', failure: { code: 'PLAYER_ERROR_CONNECTION_FAILED', kind: 'network' } }); await h.settle() }
    expect(h.decisions().slice(1).map((r) => r.body?.playId)).toEqual(['aaaaaaaaaaaaaaaa', 'aaaaaaaaaaaaaaaa'])
    h.player.emit({ state: 'error', failure: { code: 'PLAYER_ERROR_CONNECTION_FAILED', kind: 'network' } }); await h.settle()
    expect(h.decisions()).toHaveLength(3)
    expect(h.c.snapshot()).toMatchObject({ phase: 'error', error: 'The TV couldn’t play this (PLAYER_ERROR_CONNECTION_FAILED). Try again, or choose a lower quality.' })
    h.c.resume(); await h.settle()
    expect(h.decisions()).toHaveLength(4)
  })

  it('asks for a new token after a refused stream, and for the plain profile once after a format the TV turned down', async () => {
    const h = harness({ decide: () => decision({ mediaToken: undefined }) })
    await started(h)
    expect(h.sent.filter((r) => r.path === '/api/auth/media-token')).toHaveLength(1)
    h.player.emit({ state: 'error', failure: { code: 'X', kind: 'auth', httpStatus: 401 } }); await h.settle()
    expect(h.sent.filter((r) => r.path === '/api/auth/media-token')).toHaveLength(2)
    h.player.emit({ state: 'error', failure: { code: 'PLAYER_ERROR_NOT_SUPPORTED_FILE', kind: 'format' } }); await h.settle()
    expect(h.decisions().at(-1)?.body).toMatchObject({ profile: { name: 'samsung-tv-fallback', videoCodecs: ['h264'] } })
    h.player.emit({ state: 'error', failure: { code: 'PLAYER_ERROR_NOT_SUPPORTED_FILE', kind: 'format' } }); await h.settle()
    expect(h.c.snapshot().phase).toBe('error')
  })

  it('in the background ends an HLS session and comes back paused where it was', async () => {
    const h = harness({ decide: (body) => hls({ sessionId: `s-${body?.startMs ?? 'first'}` }) })
    await started(h)
    await h.run(42_000)
    h.c.suspend(); await h.settle()
    expect(h.beats().at(-1)?.body).toMatchObject({ state: 'closed', positionMs: 42_000 })
    expect(h.deletes()).toEqual(['/api/playback/s-first'])
    expect(h.player.calls.at(-1)).toBe('stop')
    const beats = h.beats().length
    await h.run(60_000)
    expect(h.beats()).toHaveLength(beats)
    h.c.restore(); await h.settle()
    expect(h.decisions().at(-1)?.body).toMatchObject({ startMs: 42_000, playId: 'aaaaaaaaaaaaaaaa' })
    expect(h.player.opened.at(-1)?.options).toMatchObject({ startMs: 42_000, paused: true })
    expect(h.c.snapshot().phase).toBe('paused')
  })

  it('in the background lets a direct play’s decoder go and restores it', async () => {
    const h = harness()
    await started(h)
    h.c.suspend(); await h.settle()
    expect(h.player.calls).toContain('suspend')
    h.c.restore(); await h.settle()
    expect(h.player.calls.at(-1)).toBe('restore')
    expect(h.decisions()).toHaveLength(1)
  })
})

describe('recordings', () => {
  const growing = (patch: Partial<Decision> = {}) => hls({
    playlist: 'event', sessionId: 'rec', url: '/api/playback/rec/index.m3u8', durationMs: 600_000,
    growing: { startedAt: new Date(Date.now() - 600_000).toISOString(), endsAt: new Date(Date.now() + 1_800_000).toISOString() }, ...patch,
  })

  it('waits out a recording that is just starting, then grows its timeline on the TV’s clock', async () => {
    let asked = 0
    const h = harness({ routes: { 'POST /api/live-tv/recordings/r1/playback': () => ++asked === 1 ? new Response(JSON.stringify({ detail: 'starting' }), { status: 409, headers: { 'Retry-After': '2' } }) : growing() } })
    const playing = h.c.play({ kind: 'recording', recordingId: 'r1', title: 'News' })
    await h.settle()
    expect(h.c.snapshot().recording).toEqual({ now: false, goToNow: false, starting: true })
    await vi.advanceTimersByTimeAsync(2000); await playing; await h.settle()
    expect(h.decisions().map((r) => r.body?.playlist)).toEqual(['event', 'event'])
    expect(h.c.snapshot()).toMatchObject({ durationMs: 600_000, timelineMs: 2_400_000, recording: { now: true, goToNow: true, starting: false } })
    await h.run(60_000)
    expect(h.c.snapshot().durationMs).toBe(660_000)
    expect(h.beats().at(-1)?.path).toBe('/api/live-tv/recordings/r1/progress')
  })

  it('goes to now, a segment behind what is written', async () => {
    const h = harness({ routes: { 'POST /api/live-tv/recordings/r1/playback': (body) => growing({ startMs: Number(body?.startMs ?? 0), sessionId: `rec-${body?.startMs ?? 0}` }) } })
    await started(h, { kind: 'recording', recordingId: 'r1' })
    h.c.goToNow(); await h.settle()
    expect(h.decisions().at(-1)?.body?.startMs).toBe(596_000)
    await h.run(1000)
    expect(h.c.snapshot().recording?.goToNow).toBe(false)
  })

  it('freezes the length where the stream ends and leaves', async () => {
    const h = harness({ routes: { 'POST /api/live-tv/recordings/r1/playback': () => growing() } })
    await started(h, { kind: 'recording', recordingId: 'r1' })
    await h.run(5000)
    h.player.emit({ state: 'ended' }); await h.settle()
    expect(h.c.snapshot()).toMatchObject({ exited: true })
    expect(h.beats().at(-1)?.body).toMatchObject({ state: 'stopped', positionMs: 5000, durationMs: 5000 })
  })
})

describe('settings', () => {
  it('reads the person’s preferences, dropping skip modes it does not know', () => {
    expect(playbackSettings({ autoplayNext: false, skip: { intro: 'auto', recap: 'sometimes' } }, { quality: '1080', subtitleSize: 'larger' }))
      .toEqual({ ...DEFAULT_SETTINGS, quality: '1080', subtitleSize: 'larger', autoplayNext: false, skip: { intro: 'auto' }, subtitles: undefined })
  })
})
