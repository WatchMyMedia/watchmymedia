import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { LoadData } from './protocol'

// castChannel against a stand-in for Chrome's Cast sender SDK: the order of
// what it does matters (the household's tuners are few), and so does the
// LOAD it sends. The built-in browser finds no Cast devices to try it on.

const calls: string[] = []
const loads: { contentId: string; contentType: string; streamType: string; customData: LoadData; credentials?: string; credentialsType?: string; title?: string }[] = []
let loadResult: unknown = undefined

vi.mock('../api/client', () => ({
  api: {
    POST: vi.fn(async (path: string) => { calls.push(`POST ${path}`); return { data: { token: 'c1.token', expiresAt: '2026-11-04T00:00:00Z', urls: ['https://wmm.example'] }, response: new Response() } }),
    GET: vi.fn(async (path: string) => { calls.push(`GET ${path}`); return { data: { id: 'server-1', apiLevel: 14 }, response: new Response() } }),
  },
  unwrap: <T,>(res: { data: T }) => res.data,
}))

function fakeSdk() {
  class MediaInfo { metadata: unknown; customData: unknown; streamType = ''; constructor(public contentId: string, public contentType: string) {} }
  class GenericMediaMetadata { title = ''; subtitle?: string }
  class LoadRequest { autoplay = false; customData: unknown; constructor(public media: MediaInfo) {} }
  const session = {
    getCastDevice: () => ({ friendlyName: 'Living Room TV' }),
    sendMessage: vi.fn(async () => undefined),
    loadMedia: vi.fn(async (req: LoadRequest & { credentials?: string; credentialsType?: string }) => {
      calls.push('loadMedia')
      const meta = req.media.metadata as GenericMediaMetadata
      loads.push({ contentId: req.media.contentId, contentType: req.media.contentType, streamType: req.media.streamType, customData: req.customData as LoadData, credentials: req.credentials, credentialsType: req.credentialsType, title: meta.title })
      return loadResult
    }),
  }
  const castNs = { framework: { CastContext: { getInstance: () => ({ getCurrentSession: () => session }) } } }
  const chromeNs = { cast: { media: { MediaInfo, GenericMediaMetadata, LoadRequest, StreamType: { LIVE: 'LIVE', BUFFERED: 'BUFFERED' } } } }
  vi.stubGlobal('cast', castNs)
  vi.stubGlobal('chrome', chromeNs)
  vi.stubGlobal('window', { cast: castNs, isSecureContext: true, setTimeout, clearTimeout })
  vi.stubGlobal('location', { origin: 'https://home.example', hostname: 'home.example' })
  return session
}

beforeEach(() => {
  calls.length = 0
  loads.length = 0
  loadResult = undefined
  vi.resetModules()
})
afterEach(() => { vi.unstubAllGlobals() })

describe('file subtitle handover', () => {
  it('keeps explicit Off instead of asking the TV to apply defaults again', async () => {
    fakeSdk()
    const sender = await import('./sender')
    // Off must replace an automatic handover even when neither the paused
    // position nor its empty subtitle track changes.
    sender.noteLocalPlay({ fileId: 7, positionMs: 4000, subtitleMode: 'auto' })
    sender.noteLocalPlay({ fileId: 7, positionMs: 4000, subtitleMode: 'off' })
    const handover = sender.takeHandover(7)
    await sender.castFile({ fileId: 7, subtitleMode: handover?.subtitleMode })
    expect(loads[0].customData).toMatchObject({ fileId: 7, subtitleId: null, subtitleMode: 'off' })
  })

  it('asks for preferences on a fresh file and preserves a manually selected track', async () => {
    fakeSdk()
    const sender = await import('./sender')
    await sender.castFile({ fileId: 7 })
    expect(loads[0].customData.subtitleMode).toBe('auto')
    await sender.castFile({ fileId: 7, subtitleId: 's2' })
    expect(loads[1].customData).toMatchObject({ subtitleId: 's2', subtitleMode: 'off' })
  })

  it('does not freeze an automatic track during handover and later audio changes', async () => {
    fakeSdk()
    const sender = await import('./sender')
    await sender.castFile({ fileId: 7, audioIndex: 1, subtitleMode: 'auto', subtitleId: 's1' })
    expect(loads[0].customData).toMatchObject({ audioIndex: 1, subtitleId: null, subtitleMode: 'auto' })
  })
})

describe('castChannel', () => {
  it('stops the session here before the TV asks for a tuner, then loads the channel as LIVE', async () => {
    fakeSdk()
    const sender = await import('./sender')
    const stopLocal = vi.fn(async () => { calls.push('stopLocal') })
    await sender.castChannel({ channelId: 'ch4', quality: '720', title: '4.1 NFLD-HD', stopLocal })
    expect(calls.indexOf('stopLocal')).toBe(0)
    expect(calls.indexOf('loadMedia')).toBe(calls.length - 1)
    expect(loads).toHaveLength(1)
    const load = loads[0]
    expect(load).toMatchObject({ contentId: 'wmm:live:ch4', contentType: 'application/x-mpegurl', streamType: 'LIVE', credentials: 'c1.token', credentialsType: 'wmm-cast', title: '4.1 NFLD-HD' })
    expect(load.customData).toMatchObject({ v: 1, server: { id: 'server-1', urls: ['https://wmm.example', 'https://home.example'] }, token: 'c1.token', live: { channelId: 'ch4', quality: '720' }, device: { name: 'Living Room TV' } })
    expect(load.customData.fileId).toBeUndefined()
    // The remote now drives this channel; it is on its way until the TV's state says it is on it.
    expect(sender.castSnapshot()).toMatchObject({ liveTarget: 'ch4', castingChannelId: 'ch4', error: undefined })
  })

  it('reports a refusal and asks for nothing more', async () => {
    fakeSdk()
    loadResult = 'load_failed'
    const sender = await import('./sender')
    await expect(sender.castChannel({ channelId: 'ch4', quality: '1080' })).rejects.toThrow('The TV refused to play (load_failed)')
    expect(sender.castSnapshot()).toMatchObject({ liveTarget: 'ch4', castingChannelId: undefined, error: 'The TV refused to play (load_failed)' })
  })

  it('doesn’t load when the session here can’t give its tuner back', async () => {
    fakeSdk()
    const sender = await import('./sender')
    await expect(sender.castChannel({ channelId: 'ch4', quality: '1080', stopLocal: async () => { throw new Error('The tuner could not be released.') } })).rejects.toThrow()
    expect(calls).not.toContain('loadMedia')
  })

  it('changes channel and steps with the protocol’s commands, showing the ask as tuning', async () => {
    const session = fakeSdk()
    const sender = await import('./sender')
    sender.switchCastChannel('ch7')
    expect(session.sendMessage).toHaveBeenLastCalledWith('urn:x-cast:media.watchmy.cast', { type: 'channel', channelId: 'ch7' })
    expect(sender.castSnapshot().castingChannelId).toBe('ch7')
    sender.stepCastChannel(-1, 'ch2')
    expect(session.sendMessage).toHaveBeenLastCalledWith('urn:x-cast:media.watchmy.cast', { type: 'channelStep', by: -1 })
    expect(sender.castSnapshot().castingChannelId).toBe('ch2')
  })
})
