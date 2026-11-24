import type { components } from '../../../src/api/schema'
import { formatBytes, formatRuntime, knownLanguage, languageName, resolutionLabel } from '../../../src/lib/format'
import { firstTrailer, type PlayableTrailer } from '../../../src/lib/trailers'
import type { PlayRequest } from '../../app/routes'
import { codecWords } from '../../player'

// What the movie page says,
// worded as the Android TV app words it (core/model MediaLabels.kt,
// TrackLabels.kt, PlaybackGateway.kt and tv/detail/MovieLabels.kt).

type MovieDetail = components['schemas']['MovieDetail']
type MediaFileDetail = components['schemas']['MediaFileDetail']
type PlaybackPlan = components['schemas']['PlaybackPlan']
type Credit = components['schemas']['Credit']

const SEP = ' · '

/** The versions a movie can play: its files on disk, the default first, as the server lists them. */
export function movieVersions(m: Pick<MovieDetail, 'files'>): MediaFileDetail[] {
  return m.files.filter((f) => f.status === 'present')
}

/** Partway through: a saved position past the start and short of the end. */
export function partway(m: Pick<MovieDetail, 'watch'>): { positionMs: number; durationMs: number; fileId?: number } | undefined {
  const w = m.watch
  return w && !w.completed && w.positionMs > 0 && w.durationMs > 0 ? w : undefined
}

/** How far through, 0–1, for the bar beside the buttons. */
export function progressOf(m: Pick<MovieDetail, 'watch'>): number | undefined {
  const p = partway(m)
  return p ? Math.min(1, p.positionMs / p.durationMs) : undefined
}

/** "1 h 12 min left", "8 min left": what is left of a partway movie, at least a minute. */
export function remaining(ms: number): string {
  const min = Math.max(1, Math.round(ms / 60_000))
  const h = Math.floor(min / 60)
  const m = min % 60
  return `${h > 0 ? `${h} h${m ? ` ${m} min` : ''}` : `${m} min`} left`
}

/** The first button: "Resume · 1 h 12 min left", or "Play". */
export function mainLabel(m: Pick<MovieDetail, 'watch'>): string {
  const p = partway(m)
  return p ? `Resume${SEP}${remaining(p.durationMs - p.positionMs)}` : 'Play'
}

/** "2026", "2 h 14 min", "Thriller, Mystery", "R", "★ 7.6": the facts under the title. */
export function movieFacts(m: Pick<MovieDetail, 'year' | 'runtimeMin' | 'genres' | 'contentRating' | 'rating'>): string[] {
  return [
    m.year ? String(m.year) : '',
    formatRuntime(m.runtimeMin ?? 0),
    m.genres.slice(0, 2).join(', '),
    m.contentRating ?? '',
    m.rating ? `★ ${m.rating.toFixed(1)}` : '',
  ].filter(Boolean)
}

const HDR: Record<string, string> = { hdr10: 'HDR10', hlg: 'HLG', dolbyvision: 'Dolby Vision' }

/** "Dolby Vision", "HDR10", "HLG"; undefined for SDR. */
export function hdrWords(hdr: string | undefined): string | undefined {
  return hdr ? HDR[hdr] ?? hdr.toUpperCase() : undefined
}

/** "7.1", "5.1", "2.0": from ffprobe's layout ("5.1(side)", "stereo"), else the channel count. */
export function channelsText(channels: number | undefined, layout?: string): string | undefined {
  const l = layout?.replace(/\(.*\)$/, '').toLowerCase()
  if (l && /^\d\.\d$/.test(l)) return l
  if (l === 'stereo') return '2.0'
  if (l === 'mono') return '1.0'
  switch (channels) {
    case undefined: case 0: return undefined
    case 1: return '1.0'
    case 2: return '2.0'
    case 3: return '2.1'
    case 6: return '5.1'
    case 7: return '6.1'
    case 8: return '7.1'
    default: return `${channels} ch`
  }
}

/** The default video and audio streams of a version, from its probe. */
function streams(f: MediaFileDetail) {
  const video = f.probe?.video.find((v) => v.default) ?? f.probe?.video[0]
  const audio = f.probe?.audio.find((a) => a.default) ?? f.probe?.audio[0]
  return { video, audio }
}

