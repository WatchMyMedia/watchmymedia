import type { framework } from 'chromecast-caf-receiver'
import type { components } from '../src/api/schema'
import type { Profile } from '../src/lib/capabilities'
import { DOVI_CODECS, HDR_PROFILE_API_LEVEL, forApiLevel, hdrFields } from '../src/lib/hdr'

type Decision = components['schemas']['Decision']

/** The apiLevel from which a server serves audio as its own rendition. */
export const SEPARATE_AUDIO_LEVEL = 4

const AUDIO_CODECS: Record<string, string> = { ac3: 'ac-3', eac3: 'ec-3', opus: 'Opus', flac: 'fLaC', mp3: 'mp4a.40.34' }

/**
 * Shaka's codecs for an HLS playlist that names none (event playlists, and
 * VOD ones from servers before 0.4.2). Its own guess is H.264 and AAC, and
 * the SourceBuffer it makes for those rejects anything else, such as copied
 * HEVC. These come from the decision, at the levels castProfile asks about;
 * a playlist with CODECS overrides them. H.264 and AAC keep Shaka's guess.
 */
export function hlsCodecDefaults(d: Decision): { defaultVideoCodec?: string; defaultAudioCodec?: string } {
  const v = d.video
  const uhd = (v.height ?? 0) > 1080
  // Copied video keeps its depth; a transcode is 10-bit only when it keeps HDR.
  const tenBit = v.copy ? (v.bitDepth ?? 8) > 8 : !!v.hdr && !v.toneMap
  const video =
    v.codec === 'hevc' ? `hvc1.${tenBit ? '2.4' : '1.6'}.L${uhd ? 153 : 120}.B0`
    : v.codec === 'av1' ? `av01.0.${uhd ? '12' : '08'}M.${tenBit ? '10' : '08'}`
    : undefined
  const audio = AUDIO_CODECS[d.audio.codec ?? '']
  return { ...(video && { defaultVideoCodec: video }), ...(audio && { defaultAudioCodec: audio }) }
}

// Media types for castCaps: each codec alone, and muxed pairs as Shaka asks
// for them when audio and video share a stream, as ours do.
const CAPS_TYPES = [
  'video/mp4; codecs="avc1.42E01E"',
  'video/mp4; codecs="avc1.640028"',
  'video/mp4; codecs="hvc1.1.6.L120.B0"',
  'video/mp4; codecs="hev1.1.6.L120.B0"',
  'video/mp4; codecs="hvc1.1.6.L120.90"',
  'video/mp4; codecs="hev1.1.6.L120.90"',
  'video/mp4; codecs="hvc1.2.4.L120.B0"',
  'audio/mp4; codecs="mp4a.40.2"',
  'audio/mp4; codecs="ac-3"',
  'audio/mp4; codecs="ec-3"',
  'video/mp4; codecs="avc1.42E01E,mp4a.40.2"',
  'video/mp4; codecs="avc1.640028,mp4a.40.2"',
  'video/mp4; codecs="hvc1.1.6.L120.B0,mp4a.40.2"',
  'video/mp4; codecs="hev1.1.6.L120.B0,mp4a.40.2"',
  'video/mp4; codecs="hvc1.1.6.L120.B0,ec-3"',
  'video/mp4; codecs="avc1.640028,ec-3"',
  'video/mp4; codecs="avc1.640028,ac-3"',
  'video/mp4; codecs="ec-3"',
  'video/mp4; codecs="mp4a.40.2"',
  ...DOVI_CODECS.map(([, codec]) => `video/mp4; codecs="${codec}"`),
]

/** HEVC Main 10 at 4K, which the HDR questions are asked with. */
const HDR_CODEC = 'hvc1.2.4.L153.B0'

/**
 * What this TV says about each of CAPS_TYPES, to Media Source Extensions
 * (what Shaka plays HLS through) and to the receiver SDK, what it says about
 * HDR, and the profiles it sends: the `caps` command, for working out from
 * outside why a cast fails.
 */
