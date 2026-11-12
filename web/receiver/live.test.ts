import { describe, expect, it, vi } from 'vitest'
import type { LiveChannel, LiveSession } from '../src/api/liveTV'
import { castLiveTransport, liveInfo, nowEnds, receiverFailure, refusedLiveInfo, withCurrentToken } from './live'

const channel = (over: Partial<LiveChannel> = {}): LiveChannel => ({
  id: 'c1', sourceId: 's1', number: '4.1', name: 'Station 4', enabled: true, available: true,
  now: { id: 'p1', title: 'The Sleuth Files', episodeTitle: 'The Low Tide', start: '2026-11-03T01:00:00Z', end: '2026-11-03T02:00:00Z' },
  next: { id: 'p2', title: 'Harbor Weather', start: '2026-11-03T02:00:00Z', end: '2026-11-03T02:30:00Z' },
  ...over,
}) as LiveChannel

describe('withCurrentToken', () => {
  it('replaces the token wherever it sits in the query', () => {
    expect(withCurrentToken('https://s/api/live-tv/sessions/a/hls/live.m3u8?access_token=c1.old', 'c1.new')).toBe('https://s/api/live-tv/sessions/a/hls/live.m3u8?access_token=c1.new')
    expect(withCurrentToken('https://s/x.ts?access_token=c1.old&n=2', 'c1.new')).toBe('https://s/x.ts?access_token=c1.new&n=2')
    expect(withCurrentToken('https://s/x.ts?n=2&access_token=c1.old', 'c1.n/w+')).toBe('https://s/x.ts?n=2&access_token=c1.n%2Fw%2B')
  })
  it('leaves a URL without a token alone', () => {
    expect(withCurrentToken('https://s/x.ts?n=2', 'c1.new')).toBe('https://s/x.ts?n=2')
  })
})

describe('negotiated Cast Live TV', () => {
  it('starts, checks, heartbeats and stops on a TV without AbortSignal.timeout', async () => {
    const unavailable = vi.spyOn(AbortSignal, 'timeout').mockImplementation(() => { throw new TypeError('Unavailable on this TV') })
    try {
      const result = { data: { id: 'session-1' }, response: { ok: true, status: 200 } }
      const api = { POST: vi.fn().mockResolvedValue(result), GET: vi.fn().mockResolvedValue(result),
        PUT: vi.fn().mockResolvedValue(result), DELETE: vi.fn().mockResolvedValue(result) }
      const transport = castLiveTransport(api as unknown as Parameters<typeof castLiveTransport>[0], 'Fictional TV')
      await transport.start('channel-1', '720', 'request-1')
      await transport.status('session-1')
      await transport.heartbeat('session-1', false, false)
      await transport.stop('session-1')
      for (const method of Object.values(api)) expect(method.mock.calls[0][1].signal).toBeInstanceOf(AbortSignal)
      expect(unavailable).not.toHaveBeenCalled()
    } finally { unavailable.mockRestore() }
  })

  it('opts into Automatic with the compatible profile and relays switch evidence', async () => {
    const POST = vi.fn().mockResolvedValue({ data: { id: 'session-1' } })
    const PUT = vi.fn().mockResolvedValue({ data: { id: 'session-1' } })
    const api = { POST, PUT } as unknown as Parameters<typeof castLiveTransport>[0]
    const transport = castLiveTransport(api, 'Fictional TV', { profile: true, automatic: true })
    await transport.start('channel-1', 'auto', 'request-1')
    expect(POST.mock.calls[0][1].body).toMatchObject({ channelId: 'channel-1', quality: 'auto', autoRecovery: true,
      profile: { segmentFormats: ['mpegts'], videoCodecs: ['h264'], audioCodecs: ['aac'], interlaced: false },
    })
    await transport.heartbeat('session-1', false, true, { state: 'playing', bufferedMs: 1500, switchGeneration: 2 })
    expect(PUT.mock.calls[0][1].body).toEqual({ paused: false, interacted: true, state: 'playing', bufferedMs: 1500, switchGeneration: 2 })
  })

  it('offers fMP4 families only when the receiver negotiated adaptive support', async () => {
    const POST = vi.fn().mockResolvedValue({ data: { id: 'session-1' } })
    const api = { POST } as unknown as Parameters<typeof castLiveTransport>[0]
    await castLiveTransport(api, 'Fictional TV', { profile: true, automatic: true, adaptive: true }).start('channel-1', 'auto', 'request-1')
    expect(POST.mock.calls[0][1].body).toMatchObject({ adaptive: true, quality: 'auto', profile: { segmentFormats: ['fmp4', 'mpegts'] } })
    await castLiveTransport(api, 'Fictional TV', { profile: true, automatic: true, adaptive: true }).start('channel-1', '720', 'request-2')
    expect(POST.mock.calls[1][1].body).not.toHaveProperty('adaptive')
    expect(POST.mock.calls[1][1].body.profile.segmentFormats).toEqual(['mpegts'])
  })

  it('maps an unsupported Automatic choice to the old fixed request', async () => {
    const POST = vi.fn().mockResolvedValue({ data: { id: 'session-1' } })
    const api = { POST } as unknown as Parameters<typeof castLiveTransport>[0]
    await castLiveTransport(api, 'Fictional TV').start('channel-1', 'auto', 'request-1')
    expect(POST.mock.calls[0][1].body).toEqual({ channelId: 'channel-1', quality: '1080', requestId: 'request-1', clientName: 'Fictional TV' })
  })
})

