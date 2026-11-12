import type { components } from '../api/schema'
import { DOVI_CODECS, hdrFields } from './hdr'

export type Profile = components['schemas']['Profile']

const mse = (type: string) => typeof MediaSource !== 'undefined' && MediaSource.isTypeSupported(type)

function native(type: string): boolean {
  const v = document.createElement('video')
  return v.canPlayType(type) !== ''
}

const HD = { width: 1920, height: 1080, bitrate: 8_000_000 }
const UHD = { width: 3840, height: 2160, bitrate: 40_000_000 }

async function decodable(type: string, size: { width: number; height: number; bitrate: number } = HD): Promise<boolean> {
  if (!mse(type)) return false
  if (!navigator.mediaCapabilities?.decodingInfo) return true
  try {
    const info = await navigator.mediaCapabilities.decodingInfo({
      type: 'media-source',
      video: { contentType: type, framerate: 24, ...size },
    })
    return info.supported
  } catch {
    return true
  }
}

/**
 * Whether the screen shows HDR, as the browser says. A TV's browser may say
 * it only of its video plane.
 */
function hdrScreen(): boolean {
  return typeof window.matchMedia === 'function' && window.matchMedia('(dynamic-range: high), (video-dynamic-range: high)').matches
}

/**
 * Whether the browser shows video with this transfer function (PQ for
 * HDR10, or HLG), asked with a 10-bit codec it decodes. Chrome says no where
 * it has no HDR output (Linux) even on an HDR screen. A browser that does
 * not know the question says yes.
 */
async function showsTransfer(codec: string, transferFunction: 'pq' | 'hlg'): Promise<boolean> {
  if (!navigator.mediaCapabilities?.decodingInfo) return true
  try {
    const info = await navigator.mediaCapabilities.decodingInfo({
      type: 'media-source',
      video: { contentType: `video/mp4; codecs="${codec}"`, framerate: 24, ...HD, colorGamut: 'rec2020', transferFunction },
    })
    return info.supported
  } catch {
    return true
  }
}

/** What this browser can play, detected once and cached for the session. */
export async function detectProfile(): Promise<Profile> {
  const containers = ['mp4']
  if (native('video/webm; codecs="vp9, opus"') || mse('video/webm; codecs="vp9"')) containers.push('webm')

  // Each codec is listed if it decodes at 1080p. The size limit is 4K when
  // every listed codec also decodes at 4K; the browser scales the picture
  // down to the screen itself, so the screen size is no limit.
  // Codec strings at a 1080p level and a 4K level.
  const videoTypes: [string, string, string][] = [
    ['h264', 'avc1.640028', 'avc1.640033'],
    ['hevc', 'hvc1.1.6.L120.B0', 'hvc1.1.6.L153.B0'],
    ['hevc10', 'hvc1.2.4.L120.B0', 'hvc1.2.4.L153.B0'],
    ['av1', 'av01.0.08M.08', 'av01.0.12M.08'],
  ]
  const videoCodecs: string[] = []
  let uhd = true
  for (const [name, hd, uhdCodec] of videoTypes) {
    if (!(await decodable(`video/mp4; codecs="${hd}"`))) continue
    videoCodecs.push(name)
    if (!(await decodable(`video/mp4; codecs="${uhdCodec}"`, UHD))) uhd = false
  }
  if (mse('video/webm; codecs="vp9"') || mse('video/mp4; codecs="vp09.00.10.08"')) videoCodecs.push('vp9')

  // HDR as PLAN-HDR-PROFILE.md §6.3 says: the kinds the screen shows, and a
  // dovi key where the browser decodes that profile and the screen shows
  // HDR. The browser maps Dolby Vision to the screen itself, so a decoder
  // and an HDR screen are taken as a screen that shows it.
  const hdr = hdrScreen()
  const tenBit = [['hevc10', 'hvc1.2.4.L120.B0'], ['av1', 'av01.0.08M.10']].find(([name]) => videoCodecs.includes(name))?.[1]
  const dovi = new Set<string>()
  if (hdr) for (const [, codec] of DOVI_CODECS) if (await decodable(`video/mp4; codecs="${codec}"`)) dovi.add(codec)
  const screen = {
    hdr10: hdr && (!tenBit || (await showsTransfer(tenBit, 'pq'))),
    hlg: hdr && (!tenBit || (await showsTransfer(tenBit, 'hlg'))),
    dolbyVision: dovi.size > 0,
  }
  const { hdrTypes, doviKeys } = hdrFields(screen, (codec) => dovi.has(codec))
  videoCodecs.push(...doviKeys)

  const audioCodecs: string[] = []
  const audioTypes: [string, string][] = [
    ['aac', 'audio/mp4; codecs="mp4a.40.2"'],
    ['mp3', 'audio/mp4; codecs="mp4a.69"'],
    ['opus', 'audio/mp4; codecs="opus"'],
    ['flac', 'audio/mp4; codecs="flac"'],
    ['ac3', 'audio/mp4; codecs="ac-3"'],
    ['eac3', 'audio/mp4; codecs="ec-3"'],
  ]
  for (const [name, type] of audioTypes) {
    if (mse(type) || native(type)) audioCodecs.push(name)
  }
  if (native('audio/webm; codecs="vorbis"')) audioCodecs.push('vorbis')
  if (!audioCodecs.includes('mp3') && native('audio/mpeg')) audioCodecs.push('mp3')

  return {
    name: 'web',
    containers,
    videoCodecs,
    audioCodecs,
    maxWidth: uhd ? UHD.width : HD.width,
    maxHeight: uhd ? UHD.height : HD.height,
    maxBitrate: 0,
    maxAudioChannels: 0,
    hdr,
    hdrTypes,
    subtitleFormats: ['webvtt', 'ass'],
  }
}

