import { afterEach, describe, expect, it, vi } from 'vitest'
import { AVPlayPlayer, type AVPlayAPI, type PlayerUpdate } from './avplay'

function fixture() {
  let state: ReturnType<AVPlayAPI['getState']> = 'NONE'
  let prepared = () => {}
  let sought = () => {}
  let listener: Parameters<AVPlayAPI['setListener']>[0]
  const api: AVPlayAPI = {
    open: vi.fn(() => { state = 'IDLE' }), close: vi.fn(() => { state = 'NONE' }), stop: vi.fn(() => { state = 'IDLE' }),
    prepareAsync: vi.fn((ok) => { prepared = () => { state = 'READY'; ok() } }),
    seekTo: vi.fn((_ms, ok) => { sought = ok }), play: vi.fn(() => { state = 'PLAYING' }), pause: vi.fn(() => { state = 'PAUSED' }),
    getState: () => state, getDuration: () => 120_000, getVersion: () => 'test',
    setDisplayRect: vi.fn(), setDisplayMethod: vi.fn(), setStreamingProperty: vi.fn(), setListener: (next) => { listener = next },
  }
  const updates: PlayerUpdate[] = []
  return { api, updates, player: new AVPlayPlayer(api, (update) => updates.push(update), 1000), prepared: () => prepared(), sought: () => sought(), listener: () => listener }
}
const flush = async () => { for (let i = 0; i < 5; i++) await Promise.resolve() }
afterEach(() => vi.useRealTimers())

describe('AVPlay lifecycle', () => {
  it('waits for prepare and initial seek before playing', async () => {
    const f = fixture()
    const opening = f.player.open('https://fixture.example/media', 30_000)
    await flush()
    expect(f.api.play).not.toHaveBeenCalled()
    f.prepared(); await flush()
    expect(f.api.seekTo).toHaveBeenCalledWith(30_000, expect.any(Function), expect.any(Function))
    expect(f.api.play).not.toHaveBeenCalled()
    f.sought(); await opening
    expect(f.api.play).toHaveBeenCalledOnce()
  })

  it('turns on 4K mode before prepare only for UHD streams', async () => {
    const f = fixture()
    const opening = f.player.open('https://fixture.example/media', 0, { uhd: true })
    await flush()
    expect(f.api.setStreamingProperty).toHaveBeenCalledWith('SET_MODE_4K', 'TRUE')
    expect(f.api.prepareAsync).toHaveBeenCalledOnce()
    expect(vi.mocked(f.api.setStreamingProperty).mock.invocationCallOrder[0]).toBeLessThan(vi.mocked(f.api.prepareAsync).mock.invocationCallOrder[0])
    f.prepared(); await opening
    const hd = fixture()
    const opened = hd.player.open('https://fixture.example/media', 0)
    await flush(); hd.prepared(); await opened
    expect(hd.api.setStreamingProperty).not.toHaveBeenCalled()
  })

  it('does not restart playback from a late prepare callback after Stop', async () => {
    const f = fixture()
    const opening = f.player.open('https://fixture.example/media', 0)
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
    const opening = f.player.open('https://fixture.example/media', 0)
    await flush(); f.prepared(); await opening
    const seeking = f.player.seek(50_000)
    await flush()
    const pausing = f.player.pause()
    await flush(); expect(f.api.pause).not.toHaveBeenCalled()
    f.sought(); await seeking; await pausing
    expect(f.api.pause).toHaveBeenCalledOnce()
  })

  it('clamps seeks away from the exact end and redacts error payloads', async () => {
    const f = fixture()
    const opening = f.player.open('https://fixture.example/media', 0)
    await flush(); f.prepared(); await opening
    const seeking = f.player.seek(120_000)
    await flush()
    expect(f.api.seekTo).toHaveBeenCalledWith(119_999, expect.any(Function), expect.any(Function))
    f.sought(); await seeking
    f.listener().onerror('https://fixture.example/?access_token=secret')
    expect(f.updates.at(-1)?.error).not.toContain('secret')
  })

  it('bounds a hung preparation and closes the native player', async () => {
    vi.useFakeTimers()
    const f = fixture()
    const opening = f.player.open('https://fixture.example/media', 0).catch((error: unknown) => error)
    await vi.advanceTimersByTimeAsync(1000)
    expect(await opening).toBeInstanceOf(Error)
    expect(f.api.getState()).toBe('NONE')
  })
})