/** "4K", "Dolby Vision", "7.1": the facts line's tags, from the movie and its default version. */
export function movieTags(m: Pick<MovieDetail, 'quality' | 'hdr' | 'files'>): string[] {
  const v = movieVersions(m)[0]
  const { audio } = v ? streams(v) : { audio: undefined }
  return [m.quality ?? '', (v && hdrWords(v.hdr)) ?? (m.hdr ? 'HDR' : ''), channelsText(audio?.channels, audio?.channelLayout) ?? ''].filter(Boolean)
}

/** A version's heading: "4K Dolby Vision", "1080p"; its file type, or "Version", when neither is known. */
export function versionTitle(f: MediaFileDetail): string {
  const { video } = streams(f)
  const size = resolutionLabel(f.width ?? video?.width, f.height ?? video?.height)
  return [size, hdrWords(f.hdr ?? video?.hdr)].filter(Boolean).join(' ') || fileExtension(f.relativePath) || 'Version'
}

/** "MKV" from a file's name. */
function fileExtension(path: string): string {
  const ext = path.split('.').pop() ?? ''
  return ext.length > 0 && ext.length <= 5 && ext !== path ? ext.toUpperCase() : ''
}

/** "Dead.Reckoning.Bay.2026.2160p.DV.mkv": a file's name without its folders. */
export function fileName(relativePath: string): string {
  return relativePath.split(/[\\/]/).pop() ?? relativePath
}

/** A version's tags: "MKV", "HEVC", "Dolby Vision · P8", "TrueHD 7.1", "48.2 GB". */
export function versionTags(f: MediaFileDetail): string[] {
  const { video, audio } = streams(f)
  const hdr = hdrWords(f.hdr ?? video?.hdr)
  const dv = f.dvProfile ?? video?.dvProfile
  const audioTag = audio ? [codecWords(audio.codec), channelsText(audio.channels, audio.channelLayout)].filter(Boolean).join(' ') : codecWords(f.audioCodec)
  return [
    fileExtension(f.relativePath),
    codecWords(f.videoCodec ?? video?.codec),
    hdr && dv && hdr === 'Dolby Vision' ? `${hdr}${SEP}P${dv}` : hdr,
    audioTag,
    f.size ? formatBytes(f.size) : undefined,
  ].filter((t): t is string => !!t)
}

/** One line of a version's Tracks: "Audio" or "Subtitles" on the first of each kind. */
export type TrackLine = { kind?: 'Audio' | 'Subtitles'; text: string; default?: boolean; burnIn?: boolean }

const SUBTITLE_FORMATS: Record<string, string> = {
  subrip: 'SRT', srt: 'SRT', ass: 'ASS', ssa: 'ASS', webvtt: 'WebVTT', vtt: 'WebVTT', mov_text: 'TX3G',
  hdmv_pgs_subtitle: 'PGS', pgssub: 'PGS', pgs: 'PGS', dvd_subtitle: 'VobSub', dvdsub: 'VobSub', dvb_subtitle: 'DVB', dvbsub: 'DVB',
}

/** ffprobe's subtitle codec as people know it: "subrip" → "SRT", "hdmv_pgs_subtitle" → "PGS". */
export function subtitleFormat(codec: string): string {
  return SUBTITLE_FORMATS[codec.toLowerCase()] ?? codec.toUpperCase()
}

/**
 * A version's audio and subtitle tracks, a few of each with "+ 2 more" after:
 * "English · TrueHD 7.1" (Default), "Commentary · AC3 2.0", "English SDH ·
 * SRT", "English · PGS" (burn-in required). Without a probe, the file's
 * audio codec; nothing when there is none.
 */
