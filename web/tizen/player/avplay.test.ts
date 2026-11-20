import { afterEach, describe, expect, it, vi } from 'vitest'
import { AVPlayPlayer, PlaybackFailure, failureOf, readTracks, samsungDisplay, type AVPlayAPI, type PlayerUpdate, type SamsungWindow } from './avplay'

/** `keyframeMs`: a seek lands on the next keyframe, as AVPlay's do in a file. */
function fixture({ keyframeMs }: { keyframeMs?: number } = {}) {
  let state: ReturnType<AVPlayAPI['getState']> = 'NONE'
  let prepared = () => {}
  let refused: (error: unknown) => void = () => {}
  let sought = () => {}
  let listener!: Parameters<AVPlayAPI['setListener']>[0]
  let time = 0
  let liveDuration = ''
  const tracks = [
    { index: 0, type: 'VIDEO', extra_info: '{"fourCC":"HEVC","Width":"3840","Height":"2160"}' },
    { index: 1, type: 'AUDIO', extra_info: '{"language":"eng","channels":"6","fourCC":"EAC3"}' },
    { index: 2, type: 'AUDIO', extra_info: '{"language":"fra","channels":"2","fourCC":"AAC"}' },
    { index: 3, type: 'TEXT', extra_info: 'not json' },
  ]
  const api: AVPlayAPI = {
    open: vi.fn(() => { state = 'IDLE' }), close: vi.fn(() => { state = 'NONE' }), stop: vi.fn(() => { state = 'IDLE' }),
    prepareAsync: vi.fn((ok, fail) => { prepared = () => { state = 'READY'; ok() }; refused = fail }),
    seekTo: vi.fn((ms, ok) => { sought = () => { time = keyframeMs ? Math.ceil(ms / keyframeMs) * keyframeMs : ms; ok() } }), play: vi.fn(() => { state = 'PLAYING' }), pause: vi.fn(() => { state = 'PAUSED' }),
    getState: () => state, getDuration: () => 120_000, getCurrentTime: () => time, getVersion: () => 'test',
    getTotalTrackInfo: () => tracks, getCurrentStreamInfo: () => [tracks[0], tracks[1]], setSelectTrack: vi.fn(), setSilentSubtitle: vi.fn(),
    getStreamingProperty: vi.fn(() => liveDuration), setDisplayRect: vi.fn(), setDisplayMethod: vi.fn(), setStreamingProperty: vi.fn(),
    setListener: (next) => { listener = next },
  }
  const updates: PlayerUpdate[] = []
  const player = new AVPlayPlayer(api, { timeoutMs: 1000 })
  player.listen((update) => updates.push(update))
  return { api, updates, player, prepared: () => prepared(), refuse: (e: unknown) => refused(e), sought: () => sought(), listener: () => listener,
    live: (value: string) => { liveDuration = value }, at: (ms: number) => { time = ms } }
}
const flush = async () => { for (let i = 0; i < 5; i++) await Promise.resolve() }
async function opened(f: ReturnType<typeof fixture>, options: Parameters<AVPlayPlayer['open']>[1] = {}) {
  const opening = f.player.open('https://fixture.example/media', options)
  await flush(); f.prepared(); await flush()
  if ((options.startMs ?? 0) > 0 && !options.live) f.sought()
  await opening
}
afterEach(() => vi.useRealTimers())

