import type { LiveProfile } from '../../../src/api/liveTV'
import { compatibleLiveProfile } from '../../../src/lib/liveTV'
import type { DeviceProfiles } from '../../player'
import type { Profile } from '../../player/api'

/**
 * What a 2024 or later LG TV (webOS 24, Chromium 108) plays, in the form the
 * server's playback request takes. Conservative on purpose, as Samsung's
 * was before its first TV session: each claim is still to be confirmed on
 * an LG TV, and one that fails there comes out rather than being worked
 * around.
 *
 * - Direct play of MP4 and MKV, which LG lists for its video element.
 *   MPEG-TS, WebM and AVI are left to a remux.
 * - H.264 and HEVC at 8 and 10 bits, up to 4K. No AV1, VP9 or MPEG-2
 *   claimed yet, and no Dolby Vision at all.
 * - AAC, AC-3 and E-AC-3, up to 5.1. Surround is claimed only as Dolby, so
 *   the server turns lossless and DTS sound into E-AC-3 5.1 rather than
 *   stereo. No DTS: some LG TVs take it only when a receiver does.
 * - HLS fMP4, which every remux and transcode is, played through hls.js.
 * - HDR10 when the TV says it shows it (hdr10). Otherwise hdrTypes stays
 *   empty and the server tone-maps HDR to SDR. Never HDR10+.
 * - Text subtitles as WebVTT, drawn by the engine; image ones burned in.
 */
export function lgProfile(options: { hdr10?: boolean } = {}): Profile {
  const hdr10 = !!options.hdr10
  return {
    name: 'lg-tv',
    containers: ['mp4', 'mkv'],
    videoCodecs: ['h264', 'hevc', 'hevc10'],
    audioCodecs: ['aac', 'ac3', 'eac3'],
    maxWidth: 3840,
    maxHeight: 2160,
    maxBitrate: 0,
    maxAudioChannels: 6,
    hdr: hdr10,
    hdrTypes: hdr10 ? ['hdr10'] : [],
    subtitleFormats: ['webvtt'],
  }
}

/**
 * The profile a stream that failed to decode asks with, once a viewing: the
 * stereo H.264 and AAC in MP4 every LG TV plays.
 */
export function lgFallbackProfile(): Profile {
  return { ...lgProfile(), name: 'lg-tv-fallback', containers: ['mp4'], videoCodecs: ['h264'], audioCodecs: ['aac'], maxWidth: 1920, maxHeight: 1080, maxAudioChannels: 2 }
}

/** Live TV: the web's conservative H.264 and AAC in MPEG-TS at up to 1080p, which hls.js plays. */
export const lgLiveProfile: LiveProfile = compatibleLiveProfile

/** What the engine asks the server with, on an LG TV. */
export const lgProfiles: DeviceProfiles = {
  file: (options) => lgProfile(options),
  fallback: lgFallbackProfile,
  live: lgLiveProfile,
}
