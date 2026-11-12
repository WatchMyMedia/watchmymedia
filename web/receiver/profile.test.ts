import type { framework } from 'chromecast-caf-receiver'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { components } from '../src/api/schema'
import { castProfile, hlsCodecDefaults } from './profile'

// What the Google TV Streamer and the Samsung QN90D said on 2026-10-30: every
// codec decodes, and MSE takes AC-3 and E-AC-3 only in an audio SourceBuffer
// of their own, not muxed with video or under video/mp4.
const tv = { canDisplayType: () => true, getDeviceCapabilities: () => ({ is_hdr_supported: true }) } as unknown as framework.CastReceiverContext
const tvMse = (type: string) => !/(ec-3|ac-3)/.test(type) || type.startsWith('audio/mp4')

describe('castProfile', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('leaves out AC-3 and E-AC-3 when they would be muxed with the video', () => {
    vi.stubGlobal('MediaSource', { isTypeSupported: tvMse })
    const p = castProfile(tv)
    expect(p.audioCodecs).not.toContain('eac3')
    expect(p.audioCodecs).not.toContain('ac3')
    expect(p.maxAudioChannels).toBe(2)
    expect(p.videoCodecs).toContain('hevc')
    expect(p.separateAudio).toBeUndefined()
  })

  it('lists them for a separate audio rendition, with surround', () => {
    vi.stubGlobal('MediaSource', { isTypeSupported: tvMse })
    const p = castProfile(tv, 4)
    expect(p.audioCodecs).toEqual(expect.arrayContaining(['aac', 'ac3', 'eac3']))
    expect(p.maxAudioChannels).toBe(8)
    expect(p.videoCodecs).toEqual(expect.arrayContaining(['h264', 'hevc', 'hevc10']))
    expect(p.separateAudio).toBe(true)
  })
})

// A TV as CAF and its platform could describe it (PLAN-HDR-PROFILE.md §6.3):
// what it shows, whether canDisplayType reads eotf, and the Dolby Vision
// profiles MSE takes.
function hdrTv(o: { hdr: boolean; dv?: boolean; eotf?: 'none' | 'pq' | 'both'; dovi?: string[] }) {
  const ctx = {
    canDisplayType: (mime: string) => {
      if (mime.includes('eotf=smpte2084')) return o.eotf === 'pq' || o.eotf === 'both'
      if (mime.includes('eotf=arib-std-b67')) return o.eotf === 'both'
      return true
    },
    getDeviceCapabilities: () => ({ is_hdr_supported: o.hdr, is_dv_supported: !!o.dv }),
  } as unknown as framework.CastReceiverContext
  vi.stubGlobal('MediaSource', { isTypeSupported: (type: string) => !/dvh1/.test(type) || (o.dovi ?? []).some((c) => type.includes(c)) })
  return ctx
}

describe('castProfile HDR', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('sends the HDR the TV shows and its Dolby Vision keys to servers from level 8', () => {
    const p = castProfile(hdrTv({ hdr: true, dv: true, eotf: 'both', dovi: ['dvh1.05', 'dvh1.08'] }), 8)
    expect(p.hdrTypes).toEqual(['hdr10', 'hlg', 'dovi'])
    expect(p.videoCodecs).toEqual(expect.arrayContaining(['hevc10', 'dovi5', 'dovi8']))
    expect(p.hdr).toBe(true)
    expect(p.separateAudio).toBe(true)
  })

  it('leaves out a Dolby Vision profile the TV does not decode, and every key on a screen without Dolby Vision', () => {
    expect(castProfile(hdrTv({ hdr: true, dv: true, eotf: 'both', dovi: ['dvh1.08'] }), 8).videoCodecs).not.toContain('dovi5')
    const hdr10 = castProfile(hdrTv({ hdr: true, dv: false, eotf: 'both', dovi: ['dvh1.05', 'dvh1.08'] }), 8)
    expect(hdr10.hdrTypes).toEqual(['hdr10', 'hlg'])
    expect(hdr10.videoCodecs?.filter((c) => c.startsWith('dovi'))).toEqual([])
  })

  it('reads HLG from canDisplayType only where it understands eotf', () => {
    expect(castProfile(hdrTv({ hdr: true, eotf: 'pq' }), 8).hdrTypes).toEqual(['hdr10'])
    expect(castProfile(hdrTv({ hdr: true, eotf: 'none' }), 8).hdrTypes).toEqual(['hdr10', 'hlg'])
  })

  it('says an SDR TV shows none, and still offers 10-bit HEVC', () => {
    const p = castProfile(hdrTv({ hdr: false, dv: false, eotf: 'none' }), 8)
    expect(p.hdrTypes).toEqual([])
    expect(p.hdr).toBe(false)
    expect(p.videoCodecs).toContain('hevc10')
  })

  it('sends older servers the profile they know, without hdrTypes or dovi keys', () => {
    const tv = hdrTv({ hdr: true, dv: true, eotf: 'both', dovi: ['dvh1.05', 'dvh1.08'] })
    for (const level of [0, 4, 7]) {
      const p = castProfile(tv, level)
      expect(p).not.toHaveProperty('hdrTypes')
      expect(p.videoCodecs?.some((c) => c.startsWith('dovi'))).toBe(false)
      expect(p.hdr).toBe(true)
    }
    expect(castProfile(tv, 7).separateAudio).toBe(true)
  })
})

type Decision = components['schemas']['Decision']
const decision = (video: Partial<Decision['video']>, audio: Partial<Decision['audio']>) =>
  ({ video: { copy: true, toneMap: false, ...video }, audio: { index: 1, copy: true, ...audio } }) as Decision

describe('hlsCodecDefaults', () => {
  it('names copied HEVC and converted audio, as in the cast that failed', () => {
    expect(hlsCodecDefaults(decision({ codec: 'hevc', height: 1080, bitDepth: 8 }, { codec: 'aac', copy: false }))).toEqual({ defaultVideoCodec: 'hvc1.1.6.L120.B0' })
  })
  it('uses Main 10 and the 4K level where the video needs them', () => {
    expect(hlsCodecDefaults(decision({ codec: 'hevc', height: 2160, bitDepth: 10 }, { codec: 'eac3' }))).toEqual({ defaultVideoCodec: 'hvc1.2.4.L153.B0', defaultAudioCodec: 'ec-3' })
    expect(hlsCodecDefaults(decision({ codec: 'hevc', copy: false, height: 2160, hdr: 'hdr10' }, { codec: 'aac', copy: false })).defaultVideoCodec).toBe('hvc1.2.4.L153.B0')
    expect(hlsCodecDefaults(decision({ codec: 'hevc', copy: false, height: 1080, hdr: 'hdr10', toneMap: true }, { codec: 'aac' })).defaultVideoCodec).toBe('hvc1.1.6.L120.B0')
    expect(hlsCodecDefaults(decision({ codec: 'av1', height: 1080, bitDepth: 10 }, { codec: 'opus' }))).toEqual({ defaultVideoCodec: 'av01.0.08M.10', defaultAudioCodec: 'Opus' })
  })
  it("keeps Shaka's own guess for H.264 and AAC", () => {
    expect(hlsCodecDefaults(decision({ codec: 'h264', height: 1080 }, { codec: 'aac' }))).toEqual({})
  })
})
