import { describe, expect, it } from 'vitest'
import { PlaybackFailure, type PlayerUpdate } from '../../player'
import { hlsFailure, videoFailure, WebOSPlayer, type HlsError, type HlsLike, type HlsMaker } from './player'

/** The parts of a video element the player uses, driven by the test. */
class FakeVideo extends EventTarget {
  src = ''
  currentTime = 0
  duration = NaN
  paused = true
  ended = false
  videoWidth = 0
  videoHeight = 0
  error: { code: number } | null = null
  seekable = { length: 0, start: () => 0, end: () => 0 }
  buffered = { length: 0, start: () => 0, end: () => 0 }
  plays = 0
  loads = 0
  removeAttribute(name: string) { if (name === 'src') this.src = '' }
  load() { this.loads++ }
  pause() { this.paused = true }
  async play() { this.plays++; this.paused = false; this.dispatchEvent(new Event('playing')) }
  /** The stream's first metadata: its length and picture size. */
  ready(seconds = 600, width = 1920, height = 1080) {
    this.duration = seconds; this.videoWidth = width; this.videoHeight = height
    this.dispatchEvent(new Event('loadedmetadata'))
  }
  fail(code: number) { this.error = { code }; this.dispatchEvent(new Event('error')) }
}

class FakeHls implements HlsLike {
  source?: string
  attached?: HTMLMediaElement
  destroyed = false
  private onError?: (event: string, data: HlsError) => void
  constructor(readonly config: Record<string, unknown>) {}
  loadSource(url: string) { this.source = url }
  attachMedia(media: HTMLMediaElement) { this.attached = media }
  destroy() { this.destroyed = true }
  on(_event: string, listener: (event: string, data: HlsError) => void) { this.onError = listener }
  error(data: HlsError) { this.onError?.('hlsError', data) }
}

function fixture(supported = true) {
  const video = new FakeVideo()
  const made: FakeHls[] = []
  const maker: HlsMaker = { isSupported: () => supported, create: (config) => { const h = new FakeHls(config as Record<string, unknown>); made.push(h); return h } }
  const player = new WebOSPlayer(video as unknown as HTMLVideoElement, maker, 1000)
  const updates: PlayerUpdate[] = []
  player.listen((u) => updates.push(u))
  const tick = () => new Promise((resolve) => setTimeout(resolve, 0))
  return { video, made, player, updates, tick }
}

