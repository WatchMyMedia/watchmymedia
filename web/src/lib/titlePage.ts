import type { components } from '../api/schema'
import { fileType, formatBytes, formatDate, knownLanguage, languageName } from './format'
import { minutesWords } from './endTime'

// The words a movie's or a show's page says about its files, its tracks and
// who watched it: what a viewer wants first ("4K HDR", "English · 5.1",
// "Plays on this browser without converting"), with the codecs one step away
// in File details.

type MediaInfo = components['schemas']['MediaInfo']
type AudioStream = components['schemas']['AudioStream']
type SubtitleStream = components['schemas']['SubtitleStream']
type PlaybackPlan = components['schemas']['PlaybackPlan']
type Season = components['schemas']['Season']
type Episode = components['schemas']['Episode']
type HistoryRow = components['schemas']['ActivityHistoryRow']

/** The parts of a file these words read. */
export type FileFacts = {
  height?: number
  width?: number
  hdr?: string
  dvProfile?: number
  videoCodec?: string
  audioCodec?: string
  relativePath: string
  size: number
  durationMs?: number
  probe?: Pick<MediaInfo, 'audio' | 'subtitles' | 'bitRate'>
}

const CODECS: Record<string, string> = {
  h264: 'H.264',
  hevc: 'HEVC',
  h265: 'HEVC',
  av1: 'AV1',
  vp9: 'VP9',
  mpeg2video: 'MPEG-2',
  mpeg4: 'MPEG-4',
  aac: 'AAC',
  ac3: 'AC3',
  eac3: 'EAC3',
  dts: 'DTS',
  truehd: 'TrueHD',
  flac: 'FLAC',
  opus: 'Opus',
  mp3: 'MP3',
  vorbis: 'Vorbis',
  subrip: 'SubRip',
  srt: 'SubRip',
  ass: 'ASS',
  ssa: 'SSA',
  webvtt: 'WebVTT',
  mov_text: 'Timed text',
  hdmv_pgs_subtitle: 'PGS',
  dvd_subtitle: 'VobSub',
  dvb_subtitle: 'DVB',
}

/** "H.264", "EAC3", "PGS": a codec as people see it written. */
export function codecName(codec?: string): string {
  if (!codec) return ''
  return CODECS[codec.toLowerCase()] ?? codec.toUpperCase()
}

/** "mono", "stereo", "5.1", "7.1", or "6 channels". */
export function channelWords(channels?: number, layout?: string): string {
  const named = layout?.replace(/\(.*\)$/, '')
  if (named && /^\d\.\d$/.test(named)) return named
  switch (channels) {
    case undefined:
    case 0:
      return named === 'stereo' || named === 'mono' ? named : ''
    case 1:
      return 'mono'
    case 2:
      return 'stereo'
    case 6:
      return '5.1'
    case 8:
      return '7.1'
    default:
      return `${channels} channels`
  }
}

function resolution(height?: number, width?: number): string {
  if (!height && !width) return ''
  if ((height ?? 0) >= 2000 || (width ?? 0) >= 3800) return '4K'
  if ((height ?? 0) >= 1000 || (width ?? 0) >= 1900) return '1080p'
  if ((height ?? 0) >= 700 || (width ?? 0) >= 1200) return '720p'
  return 'SD'
}

function hdrName(hdr?: string, dvProfile?: number, short = false): string {
  switch (hdr) {
    case 'dolbyvision':
      return short ? 'Dolby Vision' : dvProfile ? `DV P${dvProfile}` : 'Dolby Vision'
    case 'hdr10':
      return short ? 'HDR' : 'HDR10'
    case 'hlg':
      return short ? 'HDR' : 'HLG'
    default:
      return ''
  }
}

/** A version's name for a viewer: "4K HDR", "4K Dolby Vision", "1080p". */
export function versionName(f: Pick<FileFacts, 'height' | 'width' | 'hdr' | 'relativePath'>): string {
  return [resolution(f.height, f.width), hdrName(f.hdr, undefined, true)].filter(Boolean).join(' ') || fileType(f.relativePath) || 'Version'
}

/** File details' tags for a version: "2160p", "HEVC", "HDR10", "EAC3 5.1", "MKV". */
export function versionTags(f: FileFacts): string[] {
  const audio = f.probe?.audio.find((a) => a.default) ?? f.probe?.audio[0]
  const sound = [codecName(audio?.codec ?? f.audioCodec), channelWords(audio?.channels, audio?.channelLayout)].filter(Boolean).join(' ')
  return [f.height ? `${f.height}p` : '', codecName(f.videoCodec), hdrName(f.hdr, f.dvProfile), sound, fileType(f.relativePath)].filter(Boolean)
}