describe('AVPlay lifecycle', () => {
  it('waits for prepare and initial seek before playing', async () => {
    const f = fixture()
    const opening = f.player.open('https://fixture.example/media', { startMs: 30_000 })
    await flush()
    expect(f.api.play).not.toHaveBeenCalled()
    f.prepared(); await flush()
    expect(f.api.seekTo).toHaveBeenCalledWith(30_000, expect.any(Function), expect.any(Function))
    expect(f.api.play).not.toHaveBeenCalled()
    f.sought(); await opening
    expect(f.api.play).toHaveBeenCalledOnce()
    expect(f.api.setSilentSubtitle).toHaveBeenCalledWith(true)
    expect(f.updates).toContainEqual({ durationMs: 120_000, video: { width: 3840, height: 2160, codec: 'hevc' } })
  })

  it('turns on 4K mode before prepare only for UHD streams', async () => {
    const f = fixture()
    const opening = f.player.open('https://fixture.example/media', { uhd: true })
    await flush()
    expect(f.api.setStreamingProperty).toHaveBeenCalledWith('SET_MODE_4K', 'TRUE')
    expect(vi.mocked(f.api.setStreamingProperty).mock.invocationCallOrder[0]).toBeLessThan(vi.mocked(f.api.prepareAsync).mock.invocationCallOrder[0])
    f.prepared(); await opening
    const hd = fixture()
    await opened(hd)
    expect(hd.api.setStreamingProperty).not.toHaveBeenCalled()
  })

  it('opens paused without playing, and resume plays from READY', async () => {
    const f = fixture()
    await opened(f, { startMs: 5000, paused: true })
    expect(f.api.play).not.toHaveBeenCalled()
    expect(f.updates.at(-1)).toEqual({ state: 'paused', positionMs: 5000 })
    await f.player.resume()
    expect(f.api.play).toHaveBeenCalledOnce()
    expect(f.updates.at(-1)).toEqual({ state: 'playing' })
  })

  it('does not restart playback from a late prepare callback after Stop', async () => {
    const f = fixture()
    const opening = f.player.open('https://fixture.example/media')
    await flush()
    const oldListener = f.listener()
    const stopping = f.player.stop()
    f.prepared(); await opening; await stopping
    oldListener.oncurrentplaytime(60_000)
    expect(f.api.play).not.toHaveBeenCalled()
    expect(f.updates.at(-1)).toEqual({ state: 'idle' })
    expect(f.api.getState()).toBe('NONE')
  })

  it('serializes a pause behind an asynchronous seek', async () => {
    const f = fixture()
    await opened(f)
    const seeking = f.player.seek(50_000)
    await flush()
    const pausing = f.player.pause()
    await flush(); expect(f.api.pause).not.toHaveBeenCalled()
    f.sought(); await seeking; await pausing
    expect(f.api.pause).toHaveBeenCalledOnce()
    expect(f.player.position()).toBe(50_000)
  })

  it('clamps seeks away from the exact end and redacts error payloads', async () => {
    const f = fixture()
    await opened(f)
    const seeking = f.player.seek(120_000)
    await flush()
    expect(f.api.seekTo).toHaveBeenCalledWith(119_999, expect.any(Function), expect.any(Function))
    f.sought(); await seeking
    f.listener().onerror('https://fixture.example/?access_token=secret')
    expect(JSON.stringify(f.updates.at(-1))).not.toContain('secret')
    expect(f.updates.at(-1)).toMatchObject({ state: 'error', failure: { code: 'UnknownError', kind: 'network' } })
  })

  it('names a refused request as an auth failure by its status alone', async () => {
    const f = fixture()
    await opened(f)
    f.listener().onevent?.('PLAYER_MSG_HTTP_ERROR_CODE', '401')
    f.listener().onerror('PLAYER_ERROR_CONNECTION_FAILED')
    expect(f.updates.at(-1)).toEqual({ state: 'error', failure: { code: 'PLAYER_ERROR_CONNECTION_FAILED', kind: 'auth', httpStatus: 401 } })
  })

  it('rejects an unplayable format as a format failure and closes the player', async () => {
    const f = fixture()
    const opening = f.player.open('https://fixture.example/media').catch((error: unknown) => error)
    await flush(); f.refuse('PLAYER_ERROR_NOT_SUPPORTED_FILE')
    const error = await opening
    expect(error).toBeInstanceOf(PlaybackFailure)
    expect((error as PlaybackFailure).failure).toEqual({ code: 'PLAYER_ERROR_NOT_SUPPORTED_FILE', kind: 'format' })
    expect(f.api.getState()).toBe('NONE')
  })

  it('bounds a hung preparation and closes the native player', async () => {
    vi.useFakeTimers()
    const f = fixture()
    const opening = f.player.open('https://fixture.example/media').catch((error: unknown) => error)
    await vi.advanceTimersByTimeAsync(1000)
    expect(await opening).toBeInstanceOf(PlaybackFailure)
    expect(f.api.getState()).toBe('NONE')
  })
})

