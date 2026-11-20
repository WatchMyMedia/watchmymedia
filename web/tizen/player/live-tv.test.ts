import createClient from 'openapi-fetch'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { paths } from '../../src/api/schema'
import type { LiveCapabilities, LiveChannel, LiveSession } from './api'
import type { Clock } from './clock'
import { FakePlayer } from './fake-player'
import { LivePlayback, channelNumberHint, editChannelNumber, exactChannel } from './live-tv'

const HOUR = 3_600_000
const channel = (id: string, number: string, name: string, available = true): LiveChannel => ({ id, number, name, available, enabled: true, sourceId: 'hdhr' })
const channels = [channel('c1', '2.1', 'KTVU'), channel('c2', '4.1', 'KRON'), channel('c3', '5.1', 'Dark', false), channel('c4', '7.1', 'KGO'), channel('c5', '7.10', 'Other')]
type Sent = { method: string; path: string; body?: Record<string, unknown>; query: string }

function harness(o: { caps?: Partial<LiveCapabilities>; start?: (body: Record<string, unknown>, n: number) => Response | Promise<Response> | undefined; view?: (s: LiveSession) => LiveSession } = {}) {
  vi.useFakeTimers({ now: 20 * HOUR })
  const sent: Sent[] = []
  const sessions = new Map<string, LiveSession>()
  let starts = 0
  const view = (s: LiveSession) => o.view?.(s) ?? s
  const fetcher = async (request: Request) => {
    const url = new URL(request.url)
    const path = url.pathname
    const text = request.method === 'POST' || request.method === 'PUT' ? await request.text() : ''
    const body = text ? JSON.parse(text) as Record<string, unknown> : undefined
    sent.push({ method: request.method, path, body, query: url.search })
    if (path === '/api/live-tv/capabilities') return Response.json({ allowed: true, enabled: true, playback: true, guide: false, bufferSeconds: 60, ...o.caps })
    if (path === '/api/live-tv/channels') return Response.json({ items: channels })
    if (path === '/api/auth/media-token') return Response.json({ token: `live-${sent.length}`, param: 'access_token', expiresAt: new Date(Date.now() + 4 * HOUR).toISOString() })
    if (path === '/api/live-tv/sessions' && request.method === 'POST') {
      const n = ++starts
      const custom = await o.start?.(body!, n)
      if (custom) return custom
      const id = `live-${n}`
      const s: LiveSession = { id, channel: channels.find((c) => c.id === body!.channelId)!, state: 'starting', url: `/api/live-tv/sessions/${id}/live.m3u8`, bufferBytes: 0, bufferSeconds: 60,
        clientName: String(body!.clientName), lastSeenAt: new Date().toISOString(), pauseRemainingSeconds: 300, paused: false, quality: String(body!.quality), startedAt: new Date().toISOString(), userId: 1, userName: 'viewer' }
      sessions.set(id, s)
      return Response.json(view(s))
    }
    const id = /^\/api\/live-tv\/sessions\/([^/]+)$/.exec(path)?.[1]
    if (id) {
      const s = sessions.get(id)
      if (!s) return Response.json({ detail: 'not found' }, { status: 404 })
      if (request.method === 'DELETE') { sessions.delete(id); return new Response(null, { status: 204 }) }
      const next = { ...s, state: 'playing', lastSeenAt: new Date().toISOString(), ...(request.method === 'PUT' ? { paused: !!body!.paused } : {}) }
      sessions.set(id, next)
      return Response.json(view(next))
    }
    throw new Error(`Unexpected ${request.method} ${path}`)
  }
  const api = createClient<paths>({ baseUrl: 'https://tv.example', fetch: fetcher as typeof fetch })
  const player = new FakePlayer()
  const clock: Clock = { now: () => Date.now(), wall: () => Date.now(), setTimeout: (f, ms) => setTimeout(f, ms), clearTimeout: (h) => clearTimeout(h as never), setInterval: (f, ms) => setInterval(f, ms), clearInterval: (h) => clearInterval(h as never) }
  let ids = 0
  const saved: string[] = []
  const live = new LivePlayback({ api, player, clock, clientName: 'Living room TV', mediaUrl: (path, token) => `https://tv.example${path}?access_token=${token}`,
    requestId: () => `request-${++ids}`, quality: () => '1080', saveQuality: (q) => saved.push(q) })
  return {
    live, player, sent, sessions, saved,
    startsSent: () => sent.filter((r) => r.method === 'POST' && r.path === '/api/live-tv/sessions'),
    deletes: () => sent.filter((r) => r.method === 'DELETE').map((r) => r.path.split('/').pop()),
    beats: () => sent.filter((r) => r.method === 'PUT'),
    run: async (ms: number) => { for (let t = 0; t < ms; t += 1000) { player.advance(1000); await vi.advanceTimersByTimeAsync(1000) } },
    settle: () => vi.advanceTimersByTimeAsync(0),
  }
}

