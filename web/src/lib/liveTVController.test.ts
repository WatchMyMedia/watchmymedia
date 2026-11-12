import { afterEach, describe, expect, it, vi } from 'vitest'
import type { LiveQuality, LiveSession } from '../api/liveTV'
import { LiveTVController, type LiveTransport } from './liveTVController'

function deferred<T = void>() {
  let resolve!: (value: T | PromiseLike<T>) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

function session(id: string, channel = 'one', quality: LiveQuality = '1080'): LiveSession {
  return {
    id, channel: { id: channel, sourceId: 'source', number: '2.1', name: 'Test channel', enabled: true, available: true },
    quality, state: 'starting', paused: false, pauseRemainingSeconds: 300,
    bufferSeconds: 60, bufferBytes: 0, clientName: 'Test player',
    userId: 1, userName: 'Viewer', startedAt: '2026-11-01T12:00:00Z', lastSeenAt: '2026-11-01T12:00:00Z',
    url: `/api/live-tv/sessions/${id}/hls/live.m3u8`,
  }
}

function player() {
  const allocated = new Set<string>()
  const requests = new Map<string, LiveSession>()
  const sessions = new Map<string, LiveSession>()
  const events: string[] = []
  let maximumTuners = 0
  let requestNumber = 0
  function allocate(channel: string, quality: LiveQuality, requestId: string) {
    let view = requests.get(requestId)
    if (!view) {
      view = session(`session-${requests.size + 1}`, channel, quality)
      requests.set(requestId, view)
      sessions.set(view.id, view)
      allocated.add(view.id)
      maximumTuners = Math.max(maximumTuners, allocated.size)
      events.push(`allocate ${view.id}`)
    }
    return view
  }
  function release(id: string) {
    allocated.delete(id)
    events.push(`release ${id}`)
  }
  const transport = {
    start: vi.fn<LiveTransport['start']>(async (...args) => allocate(...args)),
    stop: vi.fn<LiveTransport['stop']>(async (id) => { release(id) }),
    status: vi.fn<LiveTransport['status']>(async (id) => sessions.get(id)!),
    heartbeat: vi.fn<LiveTransport['heartbeat']>(async (id, paused) => ({ ...sessions.get(id)!, paused })),
  }
  const controller = new LiveTVController(transport, () => `request-${++requestNumber}`)
  return { controller, transport, allocate, release, allocated, events, maximumTuners: () => maximumTuners }
}

afterEach(() => { vi.useRealTimers() })

describe('LiveTVController tuning and cleanup', () => {
  it('acknowledges a prepared Automatic output only after the player reports its frame', async () => {
    const { controller, transport, allocate } = player()
    transport.start.mockImplementationOnce(async (...args) => ({ ...allocate(...args), automatic: true }))
    await controller.switchTo('one', 'auto')
    const first = controller.getSnapshot().session!
    const directive = { generation: 2, from: 'original', to: '720', reason: 'connection', url: '/api/live-tv/sessions/output-2/hls/live.m3u8', mode: 'video', modeReason: 'bandwidth' }
    transport.status.mockResolvedValueOnce({ ...first, state: 'playing', outputGeneration: 1, actualQuality: 'original', directive })
    await controller.poll(false)
    controller.reportPlayback(first.id, { state: 'buffering', bufferedMs: 1800, throughputBps: 4_000_000, throughputAgeMs: 300, stalls: 1 })
    controller.settleOutput(first.id, 99, false)
    expect(transport.heartbeat).not.toHaveBeenCalled()
    transport.heartbeat.mockResolvedValueOnce({ ...first, state: 'playing', outputGeneration: 2, actualQuality: '720', url: directive.url })
    await controller.settleOutput(first.id, 2, false)
    expect(transport.heartbeat).toHaveBeenCalledWith(first.id, false, true, {
      state: 'buffering', bufferedMs: 1800, throughputBps: 4_000_000, throughputAgeMs: 300, stalls: 1, switchGeneration: 2,
    })
    expect(controller.getSnapshot().session).toMatchObject({ id: first.id, outputGeneration: 2, actualQuality: '720', url: directive.url })
    expect(transport.start).toHaveBeenCalledTimes(1)
    expect(transport.stop).not.toHaveBeenCalled()
    await controller.stop()
  })

  it('exposes stable snapshots and unsubscribes listeners', async () => {
    const { controller } = player()
    const initial = controller.getSnapshot()
    expect(controller.getSnapshot()).toBe(initial)
    const listener = vi.fn()
    const unsubscribe = controller.subscribe(listener)
    await controller.switchTo('one', '1080')
    expect(controller.getSnapshot()).toMatchObject({ phase: 'tuning', session: { id: 'session-1' } })
    expect(listener).toHaveBeenCalled()
    const calls = listener.mock.calls.length
    unsubscribe()
    await controller.stop()
    expect(listener).toHaveBeenCalledTimes(calls)
    expect(controller.getSnapshot()).toMatchObject({ phase: 'idle', session: undefined })
  })

  it('starts only the latest request when several arrive before tuning begins', async () => {
    const { controller, transport, allocated } = player()
    await Promise.all([controller.switchTo('one', '1080'), controller.switchTo('two', '720'), controller.switchTo('three', '480')])
    expect(transport.start.mock.calls).toEqual([['three', '480', 'request-3']])
    expect(controller.getSnapshot().session?.channel.id).toBe('three')
    await controller.stop()
    expect(allocated.size).toBe(0)
  })

  it('releases a late start before acquiring the latest channel, skipping intermediate channels', async () => {
    const { controller, transport, allocate, release, allocated, events, maximumTuners } = player()
    const started = deferred()
    const startReply = deferred()
    const stopping = deferred()
    const stopReply = deferred()
    transport.start.mockImplementationOnce(async (...args) => {
      const view = allocate(...args)
      started.resolve()
      await startReply.promise
      return view
    })
    transport.stop.mockImplementationOnce(async (id) => {
      stopping.resolve()
      await stopReply.promise
      release(id)
    })
    const first = controller.switchTo('one', '1080')
    await started.promise
    const second = controller.switchTo('two', '1080')
    const latest = controller.switchTo('three', '720')
    startReply.resolve()
    await stopping.promise
    expect(transport.start).toHaveBeenCalledTimes(1)
    expect(controller.getSnapshot()).toMatchObject({ phase: 'tuning', session: undefined })
    stopReply.resolve()
    await Promise.all([first, second, latest])
    expect(transport.start.mock.calls.map(([channel]) => channel)).toEqual(['one', 'three'])
    expect(events).toEqual(['allocate session-1', 'release session-1', 'allocate session-2'])
    expect(maximumTuners()).toBe(1)
    await controller.stop()
    expect(allocated.size).toBe(0)
  })

  it('waits for release before changing quality and discards obsolete quality changes', async () => {
    const { controller, transport, release, maximumTuners } = player()
    await controller.switchTo('one', '1080')
    const stopping = deferred()
    const stopReply = deferred()
    transport.stop.mockImplementationOnce(async (id) => {
      stopping.resolve()
      await stopReply.promise
      release(id)
    })
    const lower = controller.switchTo('one', '720')
    await stopping.promise
    const lowest = controller.switchTo('one', '360')
    expect(transport.start).toHaveBeenCalledTimes(1)
    stopReply.resolve()
    await Promise.all([lower, lowest])
    expect(transport.start.mock.calls.map(([, quality]) => quality)).toEqual(['1080', '360'])
    expect(maximumTuners()).toBe(1)
    await controller.stop()
  })

  it('does not retune repeated requests for the pending or playing selection', async () => {
    const { controller, transport, allocate } = player()
    const started = deferred()
    const startReply = deferred()
    transport.start.mockImplementationOnce(async (...args) => {
      const view = allocate(...args)
      started.resolve()
      await startReply.promise
      return view
    })
    const first = controller.switchTo('one', '1080')
    await started.promise
    const repeated = controller.switchTo('one', '1080')
    startReply.resolve()
    await Promise.all([first, repeated])
    transport.status.mockResolvedValueOnce({ ...session('session-1'), state: 'playing' })
    await controller.poll(false)
    await controller.switchTo('one', '1080')
    expect(transport.start).toHaveBeenCalledTimes(1)
    expect(transport.stop).not.toHaveBeenCalled()
    expect(controller.getSnapshot().phase).toBe('playing')
    await controller.stop()
  })

  it('releases a pending start on unmount and makes repeated Stop harmless', async () => {
    const { controller, transport, allocate, allocated } = player()
    const started = deferred()
    const reply = deferred()
    transport.start.mockImplementationOnce(async (...args) => {
      const view = allocate(...args)
      started.resolve()
      await reply.promise
      return view
    })
    const tuning = controller.switchTo('one', '1080')
    await started.promise
    const stop = controller.stop()
    const again = controller.stop()
    reply.resolve()
    await Promise.all([tuning, stop, again])
    expect(controller.getSnapshot()).toMatchObject({ phase: 'idle', session: undefined })
    expect(transport.stop.mock.calls).toEqual([['session-1']])
    expect(allocated.size).toBe(0)
  })

  it('supports StrictMode start, Stop, start without leaking or accepting the first session', async () => {
    const { controller, transport, allocate, allocated, maximumTuners } = player()
    const started = deferred()
    const reply = deferred()
    transport.start.mockImplementationOnce(async (...args) => {
      const view = allocate(...args)
      started.resolve()
      await reply.promise
      return view
    })
    const first = controller.switchTo('one', '1080')
    await started.promise
    const cleanup = controller.stop()
    const remount = controller.switchTo('one', '1080')
    reply.resolve()
    await Promise.all([first, cleanup, remount])
    expect(controller.getSnapshot().session?.id).toBe('session-2')
    expect(transport.start.mock.calls.map(([, , id]) => id)).toEqual(['request-1', 'request-2'])
    expect(maximumTuners()).toBe(1)
    expect([...allocated]).toEqual(['session-2'])
    await controller.stop()
    expect(allocated.size).toBe(0)
  })

  it('keeps a failed release recoverable and blocks every new allocation until it succeeds', async () => {
    const { controller, transport, allocated, maximumTuners } = player()
    await controller.switchTo('one', '1080')
    transport.stop.mockRejectedValueOnce(new Error('Release failed'))
    await controller.switchTo('two', '720')
    expect(controller.getSnapshot()).toMatchObject({ phase: 'error', error: 'Release failed', session: undefined })
    expect(transport.start).toHaveBeenCalledTimes(1)
    expect([...allocated]).toEqual(['session-1'])
    await controller.switchTo('two', '720')
    expect(transport.stop.mock.calls).toEqual([['session-1'], ['session-1']])
    expect(controller.getSnapshot().session?.channel.id).toBe('two')
    expect(maximumTuners()).toBe(1)
    await controller.stop()
    expect(allocated.size).toBe(0)
  })

  it('reports Stop failures and lets another Stop retry the same session', async () => {
    const { controller, transport, allocated } = player()
    await controller.switchTo('one', '1080')
    transport.stop.mockRejectedValueOnce(new Error('Offline'))
    await controller.stop()
    expect(controller.getSnapshot()).toMatchObject({ phase: 'error', error: 'Offline', session: undefined })
    await controller.stop()
    expect(allocated.size).toBe(0)
    expect(controller.getSnapshot().phase).toBe('idle')
  })

  it.each([new TypeError('Lost reply'), new DOMException('Timed out', 'TimeoutError'), new DOMException('Aborted', 'AbortError'), new SyntaxError('Truncated JSON')])('reuses an ambiguous start request on Retry (%s)', async (error) => {
    const { controller, transport, allocate, allocated, maximumTuners } = player()
    transport.start.mockImplementationOnce(async (...args) => { allocate(...args); throw error })
    await controller.switchTo('one', '1080')
    expect(controller.getSnapshot().phase).toBe('error')
    await controller.switchTo('one', '1080')
    expect(transport.start.mock.calls.map(([, , id]) => id)).toEqual(['request-1', 'request-1'])
    expect(controller.getSnapshot().session?.id).toBe('session-1')
    expect(maximumTuners()).toBe(1)
    await controller.stop()
    expect(allocated.size).toBe(0)
  })

  it('recovers and releases an ambiguous start before switching to another channel', async () => {
    const { controller, transport, allocate, allocated, events, maximumTuners } = player()
    transport.start.mockImplementationOnce(async (...args) => { allocate(...args); throw new TypeError('Lost reply') })
    await controller.switchTo('one', '1080')
    await controller.switchTo('two', '720')
    expect(transport.start.mock.calls).toEqual([['one', '1080', 'request-1'], ['one', '1080', 'request-1'], ['two', '720', 'request-2']])
    expect(events).toEqual(['allocate session-1', 'release session-1', 'allocate session-2'])
    expect(maximumTuners()).toBe(1)
    await controller.stop()
    expect(allocated.size).toBe(0)
  })

  it('recovers a lost reply during unmount and releases the allocated tuner', async () => {
    const { controller, transport, allocate, allocated } = player()
    const started = deferred()
    const reply = deferred()
    transport.start.mockImplementationOnce(async (...args) => {
      allocate(...args)
      started.resolve()
      await reply.promise
      throw new TypeError('Lost reply')
    })
    const tuning = controller.switchTo('one', '1080')
    await started.promise
    const stop = controller.stop()
    reply.resolve()
    await Promise.all([tuning, stop])
    expect(transport.start.mock.calls.map(([, , id]) => id)).toEqual(['request-1', 'request-1'])
    expect(transport.stop).toHaveBeenCalledWith('session-1')
    expect(allocated.size).toBe(0)
    expect(controller.getSnapshot().phase).toBe('idle')
  })

  it('does not allocate another tuner when an ambiguous request still cannot be recovered', async () => {
    const { controller, transport, allocate, maximumTuners, allocated } = player()
    transport.start.mockImplementationOnce(async (...args) => { allocate(...args); throw new TypeError('Lost reply') })
    await controller.switchTo('one', '1080')
    transport.start.mockRejectedValueOnce(new TypeError('Still offline'))
    await controller.switchTo('two', '720')
    expect(transport.start.mock.calls.every(([channel]) => channel === 'one')).toBe(true)
    expect(controller.getSnapshot()).toMatchObject({ phase: 'error', error: 'Still offline', session: undefined })
    await controller.switchTo('two', '720')
    expect(maximumTuners()).toBe(1)
    await controller.stop()
    expect(allocated.size).toBe(0)
  })

  it('uses a fresh request after a definite server rejection or terminal session', async () => {
    const { controller, transport } = player()
    transport.start.mockRejectedValueOnce(new Error('tuners_busy'))
    await controller.switchTo('one', '1080')
    expect(controller.getSnapshot()).toMatchObject({ phase: 'error', error: 'tuners_busy' })
    await controller.switchTo('one', '1080')
    transport.status.mockResolvedValueOnce({ ...session('session-1'), state: 'stopped', reason: 'pause_expired' })
    await controller.poll(false)
    expect(controller.getSnapshot()).toMatchObject({ phase: 'ended', error: 'pause_expired' })
    await controller.switchTo('one', '1080')
    expect(transport.start.mock.calls.map(([, , id]) => id)).toEqual(['request-1', 'request-2', 'request-3'])
    await controller.stop()
  })

  it('does not forget an ambiguous allocation if its recovery request is refused', async () => {
    const { controller, transport, allocate, allocated, maximumTuners } = player()
    transport.start.mockImplementationOnce(async (...args) => { allocate(...args); throw new TypeError('Lost reply') })
    await controller.switchTo('one', '1080')
    transport.start.mockRejectedValueOnce(new Error('Temporarily unavailable'))
    await controller.switchTo('two', '720')
    await controller.stop()
    expect(transport.start.mock.calls.map(([, , id]) => id)).toEqual(['request-1', 'request-1', 'request-1'])
    expect(allocated.size).toBe(0)
    expect(maximumTuners()).toBe(1)
  })

  it('retries release when cleanup of a superseded start fails, before taking another tuner', async () => {
    const { controller, transport, allocate, allocated, maximumTuners } = player()
    const started = deferred()
    const reply = deferred()
    transport.start.mockImplementationOnce(async (...args) => {
      const view = allocate(...args)
      started.resolve()
      await reply.promise
      return view
    })
    const first = controller.switchTo('one', '1080')
    await started.promise
    const latest = controller.switchTo('two', '720')
    transport.stop.mockRejectedValueOnce(new TypeError('Lost release reply'))
    reply.resolve()
    await Promise.all([first, latest])
    expect(transport.stop.mock.calls).toEqual([['session-1'], ['session-1']])
    expect(controller.getSnapshot().session?.channel.id).toBe('two')
    expect(maximumTuners()).toBe(1)
    await controller.stop()
    expect(allocated.size).toBe(0)
  })
})

describe('LiveTVController polling and viewer input', () => {
  it.each(['stopped', 'failed', 'stopping', 'future-state'])('does not play or keep heartbeating a %s session', async (state) => {
    const { controller, transport } = player()
    await controller.switchTo('one', '1080')
    transport.status.mockResolvedValueOnce({ ...session('session-1'), state, reason: 'signal_lost' })
    await controller.poll(false)
    expect(controller.getSnapshot()).toMatchObject({ phase: 'ended', error: 'signal_lost' })
    await controller.poll(true)
    expect(transport.heartbeat).not.toHaveBeenCalled()
    await controller.stop()
  })

  it.each(['resolve', 'reject'] as const)('ignores stale poll replies that %s after switching sessions', async (outcome) => {
    const { controller, transport } = player()
    await controller.switchTo('one', '1080')
    const oldReply = deferred<LiveSession>()
    transport.status.mockReturnValueOnce(oldReply.promise)
    const polling = controller.poll(false)
    await controller.switchTo('two', '720')
    const current = controller.getSnapshot()
    if (outcome === 'resolve') oldReply.resolve({ ...session('session-1'), state: 'playing' })
    else oldReply.reject(new Error('Old request failed'))
    await polling
    expect(controller.getSnapshot()).toBe(current)
    await controller.stop()
  })

  it('does not poll or pause the session awaiting release during a retune', async () => {
    const { controller, transport, release } = player()
    await controller.switchTo('one', '1080')
    const stopping = deferred()
    const reply = deferred()
    transport.stop.mockImplementationOnce(async (id) => { stopping.resolve(); await reply.promise; release(id) })
    const switching = controller.switchTo('two', '720')
    await stopping.promise
    controller.setPaused('session-1', true)
    await controller.poll(false)
    await controller.poll(true)
    expect(transport.status).not.toHaveBeenCalled()
    expect(transport.heartbeat).not.toHaveBeenCalled()
    expect(controller.getSnapshot()).toMatchObject({ phase: 'tuning', session: undefined })
    reply.resolve()
    await switching
    await controller.stop()
  })

  it('lets a new session poll while a stale request is still pending', async () => {
    const { controller, transport } = player()
    await controller.switchTo('one', '1080')
    const oldReply = deferred<LiveSession>()
    transport.status.mockReturnValueOnce(oldReply.promise)
    const oldPoll = controller.poll(false)
    await controller.switchTo('two', '720')
    transport.status.mockResolvedValueOnce({ ...session('session-2', 'two', '720'), state: 'playing' })
    await controller.poll(false)
    expect(controller.getSnapshot()).toMatchObject({ phase: 'playing', session: { id: 'session-2' } })
    oldReply.resolve(session('session-1'))
    await oldPoll
    await controller.stop()
  })

  it.each(['resolve', 'reject'] as const)('never revives a stopped player when its pending heartbeat later %ss', async (outcome) => {
    const { controller, transport, allocated } = player()
    await controller.switchTo('one', '1080')
    const reply = deferred<LiveSession>()
    transport.heartbeat.mockReturnValueOnce(reply.promise)
    const polling = controller.poll(true)
    await controller.stop('inactive')
    const stopped = controller.getSnapshot()
    if (outcome === 'resolve') reply.resolve({ ...session('session-1'), state: 'playing' })
    else reply.reject(new TypeError('Offline'))
    await polling
    expect(controller.getSnapshot()).toBe(stopped)
    expect(allocated.size).toBe(0)
  })

  it('serializes status and heartbeat, preserves input, and sends a queued pause promptly', async () => {
    const { controller, transport } = player()
    await controller.switchTo('one', '1080')
    await controller.poll(true)
    transport.heartbeat.mockClear()
    const reply = deferred<LiveSession>()
    transport.status.mockReturnValueOnce(reply.promise)
    const polling = controller.poll(false)
    controller.noteInput()
    controller.setPaused('session-1', true)
    const heartbeat = controller.poll(true)
    const overlappingStatus = controller.poll(false)
    expect(transport.status).toHaveBeenCalledTimes(1)
    expect(transport.heartbeat).not.toHaveBeenCalled()
    reply.resolve(session('session-1'))
    await Promise.all([polling, heartbeat, overlappingStatus])
    expect(transport.heartbeat.mock.calls).toEqual([['session-1', true, true]])
    await controller.poll(true)
    expect(transport.heartbeat).toHaveBeenLastCalledWith('session-1', true, false)
    await controller.stop()
  })

  it('reports the latest pause state and input when they change during a heartbeat', async () => {
    const { controller, transport } = player()
    await controller.switchTo('one', '1080')
    const reply = deferred<LiveSession>()
    transport.heartbeat.mockReturnValueOnce(reply.promise)
    const heartbeat = controller.poll(true)
    controller.noteInput()
    controller.setPaused('session-1', true)
    controller.setPaused('session-1', false)
    expect(transport.heartbeat.mock.calls).toEqual([['session-1', false, true]])
    reply.resolve(session('session-1'))
    await heartbeat
    expect(transport.heartbeat.mock.calls).toEqual([['session-1', false, true], ['session-1', false, true]])
    await controller.stop()
  })

  it('does not drop a heartbeat requested in the microtask after status completes', async () => {
    const { controller, transport } = player()
    await controller.switchTo('one', '1080')
    const reply = deferred<LiveSession>()
    transport.status.mockReturnValueOnce(reply.promise)
    const polling = controller.poll(false)
    reply.resolve(session('session-1'))
    await reply.promise
    await controller.poll(true)
    await polling
    expect(transport.heartbeat.mock.calls).toEqual([['session-1', false, true]])
    await controller.stop()
  })

  it('never carries queued pause or heartbeat work into a stopped or replacement session', async () => {
    const { controller, transport } = player()
    await controller.switchTo('one', '1080')
    const reply = deferred<LiveSession>()
    transport.status.mockReturnValueOnce(reply.promise)
    const polling = controller.poll(false)
    controller.setPaused('session-1', true)
    await controller.stop()
    await controller.switchTo('two', '720')
    reply.resolve(session('session-1'))
    await polling
    expect(transport.heartbeat).not.toHaveBeenCalled()
    controller.setPaused('session-1', true)
    await controller.poll(true)
    expect(transport.heartbeat.mock.calls).toEqual([['session-2', false, true]])
    await controller.stop()
  })

  it('clears playback and releases the tuner after a polling failure', async () => {
    const { controller, transport, allocated } = player()
    await controller.switchTo('one', '1080')
    transport.status.mockRejectedValueOnce(new Error('Connection lost'))
    await controller.poll(false)
    expect(controller.getSnapshot()).toMatchObject({ phase: 'error', error: 'Connection lost', session: undefined })
    expect(transport.stop).toHaveBeenCalledWith('session-1')
    expect(allocated.size).toBe(0)
    await controller.poll(true)
    expect(transport.heartbeat).not.toHaveBeenCalled()
  })

  it('retains failed error cleanup so Retry cannot allocate beside the previous session', async () => {
    const { controller, transport, allocated, maximumTuners } = player()
    await controller.switchTo('one', '1080')
    transport.heartbeat.mockRejectedValueOnce(new TypeError('Offline'))
    transport.stop.mockRejectedValueOnce(new TypeError('Still offline'))
    await controller.poll(true)
    expect(controller.getSnapshot()).toMatchObject({ phase: 'error', session: undefined })
    expect([...allocated]).toEqual(['session-1'])
    await controller.switchTo('one', '1080')
    expect(transport.stop.mock.calls).toEqual([['session-1'], ['session-1']])
    expect(maximumTuners()).toBe(1)
    await controller.stop()
    expect(allocated.size).toBe(0)
  })

  it('rejects a mismatched status response without releasing another session', async () => {
    const { controller, transport, allocated } = player()
    await controller.switchTo('one', '1080')
    transport.status.mockResolvedValueOnce(session('unrelated'))
    await controller.poll(false)
    expect(controller.getSnapshot()).toMatchObject({ phase: 'error', session: undefined })
    expect(transport.stop.mock.calls).toEqual([['session-1']])
    expect(allocated.size).toBe(0)
  })

  it('does not reset the inactivity clock on polling, and expires with an explicit reason', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-11-01T12:00:00Z'))
    const { controller, allocated } = player()
    await controller.switchTo('one', '1080')
    const firstInput = controller.getSnapshot().lastInput
    vi.setSystemTime(firstInput + 60_000)
    await controller.poll(false)
    await controller.poll(true)
    expect(controller.getSnapshot().lastInput).toBe(firstInput)
    controller.noteInput()
    expect(controller.getSnapshot().lastInput).toBe(firstInput + 60_000)
    await controller.stop('inactive')
    expect(controller.getSnapshot()).toMatchObject({ phase: 'ended', error: 'inactive', session: undefined })
    expect(allocated.size).toBe(0)
  })
})