describe('AVPlay tracks, live streams and backgrounding', () => {
  it('lists tracks from extra_info and switches by AVPlay’s own index', async () => {
    const f = fixture()
    await opened(f)
    expect(f.player.tracks()).toEqual([
      { kind: 'video', index: 0, codec: 'hevc', width: 3840, height: 2160, language: undefined, channels: undefined },
      { kind: 'audio', index: 1, language: 'eng', channels: 6, codec: 'eac3', width: undefined, height: undefined },
      { kind: 'audio', index: 2, language: 'fra', channels: 2, codec: 'aac', width: undefined, height: undefined },
      { kind: 'text', index: 3, language: undefined, codec: undefined, channels: undefined, width: undefined, height: undefined },
    ])
    expect(await f.player.selectTrack('audio', 2)).toBe(true)
    expect(f.api.setSelectTrack).toHaveBeenCalledWith('AUDIO', 2)
    expect(await f.player.selectTrack('audio', 3)).toBe(false)
    vi.mocked(f.api.setSelectTrack).mockImplementationOnce(() => { throw new Error('unsupported') })
    expect(await f.player.selectTrack('text', 3)).toBe(false)
  })

  it('skips tracks it cannot read', () => {
    expect(readTracks([{ index: 1.5, type: 'AUDIO', extra_info: '{}' }, { index: 2, type: 'DATA', extra_info: '{}' }])).toEqual([])
  })

  it('starts a live stream at its edge and seeks back to it for Go live', async () => {
    const f = fixture()
    await opened(f, { live: true, startMs: 40_000 })
    expect(f.api.seekTo).not.toHaveBeenCalled()
    f.live('10000|70000')
    expect(f.player.liveWindow()).toEqual({ startMs: 10_000, endMs: 70_000 })
    const going = f.player.seekToLiveEdge()
    await flush(); f.sought(); await going
    expect(f.api.seekTo).toHaveBeenCalledWith(67_000, expect.any(Function), expect.any(Function))
    f.live('garbage')
    expect(f.player.liveWindow()).toBeUndefined()
  })

  it('starts a file at or before the frame asked for, one keyframe gap back from a later landing', async () => {
    const f = fixture({ keyframeMs: 10_400 })
    const opening = f.player.open('https://fixture.example/media', { startMs: 40_000, paused: true })
    await flush(); f.prepared()
    for (let i = 0; i < 3; i++) { await flush(); f.sought() }
    await opening
    expect(vi.mocked(f.api.seekTo).mock.calls.map(([ms]) => ms)).toEqual([40_000, 31_100])
    expect(f.updates.at(-1)).toEqual({ state: 'paused', positionMs: 31_200 })
    expect(f.api.play).not.toHaveBeenCalled()
  })

  it('takes a resume’s keyframe up to where the viewer was with one seek', async () => {
    const f = fixture({ keyframeMs: 10_400 })
    await opened(f, { startMs: 40_000, latestMs: 45_000 })
    expect(f.api.seekTo).toHaveBeenCalledOnce()
    expect(f.updates).toContainEqual({ state: 'playing' })
    expect(f.player.position()).toBe(41_600)
  })

  it('goes further back when the keyframes are further apart than usual', async () => {
    const f = fixture({ keyframeMs: 25_000 })
    const opening = f.player.open('https://fixture.example/media', { startMs: 40_000, paused: true })
    await flush(); f.prepared()
    for (let i = 0; i < 4; i++) { await flush(); f.sought() }
    await opening
    expect(vi.mocked(f.api.seekTo).mock.calls.map(([ms]) => ms)).toEqual([40_000, 39_500, 10_000])
    expect(f.updates.at(-1)).toEqual({ state: 'paused', positionMs: 25_000 })
  })

  it('seeks back past the keyframe it is on, and takes a landing just after the frame', async () => {
    const f = fixture({ keyframeMs: 10_400 })
    await opened(f)
    f.at(21_000)
    const back = f.player.seek(11_000)
    for (let i = 0; i < 3; i++) { await flush(); f.sought() }
    await back
    expect(vi.mocked(f.api.seekTo).mock.calls.map(([ms]) => ms)).toEqual([11_000, 10_300])
    expect(f.updates.at(-1)).toEqual({ positionMs: 10_400, state: 'playing' })
    const near = f.player.seek(20_000)
    await flush(); f.sought(); await near
    expect(f.api.seekTo).toHaveBeenLastCalledWith(20_000, expect.any(Function), expect.any(Function))
    expect(f.updates.at(-1)).toEqual({ positionMs: 20_800, state: 'playing' })
  })

  it('classifies failures by code and status', () => {
    expect(failureOf('PLAYER_ERROR_NOT_SUPPORTED_FORMAT').kind).toBe('format')
    expect(failureOf('PLAYER_ERROR_GENEREIC').kind).toBe('network')
    expect(failureOf('PLAYER_ERROR_INVALID_STATE').kind).toBe('other')
    expect(failureOf('PLAYER_ERROR_INVALID_STATE', 503).kind).toBe('network')
    expect(failureOf('PLAYER_ERROR_CONNECTION_FAILED', 403)).toEqual({ code: 'PLAYER_ERROR_CONNECTION_FAILED', kind: 'auth', httpStatus: 403 })
  })

  it('reads the panel’s HDR and UHD claims, failing closed', () => {
    expect(samsungDisplay({ webapis: { avinfo: { isHdrTvSupport: () => true }, productinfo: { isUdPanelSupported: () => { throw new Error('no') } } } } as unknown as SamsungWindow)).toEqual({ hdr: true, uhd: false })
    expect(samsungDisplay({} as SamsungWindow)).toEqual({ hdr: false, uhd: false })
  })
})