async function tuned(h: ReturnType<typeof harness>, id = 'c1') {
  await h.live.open(id)
  await h.run(1000)
}

afterEach(() => vi.useRealTimers())

describe('tuning', () => {
  it('starts a session with the TV’s profile, waits for it to play, and plays its playlist with a media token', async () => {
    const h = harness()
    await tuned(h)
    expect(h.startsSent()[0].body).toEqual({ channelId: 'c1', requestId: 'request-1', clientName: 'Living room TV', quality: '1080',
      profile: { segmentFormats: ['mpegts'], videoCodecs: ['h264'], audioCodecs: ['aac'], interlaced: false, maxHeight: 1080 } })
    expect(h.sent.find((r) => r.method === 'GET' && r.path === '/api/live-tv/sessions/live-1')?.query).toBe('?waitMs=5000')
    expect(h.player.opened).toEqual([{ url: expect.stringMatching(/^https:\/\/tv.example\/api\/live-tv\/sessions\/live-1\/live.m3u8\?access_token=live-/), options: { live: true } }])
    expect(h.live.snapshot()).toMatchObject({ phase: 'playing', channel: { id: 'c1' }, paused: false, quality: '1080', qualities: ['1080', '720', '480', '360'] })
  })

  it('goes up and down the list in its order, past unavailable channels, giving the tuner back before the next', async () => {
    const h = harness()
    await tuned(h, 'c2')
    h.live.channelUp(); await h.run(1000)
    expect(h.live.snapshot().channel?.id).toBe('c4')
    expect(h.deletes()).toEqual(['live-1'])
    expect(h.startsSent().map((r) => r.body?.channelId)).toEqual(['c2', 'c4'])
    h.live.channelDown(); await h.run(1000)
    expect(h.live.snapshot().channel?.id).toBe('c2')
    expect(h.startsSent().map((r) => r.body?.requestId)).toEqual(['request-1', 'request-2', 'request-3'])
  })

  it('goes back to the previous channel, and back again', async () => {
    const h = harness()
    await tuned(h, 'c1')
    expect(h.live.snapshot().previousChannelId).toBeUndefined()
    h.live.channelUp(); await h.run(1000)
    expect(h.live.snapshot().previousChannelId).toBe('c1')
    h.live.previousChannel(); await h.run(1000)
    expect(h.live.snapshot()).toMatchObject({ channel: { id: 'c1' }, previousChannelId: 'c2' })
  })

  it('collects a typed number and tunes it only on OK, never a partial one', async () => {
    const h = harness()
    await tuned(h)
    h.live.typeDigit('7'); h.live.typeDigit('.')
    expect(h.live.snapshot().number).toEqual({ digits: '7.', match: undefined, hint: 'Keep entering the full channel number.' })
    expect(h.live.commitNumber()).toBe(false)
    h.live.typeDigit('1')
    expect(h.live.snapshot().number).toMatchObject({ digits: '7.1', match: { id: 'c4' }, hint: '7.1 · KGO' })
    expect(h.startsSent()).toHaveLength(1)
    expect(h.live.commitNumber()).toBe(true)
    await h.run(1000)
    expect(h.live.snapshot()).toMatchObject({ channel: { id: 'c4' }, number: undefined })
  })

  it('keeps playing when the channel asked for is unavailable, and says so', async () => {
    const h = harness()
    await tuned(h)
    h.live.typeDigit('5'); h.live.typeDigit('.'); h.live.typeDigit('1')
    expect(h.live.snapshot().number?.match).toBeUndefined()
    await h.live.open('c3')
    expect(h.live.snapshot()).toMatchObject({ phase: 'playing', channel: { id: 'c1' }, note: '5.1 isn’t available right now.' })
  })
})

