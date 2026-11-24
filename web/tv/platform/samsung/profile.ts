import type { LiveProfile } from '../../../src/api/liveTV'
import { compatibleLiveProfile } from '../../../src/lib/liveTV'
import type { DeviceProfiles } from '../../player'
import type { Profile } from '../../player/api'

/**
 * What a 2024 QN90D-class Samsung TV (Tizen 8, AVPlay) plays, in the form
 * the server's playback request takes. Conservative on purpose: each claim
 * below is still to be confirmed in the QN90D session,
 * and one that fails there comes out rather than being worked around.
 *
 * - Direct play of MP4 and MKV. MPEG-TS, WebM and AVI are left to a remux.
 * - H.264 and HEVC at 8 and 10 bits, up to 4K (AVPlay's 4K mode, see uhd).
 *   No AV1, VP9 or MPEG-2 claimed yet, and no Dolby Vision at all.
 * - AAC, AC-3 and E-AC-3, up to 5.1. A track the TV can't take is converted
 *   by the server to E-AC-3 or AC-3 where these are listed, else AAC.
 * - HLS fMP4, which every remux and transcode is.
 * - HDR10 when the caller says the TV shows it (hdr10: the screens ask
 *   avinfo, tizen.ts tvTakesHdr10). The QN90D played an HDR10 HEVC
 *   file directly in HDR on 2026-11-20. Otherwise hdrTypes stays empty and
 *   the server tone-maps HDR to SDR.
 * - Text subtitles as WebVTT, drawn by the engine; image ones burned in.
 * - selectsAudioTrack is not claimed: another audio track of a direct-play
 *   file comes as a remux until AVPlay's track switching is proven.
 */
export function samsungProfile(options: { hdr10?: boolean } = {}): Profile {
  const hdr10 = !!options.hdr10
  return {
    name: 'samsung-tv',
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
 * stereo H.264 and AAC in MP4 every TV of the class plays, so the server
 * converts what the first claim got wrong.
 */
export function fallbackProfile(): Profile {
  return { ...samsungProfile(), name: 'samsung-tv-fallback', containers: ['mp4'], videoCodecs: ['h264'], audioCodecs: ['aac'], maxWidth: 1920, maxHeight: 1080, maxAudioChannels: 2 }
}

/**
 * Live TV: the web's conservative H.264 and AAC in MPEG-TS at up to 1080p.
 * The tuner's MPEG-2 video and AC-3 audio, which the TV's own broadcast
 * decoder plays, are the next claim to try on the QN90D.
 */
export const samsungLiveProfile: LiveProfile = compatibleLiveProfile

/** What the engine asks the server with, on a Samsung TV. */
export const samsungProfiles: DeviceProfiles = {
  file: (options) => samsungProfile(options),
  fallback: fallbackProfile,
  live: samsungLiveProfile,
}