describe('liveInfo', () => {
  const now = Date.parse('2026-11-03T01:30:00Z')
  it('names the channel and what is on', () => {
    const info = liveInfo(channel(), 'c1', '720', undefined, undefined, 300, now)
    expect(info).toMatchObject({ channelId: 'c1', number: '4.1', name: 'Station 4', quality: '720', paused: false, pauseLeftSeconds: null, reason: null })
    expect(info.now).toEqual({ title: 'The Sleuth Files', episodeTitle: 'The Low Tide', start: '2026-11-03T01:00:00Z', end: '2026-11-03T02:00:00Z' })
    expect(info.next?.title).toBe('Harbor Weather')
    expect(info.next?.episodeTitle).toBeUndefined()
  })
  it('counts down a pause to when the tuner goes back', () => {
    expect(liveInfo(channel(), 'c1', '1080', undefined, now - 90_500, 300, now).pauseLeftSeconds).toBe(210)
    expect(liveInfo(channel(), 'c1', '1080', undefined, now - 400_000, 300, now).pauseLeftSeconds).toBe(0)
  })
  it('carries the session’s reason, and works before the channel list arrives', () => {
    const info = liveInfo(undefined, 'c9', '1080', { reason: 'tuners_busy' } as LiveSession, undefined, 300, now)
    expect(info).toMatchObject({ channelId: 'c9', number: '', name: '', now: null, next: null, reason: 'tuners_busy' })
  })
})

describe('nowEnds', () => {
  it('is when Now ends, or nothing without listings', () => {
    expect(nowEnds(channel())).toBe(Date.parse('2026-11-03T02:00:00Z'))
    expect(nowEnds(channel({ now: undefined }))).toBeUndefined()
    expect(nowEnds(undefined)).toBeUndefined()
  })
})

describe('receiverFailure', () => {
  it('words the board’s failures and says where to act', () => {
    expect(receiverFailure('tuners_busy', '7.1')).toEqual({
      title: 'All tuners are in use',
      body: '7.1 can’t start until a tuner is free.',
      small: 'Try again, or choose another channel, from your phone or browser.',
    })
    expect(receiverFailure('no_signal', '7.1').title).toBe('No signal on 7.1')
    expect(receiverFailure('tuner_unavailable', '7.1').title).toBe('The tuner isn’t answering')
    expect(receiverFailure('not_allowed', '7.1')).toMatchObject({ title: 'Live TV isn’t available to you', small: '' })
  })
  it('falls back to the web player’s words', () => {
    expect(receiverFailure('pause_expired', '7.1')).toMatchObject({ title: 'Your tuner was released', small: 'Try again from your phone or browser.' })
    expect(receiverFailure('', '')).toMatchObject({ title: 'Live TV could not start' })
    expect(receiverFailure('recording', '7.1')).toEqual({ title: 'Stopped for a recording', body: 'This tuner is now recording for your household.', small: 'Try again from your phone or browser.' })
  })
})

describe('the pause countdown', () => {
  it('is the server’s when the session has one', () => {
    const now = Date.parse('2026-11-03T01:30:00Z')
    expect(liveInfo(channel(), 'c1', '1080', { pauseRemainingSeconds: 42 } as LiveSession, now - 1000, 300, now).pauseLeftSeconds).toBe(42)
  })
})

describe('refusedLiveInfo', () => {
  it('names the refused channel and why, so a sender can say so', () => {
    expect(refusedLiveInfo(channel(), 'c1', '720', 'tuners_busy')).toMatchObject({ channelId: 'c1', number: '4.1', name: 'Station 4', quality: '720', paused: false, pauseLeftSeconds: null, reason: 'tuners_busy' })
  })
  it('works without the channel list, and without a reason', () => {
    expect(refusedLiveInfo(undefined, 'c9', '1080', '')).toMatchObject({ channelId: 'c9', number: '', name: '', now: null, reason: null })
  })
})
