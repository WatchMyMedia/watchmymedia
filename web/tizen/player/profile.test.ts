import { describe, expect, it } from 'vitest'
import { applyQuality, qualityById } from '../../src/lib/quality'
import { compatibleLiveProfile } from '../../src/lib/liveTV'
import { fallbackProfile, samsungLiveProfile, samsungProfile, uhd } from './profile'

describe('the Samsung profile', () => {
  it('claims only what the QN90D session is to confirm', () => {
    const p = samsungProfile()
    expect(p.containers).toEqual(['mp4', 'mkv'])
    expect(p.videoCodecs).toEqual(['h264', 'hevc', 'hevc10'])
    expect(p.videoCodecs?.some((c) => c.startsWith('dovi'))).toBe(false)
    expect(p.audioCodecs).toEqual(['aac', 'ac3', 'eac3'])
    expect(p.maxAudioChannels).toBe(6)
    expect(p).toMatchObject({ maxWidth: 3840, maxHeight: 2160, maxBitrate: 0, subtitleFormats: ['webvtt'] })
    expect(p.selectsAudioTrack).toBeUndefined()
  })

  it('lists HDR10 only once it is proven, and otherwise sends an empty hdrTypes so HDR is tone-mapped', () => {
    expect(samsungProfile()).toMatchObject({ hdr: false, hdrTypes: [] })
    expect(samsungProfile({ hdr10: true })).toMatchObject({ hdr: true, hdrTypes: ['hdr10'] })
  })

  it('caps the profile at a picked step and leaves Automatic uncapped', () => {
    expect(applyQuality(samsungProfile(), qualityById('720', 32))).toMatchObject({ maxWidth: 1280, maxHeight: 720, maxBitrate: 4_000_000 })
    expect(applyQuality(samsungProfile(), qualityById('auto', 32))).toMatchObject({ maxWidth: 3840, maxHeight: 2160, maxBitrate: 0 })
  })

  it('falls back to stereo H.264 and AAC in MP4', () => {
    expect(fallbackProfile()).toMatchObject({ containers: ['mp4'], videoCodecs: ['h264'], audioCodecs: ['aac'], maxAudioChannels: 2, maxHeight: 1080, hdrTypes: [] })
  })

  it('plays Live TV with the web’s conservative live profile', () => {
    expect(samsungLiveProfile).toEqual(compatibleLiveProfile)
  })

  it('asks for 4K mode only for streams larger than 1080p', () => {
    expect(uhd({ video: { copy: true, toneMap: false, width: 3840, height: 2160 } })).toBe(true)
    expect(uhd({ video: { copy: true, toneMap: false, width: 1920, height: 1080 } })).toBe(false)
    expect(uhd({ video: { copy: false, toneMap: false, width: 854, height: 480 } })).toBe(false)
    expect(uhd({ video: { copy: true, toneMap: false } })).toBe(false)
  })
})