describe('refusals and failures', () => {
  it('words a refusal by its reason code alone', async () => {
    const h = harness({ start: () => Response.json({ detail: 'tuners_busy' }, { status: 503 }) })
    await tuned(h)
    expect(h.live.snapshot()).toMatchObject({ phase: 'error', failure: { reason: 'tuners_busy', title: 'All tuners are in use' } })
    expect(h.player.opened).toEqual([])
  })

  it('never shows the server’s sentence', async () => {
    const h = harness({ start: () => Response.json({ detail: 'Tuner at http://192.168.1.50 refused' }, { status: 400 }) })
    await tuned(h)
    expect(JSON.stringify(h.live.snapshot().failure)).not.toContain('192.168')
    expect(h.live.snapshot().failure?.title).toBe('Live TV could not start')
  })

  it('retries a start whose reply was lost with the same request ID', async () => {
    const h = harness({ start: (_body, n) => { if (n === 1) throw new TypeError('Failed to fetch') ; return undefined } })
    await tuned(h)
    expect(h.startsSent().map((r) => r.body?.requestId)).toEqual(['request-1', 'request-1'])
    expect(h.live.snapshot().phase).toBe('playing')
  })

  it('says Live TV isn’t available when the account may not watch', async () => {
    const h = harness({ caps: { allowed: false } })
    await tuned(h)
    expect(h.live.snapshot()).toMatchObject({ phase: 'error', failure: { title: 'Live TV isn’t available to you' } })
    expect(h.startsSent()).toEqual([])
  })

  it('opens a dropped stream again twice, with a new token after a refusal, then asks the server and stops', async () => {
    const h = harness()
    await tuned(h)
    h.player.emit({ state: 'error', failure: { code: 'X', kind: 'auth', httpStatus: 401 } }); await h.settle()
    h.player.emit({ state: 'error', failure: { code: 'X', kind: 'network' } }); await h.settle()
    expect(h.player.opened).toHaveLength(3)
    expect(h.sent.filter((r) => r.path === '/api/auth/media-token')).toHaveLength(2)
    h.player.emit({ state: 'error', failure: { code: 'X', kind: 'network' } }); await h.settle(); await h.settle()
    expect(h.player.opened).toHaveLength(3)
    expect(h.deletes()).toEqual(['live-1'])
    expect(h.live.snapshot()).toMatchObject({ phase: 'ended', failure: { reason: 'encoder_failed' } })
  })

  it('offers the recording that took the tuner', async () => {
    let n = 0
    const h = harness({ view: (s) => ++n > 2 ? { ...s, state: 'stopped', reason: 'recording', takeoverRecordingId: 'r9', takeoverTitle: 'Evening News' } : s })
    await tuned(h)
    await h.run(10_000)
    expect(h.live.snapshot()).toMatchObject({ phase: 'ended', failure: { reason: 'recording', title: 'Stopped for a recording', recordingId: 'r9', recordingTitle: 'Evening News' } })
    expect(h.player.calls.at(-1)).toBe('stop')
  })
})

describe('pausing and Go live', () => {
  it('pauses within the buffer, tells the server, and resumes at live once the pause outlasted it', async () => {
    const h = harness()
    await tuned(h)
    h.player.window = { startMs: 0, endMs: 60_000 }
    h.live.pause(); await h.settle()
    expect(h.beats().at(-1)?.body).toMatchObject({ paused: true, interacted: true })
    expect(h.live.snapshot()).toMatchObject({ paused: true, behind: true })
    await h.run(30_000)
    h.live.resume()
    expect(h.player.calls).not.toContain('live-edge')
    h.live.pause(); await h.run(61_000)
    h.live.resume()
    expect(h.player.calls.at(-2)).toBe('live-edge')
    expect(h.live.snapshot()).toMatchObject({ paused: false, note: 'Your pause passed the buffer. Resuming at live.' })
  })

  it('goes live from behind', async () => {
    const h = harness()
    await tuned(h)
    h.player.window = { startMs: 0, endMs: 60_000 }
    h.player.positionMs = 20_000
    await h.run(1000)
    expect(h.live.snapshot().behind).toBe(true)
    h.live.goLive(); await h.run(1000)
    expect(h.player.calls).toContain('live-edge')
    expect(h.live.snapshot()).toMatchObject({ behind: false, note: 'Playing live.' })
  })
})