export function versionTracks(f: MediaFileDetail, perKind = 3): TrackLine[] {
  const lines: TrackLine[] = []
  const audio = f.probe?.audio ?? []
  if (audio.length === 0) {
    const codec = codecWords(f.audioCodec)
    if (codec) lines.push({ kind: 'Audio', text: codec, default: true })
  }
  audio.slice(0, perKind).forEach((a, i) => {
    // A titled track reads by its title ("Commentary"), others by their language.
    const lang = knownLanguage(a.language) ? languageName(a.language) : undefined
    const name = a.title?.trim() || lang || 'Unknown language'
    lines.push({ kind: i === 0 ? 'Audio' : undefined, text: [name, [codecWords(a.codec), channelsText(a.channels, a.channelLayout)].filter(Boolean).join(' ')].filter(Boolean).join(SEP), default: a.default })
  })
  if (audio.length > perKind) lines.push({ text: `+ ${audio.length - perKind} more` })
  const subs = f.probe?.subtitles ?? []
  subs.slice(0, perKind).forEach((s, i) => {
    const lang = knownLanguage(s.language) ? languageName(s.language) : undefined
    const name = [lang ?? s.title ?? 'Unknown language', lang && s.title && s.title !== lang ? s.title : ''].filter(Boolean).join(' ')
    lines.push({ kind: i === 0 ? 'Subtitles' : undefined, text: [s.forced ? `${name} (Forced)` : name, subtitleFormat(s.codec)].join(SEP), burnIn: s.image })
  })
  if (subs.length > perKind) lines.push({ text: `+ ${subs.length - perKind} more` })
  return lines
}

/**
 * How a version would play on this TV, after its file name: "direct play",
 * "remux to fMP4, audio to EAC3 on this TV", "transcode to 1080p H.264 on
 * this TV". Undefined when the server couldn't plan it.
 */
export function planLine(plan: PlaybackPlan | undefined): string | undefined {
  if (!plan) return undefined
  if (plan.mode === 'direct') return 'direct play'
  const audio = plan.audio.copy ? undefined : ['audio to', codecWords(plan.audio.codec) ?? 'AAC', plan.audio.channels === 6 ? '5.1' : plan.audio.channels === 8 ? '7.1' : undefined].filter(Boolean).join(' ')
  const first = plan.mode === 'transcode'
    ? ['transcode to', resolutionLabel(plan.video.width, plan.video.height) || undefined, plan.video.copy ? undefined : codecWords(plan.video.codec)].filter(Boolean).join(' ')
    : 'remux to fMP4'
  return `${[first, audio].filter(Boolean).join(', ')} on this TV`
}

/** The line under a version's tags: its file name and how it would play. */
export function versionLine(f: MediaFileDetail, plan: PlaybackPlan | undefined): string {
  return [fileName(f.relativePath), planLine(plan)].filter(Boolean).join(SEP)
}

/** A cast member's name for screen readers: "Ida Markham as Nell Kerr". */
export function castLabel(c: Credit): string {
  return c.character ? `${c.name} as ${c.character}` : c.name
}

/** What the player route gets for a movie's version: [start] 0 plays from the start; none resumes where the server says. */
export function moviePlay(m: Pick<MovieDetail, 'title' | 'year' | 'backdropPath'>, file: Pick<MediaFileDetail, 'id'>, start?: 0): PlayRequest {
  return { kind: 'file', fileId: file.id, startMs: start, title: m.title, subtitle: m.year ? String(m.year) : undefined, backdropPath: m.backdropPath }
}

/**
 * Resume plays the version the saved position is on, else the default;
 * the server resumes 5 s before where the viewer stopped,
 * so the request carries no start.
 */
export function resumeVersion(m: Pick<MovieDetail, 'files' | 'watch'>): MediaFileDetail | undefined {
  const versions = movieVersions(m)
  const at = partway(m)?.fileId
  return versions.find((v) => v.id === at) ?? versions[0]
}

/** The Trailer button's request: a file plays in the player, a YouTube trailer in the trailer screen. Undefined hides the button. */
export function trailerPlay(m: Pick<MovieDetail, 'id' | 'title' | 'trailers'>, trailer: PlayableTrailer | null = firstTrailer(m.trailers)): PlayRequest | undefined {
  if (!trailer) return undefined
  return trailer.kind === 'file'
    ? { kind: 'trailer', movieId: m.id, fileId: trailer.fileId, title: m.title }
    : { kind: 'trailer', movieId: m.id, youtubeKey: trailer.key, title: m.title }
}