/**
 * What Safari's own HLS player (and so an Apple TV over AirPlay) plays, for
 * the native path in lib/airplay.ts: MP4 only (MKV remuxes), WebVTT only
 * (ASS is converted, so subtitles reach the TV as renditions).
 */
export function detectNativeProfile(): Profile {
  const videoTypes: [string, string][] = [
    ['h264', 'avc1.640028'],
    ['hevc', 'hvc1.1.6.L120.B0'],
    ['hevc10', 'hvc1.2.4.L120.B0'],
    ['av1', 'av01.0.08M.08'],
  ]
  const videoCodecs = videoTypes.filter(([, c]) => native(`video/mp4; codecs="${c}"`)).map(([n]) => n)
  if (!videoCodecs.includes('h264')) videoCodecs.unshift('h264')
  // AVPlayer shows HDR10, HLG and, where it decodes it, Dolby Vision.
  const hdr = hdrScreen()
  const decodes = (codec: string) => native(`video/mp4; codecs="${codec}"`)
  const { hdrTypes, doviKeys } = hdrFields({ hdr10: hdr, hlg: hdr, dolbyVision: hdr && DOVI_CODECS.some(([, c]) => decodes(c)) }, decodes)
  videoCodecs.push(...doviKeys)
  const audioTypes: [string, string][] = [
    ['aac', 'audio/mp4; codecs="mp4a.40.2"'],
    ['mp3', 'audio/mpeg'],
    ['ac3', 'audio/mp4; codecs="ac-3"'],
    ['eac3', 'audio/mp4; codecs="ec-3"'],
    ['flac', 'audio/mp4; codecs="flac"'],
    ['alac', 'audio/mp4; codecs="alac"'],
  ]
  const audioCodecs = audioTypes.filter(([, t]) => native(t)).map(([n]) => n)
  // Every Apple device that decodes HEVC in hardware decodes it at 4K.
  const uhd = videoCodecs.includes('hevc')
  return {
    name: 'web-native',
    containers: ['mp4'],
    videoCodecs,
    audioCodecs,
    maxWidth: uhd ? UHD.width : HD.width,
    maxHeight: uhd ? UHD.height : HD.height,
    maxBitrate: 0,
    maxAudioChannels: 0,
    hdr,
    hdrTypes,
    subtitleFormats: ['webvtt'],
  }
}

let cached: Promise<Profile> | null = null
export function profile(native = false): Promise<Profile> {
  if (native) return Promise.resolve(detectNativeProfile())
  cached ??= detectProfile()
  return cached
}