export function castCaps(ctx: framework.CastReceiverContext) {
  const ask = <T>(f: () => T): T | null => {
    try {
      return f()
    } catch {
      return null
    }
  }
  const mse: Record<string, boolean | null> = {}
  const display: Record<string, boolean | null> = {}
  for (const t of CAPS_TYPES) {
    const [mime, codecs] = [t.split(';')[0], /codecs="([^"]*)"/.exec(t)?.[1]]
    mse[t] = ask(() => MediaSource.isTypeSupported(t))
    display[t] = ask(() => ctx.canDisplayType(mime, codecs))
  }
  const caps = ask(() => ctx.getDeviceCapabilities()) ?? {}
  const hdr = {
    isHdrSupported: caps.is_hdr_supported ?? null,
    isDvSupported: caps.is_dv_supported ?? null,
    pq: ask(() => ctx.canDisplayType('video/mp4; eotf=smpte2084', HDR_CODEC)),
    hlg: ask(() => ctx.canDisplayType('video/mp4; eotf=arib-std-b67', HDR_CODEC)),
  }
  return {
    profile: castProfile(ctx),
    separateProfile: castProfile(ctx, SEPARATE_AUDIO_LEVEL),
    hdrProfile: castProfile(ctx, HDR_PROFILE_API_LEVEL),
    mse,
    display,
    hdr,
    userAgent: navigator.userAgent,
  }
}

/**
 * What this Cast device can play (PLAN-CAST.md §4.3), from the receiver
 * SDK's own probes: a codec is listed when it decodes at 1080p, and the size
 * limit is 4K when every listed codec also decodes at 4K. AC-3 and E-AC-3
 * are listed only where the device decodes them or passes them through over
 * HDMI, and Media Source Extensions takes them the way the stream carries
 * them. With separateAudio (servers from apiLevel 4) the audio is its own
 * rendition, which the TVs tried so far take; muxed with video they do not,
 * so older servers send those TVs AAC. MKV is always remuxed; ASS becomes
 * WebVTT; image subtitles burn in. Servers from apiLevel 8 also get the HDR
 * the TV shows and the Dolby Vision keys (PLAN-HDR-PROFILE.md §6.3).
 */