describe('the LG player', () => {
  it('gives a file to the video, starts where it resumes and plays', async () => {
    const f = fixture()
    const opened = f.player.open('https://tv.example/api/files/7/stream?access_token=t', { startMs: 90_000 })
    await f.tick()
    expect(f.video.src).toBe('https://tv.example/api/files/7/stream?access_token=t')
    expect(f.made).toHaveLength(0)
    f.video.ready()
    await opened
    expect(f.video.currentTime).toBe(90)
    expect(f.video.plays).toBe(1)
    expect(f.updates).toContainEqual({ durationMs: 600_000 })
    expect(f.updates).toContainEqual(expect.objectContaining({ state: 'buffering', positionMs: 90_000, video: { width: 1920, height: 1080 } }))
    expect(f.updates.at(-1)).toEqual({ state: 'playing' })
  })

  it('plays HLS through hls.js from where it resumes, with a bounded buffer', async () => {
    const f = fixture()
    const opened = f.player.open('https://tv.example/api/playback/abc/main.m3u8?access_token=t', { startMs: 30_000, paused: true })
    await f.tick()
    const hls = f.made[0]
    expect(hls.source).toBe('https://tv.example/api/playback/abc/main.m3u8?access_token=t')
    expect(hls.attached).toBe(f.video)
    expect(hls.config).toMatchObject({ startPosition: 30, maxBufferLength: 30, maxBufferSize: 60_000_000, backBufferLength: 30 })
    f.video.currentTime = 30
    f.video.ready()
    await opened
    expect(f.video.plays).toBe(0)
    expect(f.updates.at(-1)).toEqual({ state: 'paused', positionMs: 30_000 })
  })

  it('starts a live stream at its edge, and keeps seeks inside its window', async () => {
    const f = fixture()
    const opened = f.player.open('https://tv.example/api/live-tv/sessions/s/main.m3u8', { live: true, startMs: 5000 })
    await f.tick()
    expect(f.made[0].config).toMatchObject({ liveSyncDurationCount: 3 })
    expect(f.made[0].config.startPosition).toBeUndefined()
    f.video.seekable = { length: 1, start: () => 100, end: () => 160 }
    f.video.ready(Infinity)
    await opened
    expect(f.player.liveWindow()).toEqual({ startMs: 100_000, endMs: 160_000 })
    await f.player.seek(10_000)
    expect(f.video.currentTime).toBe(100)
    await f.player.seekToLiveEdge()
    expect(f.video.currentTime).toBe(157)
  })

  it('turns a fatal hls.js error before the first frame into a failure the engine can act on', async () => {
    const f = fixture()
    const opened = f.player.open('https://tv.example/x/main.m3u8')
    await f.tick()
    f.made[0].error({ type: 'networkError', details: 'manifestLoadError', fatal: true, response: { code: 401 } })
    await expect(opened).rejects.toMatchObject({ failure: { code: 'HLS_manifestLoadError', kind: 'auth', httpStatus: 401 } })
    expect(f.made[0].destroyed).toBe(true)
  })

  it('reports one failure while playing, and stops cleanly', async () => {
    const f = fixture()
    const opened = f.player.open('https://tv.example/x/main.m3u8')
    await f.tick()
    f.video.ready()
    await opened
    f.made[0].error({ type: 'mediaError', details: 'bufferIncompatibleCodecsError', fatal: true })
    f.video.fail(3)
    expect(f.updates.filter((u) => u.state === 'error')).toEqual([{ state: 'error', failure: { code: 'HLS_bufferIncompatibleCodecsError', kind: 'format' } }])
    await f.player.stop()
    expect(f.updates.at(-1)).toEqual({ state: 'idle' })
    expect(f.video.src).toBe('')
  })

  it('gives up on a stream that never says what it is', async () => {
    const f = fixture(false)
    const opened = f.player.open('https://tv.example/api/files/7/stream')
    await expect(opened).rejects.toBeInstanceOf(PlaybackFailure)
    await expect(opened).rejects.toMatchObject({ failure: { code: 'TIMEOUT', kind: 'network' } })
  })

  it('lets a newer open take over from one still loading', async () => {
    const f = fixture()
    const first = f.player.open('https://tv.example/api/files/1/stream')
    await f.tick()
    const second = f.player.open('https://tv.example/api/files/2/stream')
    // As AVPlay's adapter does: the open a newer one replaced ends quietly.
    await expect(first).resolves.toBeUndefined()
    await f.tick()
    f.video.ready()
    await second
    expect(f.video.src).toBe('https://tv.example/api/files/2/stream')
  })

  it('words failures by kind, never by message', () => {
    expect(videoFailure(4)).toEqual({ code: 'MEDIA_ERR_SRC_NOT_SUPPORTED', kind: 'format' })
    expect(videoFailure(2)).toEqual({ code: 'MEDIA_ERR_NETWORK', kind: 'network' })
    expect(hlsFailure({ type: 'networkError', details: 'fragLoadError', response: { code: 503 } })).toEqual({ code: 'HLS_fragLoadError', kind: 'network', httpStatus: 503 })
    expect(hlsFailure({ type: 'otherError', details: 'https://leak.example/?access_token=x' })).toEqual({ code: 'HLS_unknown', kind: 'other' })
  })

  it('leaves track changes to the server', async () => {
    const f = fixture()
    expect(f.player.tracks()).toEqual([])
    await expect(f.player.selectTrack()).resolves.toBe(false)
  })
})