/** "21.4 GB · 24 Mb/s": a version's size and its average bitrate. */
export function sizeAndRate(f: Pick<FileFacts, 'size' | 'durationMs' | 'probe'>): string {
  const bits = f.probe?.bitRate || (f.durationMs ? (f.size * 8) / (f.durationMs / 1000) : 0)
  const rate = bits ? `${bits >= 10_000_000 ? Math.round(bits / 1_000_000) : (bits / 1_000_000).toFixed(1)} Mb/s` : ''
  return [f.size ? formatBytes(f.size) : '', rate].filter(Boolean).join(' · ')
}

/** A sound track to choose before playing. */
export type SoundOption = { index: number; label: string; detail: string; default: boolean }

/** "English · 5.1", "English · Commentary": the name a sound track is chosen by. */
export function soundLabel(a: Pick<AudioStream, 'language' | 'title' | 'channels' | 'channelLayout'>): string {
  const commentary = a.title && /comment/i.test(a.title) ? 'Commentary' : ''
  const layout = channelWords(a.channels, a.channelLayout)
  // An untagged track is named by its layout alone: "Stereo".
  if (!knownLanguage(a.language)) return commentary || (layout ? layout.charAt(0).toUpperCase() + layout.slice(1) : 'Sound')
  return [languageName(a.language), commentary || layout].filter(Boolean).join(' · ')
}

/** A file's sound tracks, the file's default first among equals, as the Sound choice lists them. */
export function soundOptions(probe?: Pick<MediaInfo, 'audio'>): SoundOption[] {
  const audio = probe?.audio ?? []
  const labels = audio.map(soundLabel)
  return audio.map((a, i) => ({
    index: a.index,
    // Two tracks that read alike are told apart by their place in the file.
    label: labels.filter((l) => l === labels[i]).length > 1 ? `${labels[i]} (track ${i + 1})` : labels[i],
    detail: [codecName(a.codec), channelWords(a.channels, a.channelLayout), a.bitRate ? `${Math.round(a.bitRate / 1000)} kb/s` : ''].filter(Boolean).join(' · '),
    default: !!a.default,
  }))
}

/** A subtitle track in a file to choose before playing; its id is the one the player asks the server for. */
export type SubtitleOption = { id: string; label: string; detail: string; image: boolean }

/** "English", "English · Forced", "Spanish · SDH". */
export function subtitleLabel(s: Pick<SubtitleStream, 'language' | 'title' | 'forced'>): string {
  const language = knownLanguage(s.language) ? languageName(s.language) : 'Unknown language'
  const extra = s.forced ? 'Forced' : s.title && s.title.length <= 24 && s.title.toLowerCase() !== language.toLowerCase() ? s.title : ''
  return [language, extra].filter(Boolean).join(' · ')
}

/** The subtitle tracks inside a file: text ones play as they are; a picture one is drawn into the video, which converts it. */
export function subtitleOptions(probe?: Pick<MediaInfo, 'subtitles'>): SubtitleOption[] {
  return (probe?.subtitles ?? []).map((s) => ({
    id: `s${s.index}`,
    label: subtitleLabel(s),
    detail: s.image ? `Picture (${codecName(s.codec)})` : `Text (${codecName(s.codec)})`,
    image: !!s.image,
  }))
}

/** "2 versions · 27.6 GB · sound in 2 languages · 2 subtitle tracks": File details' line while it is closed. */
export function filesSummary(files: readonly Pick<FileFacts, 'size' | 'probe'>[]): string {
  const size = files.reduce((n, f) => n + (f.size || 0), 0)
  const first = files[0]?.probe
  const languages = new Set((first?.audio ?? []).map((a) => (knownLanguage(a.language) ? a.language : '')).filter(Boolean))
  const subs = first?.subtitles.length ?? 0
  return [
    files.length === 1 ? '1 version' : `${files.length} versions`,
    size ? formatBytes(size) : '',
    languages.size > 1 ? `sound in ${languages.size} languages` : '',
    subs ? `${subs} subtitle ${subs === 1 ? 'track' : 'tracks'}` : '',
  ]
    .filter(Boolean)
    .join(' · ')
}

/** How a version plays on this browser, in words, from the server's plan. */
export function planWords(plan?: Pick<PlaybackPlan, 'mode' | 'video' | 'audio'>, source?: Pick<FileFacts, 'videoCodec'>): string | undefined {
  switch (plan?.mode) {
    case 'direct':
    case 'remux':
      return 'Plays on this browser without converting'
    case 'audio':
      return 'Plays on this browser; the sound is converted as it plays'
    case 'transcode': {
      const codec = codecName(source?.videoCodec)
      return plan.video?.copy === false && codec ? `This browser can’t play ${codec} here, so the video is converted as it plays` : 'The video is converted as it plays'
    }
    default:
      return undefined
  }
}

/** This person's place in a movie, for Watched here: "You are 1 h 6 min in", "You watched it all". */
export function yourPlace(watch?: { completed: boolean; positionMs: number }): string | undefined {
  if (!watch) return undefined
  if (watch.completed) return 'You watched it all'
  if (watch.positionMs > 60_000) return `You are ${minutesWords(watch.positionMs)} in`
  return undefined
}

