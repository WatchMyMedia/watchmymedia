import type { Decision, Profile } from './types'

export const PROFILES = [
  { id: 'safe', label: 'H.264 + AAC', description: 'Conservative · unsupported formats convert on the server' },
  { id: 'hevc', label: 'HEVC experiment', description: 'Copy HEVC video; convert unsupported audio' },
  { id: 'surround', label: 'HEVC + E-AC-3 experiment', description: 'Test surround decoding with this TV and audio output' },
  { id: 'transcode', label: 'Force 480p conversion', description: 'Exercise the server’s HLS transcoder' },
] as const
export type ProfileId = typeof PROFILES[number]['id']

/** These are explicit experiments, not claims of certified QN90D capability. */
export function prototypeProfile(id: ProfileId): Profile {
  return {
    name: `tizen-prototype-${id}`,
    containers: id === 'transcode' ? [] : ['mp4'],
    videoCodecs: id === 'safe' || id === 'transcode' ? ['h264'] : ['h264', 'hevc'],
    audioCodecs: id === 'surround' ? ['aac', 'ac3', 'eac3'] : ['aac'],
    maxWidth: id === 'transcode' ? 854 : 3840,
    maxHeight: id === 'transcode' ? 480 : 2160,
    maxBitrate: id === 'transcode' ? 2_000_000 : 0,
    maxAudioChannels: id === 'surround' ? 6 : 2,
    hdr: false,
    subtitleFormats: ['webvtt'],
  }
}

/** Whether the stream the server sends is larger than 1080p. */
export function uhd(decision: Pick<Decision, 'video'>): boolean {
  return (decision.video.width ?? 0) > 1920 || (decision.video.height ?? 0) > 1080
}

export function filePosition(decision: Pick<Decision, 'protocol' | 'playlist' | 'startMs'>, playerMs: number): number {
  return Math.max(0, Math.round(playerMs + (decision.protocol === 'hls' && decision.playlist !== 'vod' ? decision.startMs : 0)))
}

export function playerPosition(decision: Pick<Decision, 'protocol' | 'playlist' | 'startMs'>, fileMs: number): number {
  return Math.max(0, Math.round(fileMs - (decision.protocol === 'hls' && decision.playlist !== 'vod' ? decision.startMs : 0)))
}
