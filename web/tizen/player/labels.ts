import { knownLanguage, languageName, resolutionLabel } from '../../src/lib/format'
import { AUTO, QUALITIES, stepName } from '../../src/lib/quality'
import type { AudioTrack, Decision, OnlineSubtitle, SubtitleTrack } from './api'

// The player's words, as the Android TV app says them (core/model
// PlayerLabels.kt and PlaybackShared.kt), so the TVs read alike.

const SEP = ' · '

export const MODE_WORDS: Record<string, string> = { direct: 'Direct play', remux: 'Direct stream', audio: 'Audio transcode', transcode: 'Transcode' }

/** "Stereo", "5.1", "7.1"; nothing for mono and odd counts. */
export function channelsWords(channels?: number): string | undefined {
  return channels === 2 ? 'Stereo' : channels === 6 ? '5.1' : channels === 8 ? '7.1' : undefined
}

const CODECS: Record<string, string> = { aac: 'AAC', ac3: 'AC3', eac3: 'EAC3', truehd: 'TrueHD', dts: 'DTS', flac: 'FLAC', opus: 'Opus', mp3: 'MP3', pcm: 'PCM', h264: 'H.264', hevc: 'HEVC', av1: 'AV1', vp9: 'VP9' }
export const codecWords = (codec?: string) => codec ? CODECS[codec.toLowerCase()] ?? codec.toUpperCase() : undefined

/** "4K HDR", "1080p", "4K Dolby Vision": the video as the screen gets it. */
export function videoWords(video: Decision['video']): string | undefined {
  const size = resolutionLabel(video.width, video.height) || undefined
  const hdr = video.hdr === 'dolbyvision' ? 'Dolby Vision' : video.hdr && !video.toneMap ? 'HDR' : undefined
  return [size, hdr].filter(Boolean).join(' ') || undefined
}

/**
 * The pill at the top right: how the file comes, the video and, when the
 * audio goes through untouched, its format: "Direct play · 4K HDR · EAC3 5.1".
 * Automatic leads with "Auto", and once it went down a step says just that:
 * "Auto · 720p".
 */
export function streamPill(decision: Decision, quality: string, audioIndex?: number): string {
  const step = quality === AUTO ? decision.auto?.step : undefined
  if (step && step !== 'original') return `Auto${SEP}${stepName(step)}`
  const parts = [MODE_WORDS[decision.mode] ?? 'Playing']
  if (quality === AUTO) parts.unshift('Auto')
  const video = videoWords(decision.video)
  if (video) parts.push(video)
  else if (quality !== AUTO && quality !== 'original') parts.push(stepName(quality))
  if (decision.video.toneMap) parts.push('HDR to SDR')
  if (decision.mode === 'direct' || decision.mode === 'remux') {
    const track = decision.audioTracks.find((a) => a.index === (audioIndex ?? decision.audio.index))
    const audio = [codecWords(track?.codec ?? decision.audio.codec), channelsWords(track?.channels ?? decision.audio.channels)].filter(Boolean).join(' ')
    if (audio) parts.push(audio)
  }
  return parts.join(SEP)
}

export type OptionWords = { label: string; detail?: string }

/** An audio track: its language, with the format as the detail ("English", "EAC3 5.1 · Commentary"). */
export function audioWords(track: AudioTrack): OptionWords {
  const detail = [[codecWords(track.codec), channelsWords(track.channels)].filter(Boolean).join(' '), track.title].filter(Boolean).join(SEP)
  return { label: knownLanguage(track.language) ? languageName(track.language) : track.title || `Track ${track.index}`, detail: detail || undefined }
}

/** A subtitle track; an image one says picking it restarts the stream (PLAN-CLIENTS.md §2.3 item 7). */
export function subtitleWords(track: SubtitleTrack): OptionWords {
  const label = knownLanguage(track.language) ? languageName(track.language) : track.title || 'Unknown language'
  const detail = [track.source === 'opensubtitles' ? 'OpenSubtitles' : track.external ? 'External' : undefined,
    track.forced ? 'Forced' : undefined, track.hearingImpaired ? 'HI' : undefined, track.release,
    track.format === 'image' || track.burnInOnly ? 'burned in, restarts playback' : undefined,
    track.source !== 'opensubtitles' && track.title && knownLanguage(track.language) ? track.title : undefined].filter(Boolean).join(SEP)
  return { label, detail: detail || undefined }
}

/** A result to download: "English", "matches this file · HI · Some.Release.1080p". */
export function onlineWords(result: OnlineSubtitle): OptionWords {
  const detail = [result.hashMatch ? 'matches this file' : undefined, result.hearingImpaired ? 'HI' : undefined, result.forced ? 'Forced' : undefined,
    result.aiTranslated ? 'AI translated' : undefined, result.release].filter(Boolean).join(SEP)
  return { label: languageName(result.language), detail: detail || undefined }
}

/** The Next episode card's heading: "S03 E08 · Squall Line". */
export function episodeWords(next: { seasonNumber: number; episodeNumber: number; title: string }): string {
  const two = (n: number) => String(n).padStart(2, '0')
  return `S${two(next.seasonNumber)} E${two(next.episodeNumber)}${SEP}${next.title}`
}

const order = (id: string) => QUALITIES.findIndex((q) => q.id === id)

/** Automatic moved from was to now: lowered, or raised (back to the original says so). */
export function automaticStepNote(was: string | undefined, now: string): string {
  if (!was || order(now) > order(was)) return `Lowered to ${stepName(now)} for your connection.`
  return now === 'original' ? 'Connection recovered; playing the original file.' : `Raised to ${stepName(now)} for your connection.`
}

export const SLOW_CONNECTION_NOTE = 'Your connection is too slow to play this smoothly.'
export const STOPPED_BY_ADMIN = 'Playback was stopped by the server’s admin.'
export const SUBTITLE_FAILED_NOTE = 'Couldn’t load a subtitle. The video will keep playing.'
export const AUTOMATIC_SUBTITLE_FAILED = 'Couldn’t get an automatic subtitle. Video will keep playing; choose a subtitle in the player or try again later.'