export function castProfile(ctx: framework.CastReceiverContext, level = 0): Profile {
  const separateAudio = level >= SEPARATE_AUDIO_LEVEL
  // Shaka plays our HLS through Media Source Extensions, which can refuse
  // what the TV itself decodes: every TV here said yes to E-AC3 (HDMI
  // passthrough), got it copied next to HEVC in one fMP4 stream, and threw
  // NotSupportedError on the pair (CAF 905, Shaka 3015), while each alone in
  // a SourceBuffer of its own was fine. So an MP4 codec counts only when MSE
  // takes it as the stream will carry it: alone, or muxed with the other
  // half (video with AAC, audio with H.264).
  const mse = (type: string) => {
    try {
      return typeof MediaSource === 'undefined' || MediaSource.isTypeSupported(type)
    } catch {
      return false
    }
  }
  const muxed = (video: string, audio: string) => mse(`video/mp4; codecs="${video},${audio}"`)
  const display = (mime: string, codecs?: string, width?: number, height?: number, framerate?: number) => {
    try {
      return ctx.canDisplayType(mime, codecs, width, height, framerate)
    } catch {
      return false
    }
  }
  const can = (mime: string, codecs: string, width: number, height: number) =>
    display(mime, codecs, width, height, 30) &&
    (mime === 'video/mp4' && !separateAudio ? muxed(codecs, 'mp4a.40.2') : mse(`${mime}; codecs="${codecs}"`))
  const caps = ctx.getDeviceCapabilities() ?? {}
  // Codec strings at a 1080p level and a 4K level. HEVC is asked about with
  // the hvc1 tag the server writes: a TV can decode one tag and not the other.
  const videoTypes: [string, string, string, string][] = [
    ['h264', 'video/mp4', 'avc1.640028', 'avc1.640033'],
    ['hevc', 'video/mp4', 'hvc1.1.6.L120.B0', 'hvc1.1.6.L153.B0'],
    ['hevc10', 'video/mp4', 'hvc1.2.4.L120.B0', 'hvc1.2.4.L153.B0'],
    ['vp9', 'video/webm', 'vp9', 'vp9'],
    ['av1', 'video/mp4', 'av01.0.08M.08', 'av01.0.12M.08'],
  ]
  const videoCodecs: string[] = []
  let uhd = true
  for (const [name, mime, hd, uhdCodec] of videoTypes) {
    if (!can(mime, hd, 1920, 1080)) continue
    videoCodecs.push(name)
    if (!can(mime, uhdCodec, 3840, 2160)) uhd = false
  }
  if (!videoCodecs.includes('h264')) videoCodecs.unshift('h264') // every Cast device decodes it
  // [name, type for canDisplayType, codecs for it, the codec as muxed in fMP4]
  const audioTypes: [string, string, string, string][] = [
    ['aac', 'audio/mp4', 'mp4a.40.2', 'mp4a.40.2'],
    ['mp3', 'audio/mpeg', '', 'mp4a.40.34'],
    ['opus', 'audio/webm', 'opus', 'opus'],
    ['flac', 'audio/mp4', 'flac', 'flac'],
    ['ac3', 'audio/mp4', 'ac-3', 'ac-3'],
    ['eac3', 'audio/mp4', 'ec-3', 'ec-3'],
  ]
  // Muxed, Shaka can also ask for the audio alone under the stream's
  // video/mp4 type, which is the call the TVs refused: 'video/mp4;
  // codecs="ec-3"'. A rendition of its own is audio/mp4.
  const audioMse = (inMp4: string) =>
    separateAudio ? mse(`audio/mp4; codecs="${inMp4}"`) : muxed('avc1.640028', inMp4) && mse(`video/mp4; codecs="${inMp4}"`)
  const audioCodecs = audioTypes.filter(([, mime, codec, inMp4]) => display(mime, codec || undefined) && audioMse(inMp4)).map(([name]) => name)
  if (!audioCodecs.includes('aac')) audioCodecs.unshift('aac')
  const surround = audioCodecs.includes('ac3') || audioCodecs.includes('eac3')
  // CAF says whether the TV shows HDR and Dolby Vision, but not HLG. The
  // platform's canDisplayType reads an eotf parameter (Shaka asks it so for
  // PQ): HLG is asked as arib-std-b67, and the answer trusted only where the
  // TV also says yes to PQ that way; otherwise HLG goes with HDR10. A dovi
  // key needs the TV to show Dolby Vision and to decode the profile.
  const hdr10 = !!caps.is_hdr_supported
  const eotf = (name: string) => display(`video/mp4; eotf=${name}`, HDR_CODEC)
  const readsEotf = hdr10 && eotf('smpte2084')
  const screen = { hdr10, hlg: hdr10 && (!readsEotf || eotf('arib-std-b67')), dolbyVision: !!caps.is_dv_supported }
  const { hdrTypes, doviKeys } = hdrFields(screen, (codec) => can('video/mp4', codec, 1920, 1080))
  videoCodecs.push(...doviKeys)
  return forApiLevel({
    name: 'cast',
    containers: videoCodecs.includes('vp9') ? ['mp4', 'webm'] : ['mp4'],
    videoCodecs,
    audioCodecs,
    maxWidth: uhd ? 3840 : 1920,
    maxHeight: uhd ? 2160 : 1080,
    maxBitrate: 0,
    maxAudioChannels: surround ? 8 : 2,
    hdr: hdr10,
    hdrTypes,
    subtitleFormats: ['webvtt'],
    ...(separateAudio && { separateAudio: true }),
  }, level)
}