describe('Still watching and leaving', () => {
  it('asks after four hours without a key, and gives the tuner back a minute later', async () => {
    const h = harness()
    await tuned(h)
    await vi.advanceTimersByTimeAsync(4 * HOUR - 5000)
    expect(h.live.snapshot().askStillWatching).toBe(false)
    await h.run(6000)
    expect(h.live.snapshot().askStillWatching).toBe(true)
    await h.run(61_000)
    expect(h.deletes()).toEqual(['live-1'])
    expect(h.live.snapshot()).toMatchObject({ phase: 'ended', askStillWatching: false, failure: { reason: 'inactive', title: 'Still watching?' } })
  }, 30_000)

  it('keeps watching on Yes, saying the viewer is there', async () => {
    const h = harness()
    await tuned(h)
    await vi.advanceTimersByTimeAsync(4 * HOUR + 1000)
    expect(h.live.snapshot().askStillWatching).toBe(true)
    h.live.keepWatching(); await h.settle()
    expect(h.beats().at(-1)?.body).toMatchObject({ interacted: true })
    await h.run(120_000)
    expect(h.live.snapshot()).toMatchObject({ phase: 'playing', askStillWatching: false })
    expect(h.deletes()).toEqual([])
  }, 30_000)

  it('gives the tuner back on stop, and in the background, tuning again on return', async () => {
    const h = harness()
    await tuned(h)
    h.live.suspend(); await h.settle()
    expect(h.deletes()).toEqual(['live-1'])
    expect(h.player.calls.at(-1)).toBe('stop')
    h.live.restore(); await h.run(1000)
    expect(h.live.snapshot()).toMatchObject({ phase: 'playing', channel: { id: 'c1' } })
    await h.live.stop()
    expect(h.deletes()).toEqual(['live-1', 'live-2'])
    expect(h.live.snapshot().phase).toBe('idle')
  })
})

describe('Automatic', () => {
  it('plays a prepared output from its first frame, and goes back when it fails', async () => {
    let n = 0
    const directive = { generation: 2, url: '/api/live-tv/sessions/live-1/g2.m3u8', from: '1080', to: '720', mode: 'video', modeReason: '', reason: '' }
    const h = harness({ caps: { automatic: true }, view: (s) => ({ ...s, automatic: true, actualQuality: '1080', ...(++n >= 3 ? { directive } : {}) }) })
    h.live.setQuality('auto')
    await tuned(h)
    expect(h.startsSent()[0].body).toMatchObject({ quality: 'auto', autoRecovery: true })
    await h.run(10_000)
    expect(h.player.lastUrl).toContain('/g2.m3u8')
    await h.run(10_000)
    expect(h.beats().some((b) => b.body?.switchGeneration === 2)).toBe(true)
    expect(h.live.snapshot().autoStep).toBe('1080p')
  })

  it('reports a failed output and reopens the one before', async () => {
    let n = 0
    const directive = { generation: 3, url: '/api/live-tv/sessions/live-1/g3.m3u8', from: '1080', to: '720', mode: 'video', modeReason: '', reason: '' }
    const h = harness({ caps: { automatic: true }, view: (s) => ({ ...s, automatic: true, ...(++n === 3 ? { directive } : {}) }) })
    h.live.setQuality('auto')
    await tuned(h)
    h.player.autoPlay = false
    await h.run(10_000)
    expect(h.player.lastUrl).toContain('/g3.m3u8')
    h.player.emit({ state: 'error', failure: { code: 'X', kind: 'network' } })
    await h.settle()
    expect(h.player.lastUrl).toContain('/live.m3u8')
    expect(h.beats().at(-1)?.body).toMatchObject({ switchFailedGeneration: 3 })
  })
})

describe('channel numbers', () => {
  it('edits a bounded number with one dot', () => {
    expect(editChannelNumber('', '.')).toBe('')
    expect(editChannelNumber('7', '.')).toBe('7.')
    expect(editChannelNumber('7.', '.')).toBe('7.')
    expect(editChannelNumber('7.1', '\b')).toBe('7.')
    expect(editChannelNumber('1234567890', '1')).toBe('1234567890')
    expect(editChannelNumber('7', 'x')).toBe('7')
  })

  it('names a match, a clash, a prefix and a miss', () => {
    expect(exactChannel(channels, '7.1')?.id).toBe('c4')
    expect(exactChannel(channels, '5.1')).toBeUndefined()
    expect(channelNumberHint(channels, '')).toBe('Enter the channel number, including its dot.')
    expect(channelNumberHint([...channels, channel('c6', '7.1', 'Twin')], '7.1')).toBe('More than one channel uses 7.1. Choose one from Channels.')
    expect(channelNumberHint(channels, '9')).toBe('No channel 9. Check the number and try again.')
  })
})