/** One play in Watched here: "Jordan watched it all", "Sam stopped at 40 min", "You watched S3 E7". */
export function historyWords(r: Pick<HistoryRow, 'completed' | 'positionMs' | 'item'> & { user: { name: string } }, you: boolean): string {
  const who = you ? 'You' : r.user.name
  const ep = r.item.type === 'episode' && r.item.seasonNumber != null && r.item.episodeNumber != null ? `S${r.item.seasonNumber} E${r.item.episodeNumber}` : ''
  if (r.completed) return ep ? `${who} watched ${ep}` : `${who} watched it all`
  const at = r.positionMs > 0 ? ` at ${minutesWords(r.positionMs)}` : ''
  return ep ? `${who} stopped in ${ep}${at}` : `${who} stopped${at || ' at the start'}`
}

/** A season tab's count: "10 · all watched", "6 of 8 watched", "2". With Show missing, what the library lacks. */
export function seasonTabWords(s: Pick<Season, 'episodeCount' | 'availableCount' | 'watchedCount'>, showMissing: boolean): string {
  const n = s.availableCount
  if (showMissing && n < s.episodeCount) return n === 0 ? 'not in library' : `${n} of ${s.episodeCount}`
  if (n === 0) return '0'
  if (s.watchedCount >= n) return `${n} · all watched`
  if (s.watchedCount > 0) return `${s.watchedCount} of ${n} watched`
  return String(n)
}

/** "8 episodes here · 1 new · episode 9 airs Dec 1": the line above a season's episodes. */
export function seasonLine(episodes: readonly Pick<Episode, 'number' | 'hasFile' | 'isNew' | 'aired' | 'airDate' | 'watch'>[]): string {
  const here = episodes.filter((e) => e.hasFile)
  const fresh = here.filter((e) => e.isNew && !e.watch?.completed).length
  const next = episodes.filter((e) => !e.aired && e.airDate).sort((a, b) => a.number - b.number)[0]
  return [
    `${here.length} ${here.length === 1 ? 'episode' : 'episodes'} here`,
    fresh ? `${fresh} new` : '',
    next?.airDate ? `episode ${next.number} airs ${formatDate(next.airDate)}` : '',
  ]
    .filter(Boolean)
    .join(' · ')
}

/** An episode row's facts: "Aired Oct 5, 2026 · 50 min", "Airs Nov 30, 2026". */
export function episodeRowFacts(e: Pick<Episode, 'aired' | 'airDate' | 'runtimeMin' | 'files' | 'hasFile'>): string {
  const minutes = e.runtimeMin || (e.files[0]?.durationMs ? Math.round(e.files[0].durationMs / 60_000) : 0)
  if (!e.aired && !e.hasFile) return e.airDate ? `Airs ${formatDate(e.airDate)}` : 'Not aired yet'
  return [e.airDate ? `Aired ${formatDate(e.airDate)}` : '', minutes ? `${minutes} min` : ''].filter(Boolean).join(' · ')
}

/** An episode row's state at its end: "Watched", "29 min left" (gold), "Not watched" for a new one. */
export function episodeRowState(e: Pick<Episode, 'watch' | 'isNew' | 'hasFile' | 'files'>): { text: string; gold: boolean } | undefined {
  if (!e.hasFile) return undefined
  if (e.watch?.completed) return { text: 'Watched', gold: false }
  if (e.watch && e.watch.positionMs > 0) {
    const durationMs = e.watch.durationMs || e.files[0]?.durationMs || 0
    if (durationMs > e.watch.positionMs) return { text: `${minutesWords(durationMs - e.watch.positionMs)} left`, gold: true }
  }
  if (e.isNew) return { text: 'Not watched', gold: false }
  return undefined
}

/** The show's state beside its name: "Continuing · next episode Dec 1, 2026", "Ended". */
export function showState(s: { status?: string; nextAirDate?: string }): { label: string; gold: boolean } | undefined {
  if (s.status === 'Ended') return { label: 'Ended', gold: false }
  if (s.status === 'Canceled') return { label: 'Cancelled', gold: false }
  if (s.nextAirDate) return { label: `Continuing · next episode ${formatDate(s.nextAirDate)}`, gold: true }
  if (s.status === 'Returning Series') return { label: 'Continuing', gold: true }
  return s.status ? { label: s.status, gold: false } : undefined
}

/** The choices made before playing, as the player's address carries them; nothing for what was left as it was. */
export function playChoices(c: { audio?: number; subtitle?: string }): { audio?: number; subtitle?: string } {
  return { ...(c.audio !== undefined ? { audio: c.audio } : {}), ...(c.subtitle ? { subtitle: c.subtitle } : {}) }
}

/** Initials for a person with no photo: "IC" for Ines Calloway. */
export function initials(name: string): string {
  const parts = name.split(/\s+/).filter(Boolean)
  return ((parts[0]?.[0] ?? '') + (parts.length > 1 ? (parts.at(-1)?.[0] ?? '') : '')).toUpperCase()
}
