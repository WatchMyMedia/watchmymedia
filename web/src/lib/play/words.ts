import type { components } from '../../api/schema'
import { clock } from '../clock'
import { endsAt } from '../endTime'
import { languageName, knownLanguage } from '../format'
import { stepName } from '../quality'

type Decision = components['schemas']['Decision']
type MediaInfo = components['schemas']['MediaInfo']
/** A sound track as the decision lists it; only what the words need. */
type AudioLike = { index: number; codec: string; channels: number; language?: string; title?: string }
/** A subtitle track, or the parts of one the words need. */
type SubtitleLike = { language?: string; title?: string; forced?: boolean; hearingImpaired?: boolean; external?: boolean; source?: string; release?: string; burnInOnly?: boolean }

// The player's words: its title, what is happening to the stream, the
// tracks, the times beside the seek bar. Technical detail is said in
// words first; the numbers follow in Playback info.

/** The bar's title: a show's name over "S3 E7 · The Long Dark", or a movie's title alone. */
export function playerTitle(title: string | undefined): { kicker?: string; heading: string } {
  const t = (title ?? '').trim()
  if (!t) return { heading: '' }
  const at = t.indexOf(' · ')
  if (at < 0) return { heading: t }
  const left = t.slice(0, at)
  const rest = t.slice(at + 3).trim()
  // "Harbor Lights · 2019": a movie and its year.
  if (!rest || /^\d{4}$/.test(rest)) return { heading: left }
  const code = /^S(\d+)E(\d+)(?:-E?\d+)?\s*(.*)$/.exec(rest)
  if (code) {
    const name = code[3].trim()
    return { kicker: left, heading: [`S${Number(code[1])} E${Number(code[2])}`, name].filter(Boolean).join(' · ') }
  }
  return { kicker: left, heading: rest }
}

const VIDEO_CODECS: Record<string, string> = {
  h264: 'H.264',
  hevc: 'HEVC',
  hevc10: '10-bit HEVC',
  av1: 'AV1',
  vp9: 'VP9',
  vp8: 'VP8',
  mpeg2video: 'MPEG-2',
  mpeg4: 'MPEG-4',
  vc1: 'VC-1',
}
const AUDIO_CODECS: Record<string, string> = {
  aac: 'AAC',
  ac3: 'AC-3',
  eac3: 'E-AC-3',
  dts: 'DTS',
  truehd: 'Dolby TrueHD',
  flac: 'FLAC',
  opus: 'Opus',
  mp3: 'MP3',
  vorbis: 'Vorbis',
  pcm: 'PCM',
  alac: 'ALAC',
}
const HDR_NAMES: Record<string, string> = { hdr10: 'HDR10', hlg: 'HLG', dolbyvision: 'Dolby Vision', hdr10plus: 'HDR10+' }

/** "HEVC", "H.264", or the codec's own name in capitals. */
export function videoCodecName(codec: string | undefined): string {
  if (!codec) return ''
  return VIDEO_CODECS[codec.toLowerCase()] ?? codec.toUpperCase()
}

/** "E-AC-3", "DTS", or the codec's own name in capitals. */
export function audioCodecName(codec: string | undefined): string {
  if (!codec) return ''
  const c = codec.toLowerCase()
  return AUDIO_CODECS[c.startsWith('pcm_') ? 'pcm' : c] ?? codec.toUpperCase()
}

/** "Mono", "Stereo", "5.1 surround", "7.1 surround". */
export function channelWords(channels: number | undefined): string {
  if (!channels) return ''
  if (channels === 1) return 'Mono'
  if (channels === 2) return 'Stereo'
  if (channels === 6) return '5.1 surround'
  if (channels === 8) return '7.1 surround'
  return `${channels} channels`
}

/** "4K", "1080p", "720p", "SD" from a picture's height. */
export function heightName(height: number | undefined, width?: number): string {
  if (!height) return ''
  if (height >= 2000 || (width ?? 0) >= 3800) return '4K'
  if (height >= 1000 || (width ?? 0) >= 1900) return '1080p'
  if (height >= 700 || (width ?? 0) >= 1200) return '720p'
  if (height >= 560) return '576p'
  return 'SD'
}

/** One of the server's reasons for converting, in a viewer's words; undefined for one it can't word. */
export function reasonWords(reason: string): string | undefined {
  let m = /^client cannot decode (\S+) video$/.exec(reason)
  if (m) return `this browser can’t play ${videoCodecName(m[1])} video`
  m = /^client cannot play Dolby Vision (.+)$/.exec(reason)
  if (m) return 'this browser can’t play Dolby Vision'
  m = /^client cannot decode (\S+) audio$/.exec(reason)
  if (m) return `this browser can’t play ${audioCodecName(m[1])} sound`
  m = /^client cannot play the (\S+) container$/.exec(reason)
  if (m) return `this browser can’t open ${m[1].toUpperCase()} files`
  m = /^the screen cannot show (.+)$/.exec(reason)
  if (m) return `this screen can’t show ${m[1]}`
  if (/^\d+x\d+ exceeds the client's/.test(reason)) return 'the quality chosen is smaller than the file'
  if (/^bitrate \d+ kb\/s exceeds the client's/.test(reason)) return 'the quality chosen needs less data than the file'
  m = /^(\d+) audio channels exceed the client's (\d+)$/.exec(reason)
  if (m) return `this browser plays up to ${channelWords(Number(m[2])).toLowerCase()} sound`
  if (reason === 'subtitle burn-in requested') return 'the subtitles chosen are drawn into the picture'
  if (reason === 'selected audio track requires a remux') return 'the sound track chosen has to be repackaged'
  if (reason === 'no video stream') return 'the file has no picture'
  if (/^the server cannot convert Dolby Vision/.test(reason) || /^audio converted to/.test(reason) || /^Dolby Vision \d+ is sent as/.test(reason)) return reason
  return undefined
}

/** "a, b and c". */
function joined(parts: string[]): string {
  return parts.length < 2 ? (parts[0] ?? '') : `${parts.slice(0, -1).join(', ')} and ${parts.at(-1)}`
}

/**
 * What is happening to the stream, in a sentence or two: "Plays on this
 * browser without converting.", "Converting the video, because this
 * browser can’t play HEVC video. Sound is untouched."
 */
export function streamStory(d: Pick<Decision, 'mode' | 'reasons' | 'video' | 'audio' | 'auto' | 'burnIn'>): string {
  const why = [...new Set(d.reasons.map(reasonWords).filter((w): w is string => !!w))]
  const because = why.length ? `, because ${joined(why)}` : ''
  const sound = d.audio.copy ? 'Sound is untouched.' : `Sound is converted to ${audioCodecName(d.audio.codec)}${d.audio.channels ? ` ${channelWords(d.audio.channels).toLowerCase()}` : ''}.`
  switch (d.mode) {
    case 'direct':
      return 'Plays on this browser without converting.'
    case 'remux':
      return `Plays without converting the picture or sound: the server repackages the file${because}.`
    case 'audio':
      return `Converting the sound only${because}. The picture is untouched.`
    default: {
      const tone = d.video.toneMap ? ', turning its HDR into SDR' : ''
      return `Converting the video for this browser${tone}${because}. ${sound}`
    }
  }
}

/** Automatic's line in Playback info: "720p · measured 3.1 Mbps", "Original quality · on the local network". */
export function autoWords(auto: Decision['auto']): string | undefined {
  if (!auto) return undefined
  const step = !auto.step || auto.step === 'original' ? 'Original quality' : stepName(auto.step)
  return [step, auto.reason].filter(Boolean).join(' · ')
}

/** "4K HEVC, HDR10 → 1080p H.264, tone-mapped", or "1080p H.264 · as it is". */
export function videoLine(d: Pick<Decision, 'video'>, source?: { codec?: string; width?: number; height?: number; hdr?: string; bitDepth?: number }): string {
  const out = [heightName(d.video.height, d.video.width), videoCodecName(d.video.codec)].filter(Boolean).join(' ')
  const hdr = d.video.hdr ? HDR_NAMES[d.video.hdr] ?? d.video.hdr.toUpperCase() : ''
  if (d.video.copy) return `${[out, hdr].filter(Boolean).join(', ')} · as it is`
  if (!source) return [out, d.video.toneMap ? 'tone-mapped' : ''].filter(Boolean).join(', ')
  const codec = source.codec === 'hevc' && (source.bitDepth ?? 8) > 8 ? 'hevc10' : source.codec
  const from = [[heightName(source.height, source.width), videoCodecName(codec)].filter(Boolean).join(' '), source.hdr ? HDR_NAMES[source.hdr] ?? source.hdr.toUpperCase() : ''].filter(Boolean).join(', ')
  return `${from} → ${[out, d.video.toneMap ? 'tone-mapped' : ''].filter(Boolean).join(', ')}`
}

/** "English 5.1, E-AC-3 · as it is", or "English 5.1, DTS → Stereo AAC". */
export function audioLine(d: Pick<Decision, 'audio'> & { audioTracks: readonly AudioLike[] }): string {
  const track = d.audioTracks.find((a) => a.index === d.audio.index)
  const lang = knownLanguage(track?.language) ? languageName(track?.language) : ''
  const source = [[lang, track?.channels ? channelWords(track.channels).replace(' surround', '') : ''].filter(Boolean).join(' '), audioCodecName(track?.codec)].filter(Boolean).join(', ')
  if (d.audio.copy) return `${source || audioCodecName(d.audio.codec)} · as it is`
  const to = [d.audio.channels ? channelWords(d.audio.channels).replace(' surround', '') : '', audioCodecName(d.audio.codec)].filter(Boolean).join(' ')
  return source ? `${source} → ${to}` : to
}

/** "8.2 Mb/s"; empty for nothing. */
export function mbps(bitsPerSecond: number | undefined): string {
  if (!bitsPerSecond || bitsPerSecond <= 0) return ''
  const v = bitsPerSecond / 1_000_000
  return `${v >= 10 ? v.toFixed(0) : v.toFixed(1)} Mb/s`
}

/** The rows of Playback info under its sentence, in order. */
export function playbackRows(o: {
  decision: Pick<Decision, 'video' | 'audio' | 'bitrate' | 'auto' | 'mode'> & { audioTracks: readonly AudioLike[]; subtitles: readonly (SubtitleLike & { id: string })[] }
  source?: MediaInfo
  subtitleId: string
  bufferedSeconds: number
  observedHeight?: number
}): { label: string; value: string }[] {
  const d = o.decision
  const v = o.source?.video?.[0]
  const rows = [
    { label: 'Video', value: videoLine(d, v ? { codec: v.codec, width: v.width, height: v.height, hdr: v.hdr, bitDepth: v.bitDepth } : undefined) },
    { label: 'Audio', value: audioLine(d) },
  ]
  const sub = d.subtitles.find((s) => s.id === o.subtitleId)
  rows.push({ label: 'Subtitles', value: sub ? subtitleWords(sub).title + (sub.burnInOnly ? ', drawn into the picture' : '') : 'Off' })
  const stream = mbps(d.bitrate)
  const file = mbps(o.source?.bitRate)
  if (stream || file) rows.push({ label: 'Bitrate', value: [stream ? `${stream} now` : '', file && file !== stream && d.mode !== 'direct' ? `the file is ${file}` : ''].filter(Boolean).join(' · ') || file })
  rows.push({ label: 'Buffer', value: o.bufferedSeconds >= 1 ? `${Math.round(o.bufferedSeconds)} s ahead` : 'Filling' })
  const auto = autoWords(d.auto)
  if (auto) rows.push({ label: 'Automatic quality', value: o.observedHeight ? `${auto} · showing ${o.observedHeight}p` : auto })
  return rows
}

/** A sound track's choice: "English" over "5.1 surround" or "Commentary · Stereo". */
export function audioWords(a: AudioLike): { title: string; detail: string } {
  const lang = knownLanguage(a.language) ? languageName(a.language) : ''
  const title = lang || a.title || `Track ${a.index + 1}`
  const detail = [lang && a.title ? a.title : '', channelWords(a.channels), audioCodecName(a.codec)].filter(Boolean).join(' · ')
  return { title, detail }
}

/** A subtitle's choice: "English" over "Full", "Signs and foreign speech only", "For the deaf and hard of hearing". */
export function subtitleWords(s: SubtitleLike): { title: string; detail: string } {
  const lang = knownLanguage(s.language) ? languageName(s.language) : ''
  const title = lang || s.title || 'Unknown language'
  const kind = s.forced ? 'Signs and foreign speech only' : s.hearingImpaired ? 'For the deaf and hard of hearing' : 'Full'
  const from = s.source === 'opensubtitles' ? 'Downloaded' : s.external ? 'Beside the file' : ''
  const named = lang && s.title && !/^(sdh|forced|full)$/i.test(s.title) ? s.title : ''
  const detail = [kind, named, from, s.burnInOnly ? 'Drawn into the picture' : ''].filter(Boolean).join(' · ')
  return { title, detail }
}

/** A subtitle found online, not yet downloaded: "English" over "Matches this file · release". */
export function onlineWords(r: { language: string; hashMatch?: boolean; hearingImpaired?: boolean; forced?: boolean; aiTranslated?: boolean; release?: string }): { title: string; detail: string } {
  const detail = [r.hashMatch ? 'Matches this file' : '', r.forced ? 'Signs and foreign speech only' : '', r.hearingImpaired ? 'For the deaf and hard of hearing' : '', r.aiTranslated ? 'Machine translated' : '', r.release].filter(Boolean).join(' · ')
  return { title: languageName(r.language), detail }
}

/** "−29:02 · ends 8:41 PM": what is left and when it ends if it plays on now. */
export function timeLeftLine(timeSeconds: number, durationSeconds: number, now: number, locale?: string): string {
  if (!durationSeconds || durationSeconds <= 0) return ''
  const left = Math.max(0, durationSeconds - timeSeconds) * 1000
  return `−${clock(left)} · ends ${endsAt(left, now, locale)}`
}

const MARK_LABELS: Record<string, string> = { intro: 'Intro', recap: 'Recap', credits: 'Credits', preview: 'Preview', commercial: 'Ad' }

/** A segment drawn on the seek bar: where it sits (percent of the bar), its name, and how it reads aloud. */
export type SegmentMark = { id: string; kind: string; label: string; left: number; width: number; spoken: string }

/** The file's intro, recap, credits and the like as marks on the seek bar, the shortest left out. */
export function segmentMarks(segments: readonly { id?: number; kind: string; startMs: number; endMs?: number }[] | undefined, durationSeconds: number): SegmentMark[] {
  if (!segments?.length || durationSeconds <= 0) return []
  const total = durationSeconds * 1000
  const out: SegmentMark[] = []
  for (const s of segments) {
    const label = MARK_LABELS[s.kind]
    if (!label) continue
    const end = Math.min(total, s.endMs || total)
    if (end - s.startMs < 1000) continue
    const left = Math.max(0, (s.startMs / total) * 100)
    const width = Math.max(0.4, ((end - s.startMs) / total) * 100)
    const spoken = end >= total - 1000 ? `${label}, from ${clock(s.startMs)}` : `${label}, ${clock(s.startMs)} to ${clock(end)}`
    out.push({ id: `${s.kind}-${s.id ?? s.startMs}`, kind: s.kind, label, left, width: Math.min(width, 100 - left), spoken })
  }
  return out
}

// Volume, remembered on this browser.

export type VolumeSetting = { level: number; muted: boolean }
export const DEFAULT_VOLUME: VolumeSetting = { level: 1, muted: false }

/** The stored volume read back; anything else is full and unmuted. */
export function parseVolume(stored: string | null): VolumeSetting {
  if (!stored) return DEFAULT_VOLUME
  try {
    const v: unknown = JSON.parse(stored)
    if (typeof v !== 'object' || v === null) return DEFAULT_VOLUME
    const level = (v as { level?: unknown }).level
    const muted = (v as { muted?: unknown }).muted
    return { level: typeof level === 'number' && Number.isFinite(level) ? clampVolume(level) : 1, muted: muted === true }
  } catch {
    return DEFAULT_VOLUME
  }
}

export const clampVolume = (v: number) => Math.round(Math.min(1, Math.max(0, v)) * 100) / 100

/** Up and Down: a tenth louder or quieter; louder unmutes, from where the level was. */
export function stepVolume(v: VolumeSetting, delta: number): VolumeSetting {
  const level = clampVolume(v.level + delta)
  return { level, muted: delta < 0 ? v.muted || level === 0 : level === 0 }
}

/** "Mute" or "Unmute", and the level read aloud. */
export function volumeLabel(v: VolumeSetting): string {
  return v.muted || v.level === 0 ? 'Unmute' : 'Mute'
}

// Previous episode.

type EpisodeLike = { id: number; number: number; hasFile: boolean; title?: string; files?: readonly { id: number }[] }

/** The episode before this one in its season that has a file, or undefined (the first, or none on disk). */
export function previousInSeason<E extends EpisodeLike>(episodes: readonly E[], currentId: number): E | undefined {
  const sorted = [...episodes].sort((a, b) => a.number - b.number)
  const at = sorted.findIndex((e) => e.id === currentId)
  if (at <= 0) return undefined
  for (let i = at - 1; i >= 0; i--) if (sorted[i].hasFile && sorted[i].files?.length) return sorted[i]
  return undefined
}

/** The last episode on disk of an earlier season, for the first episode of a season. */
export function lastWithFile<E extends EpisodeLike>(episodes: readonly E[]): E | undefined {
  return [...episodes].sort((a, b) => b.number - a.number).find((e) => e.hasFile && !!e.files?.length)
}

/** The season before `season` that the show has, skipping Specials; undefined for the first. */
export function previousSeason(seasons: readonly { number: number }[], season: number): number | undefined {
  return seasons
    .map((s) => s.number)
    .filter((n) => n > 0 && n < season)
    .sort((a, b) => b - a)[0]
}

/** "S3 E6, Slack Water": an episode named for a button. */
export function episodeName(season: number, episode: number, title?: string): string {
  return [`S${season} E${episode}`, title].filter(Boolean).join(', ')
}

/**
 * The subtitle a title's page chose before playing, from the player's
 * address: "off", or a track id. undefined leaves it to the person's settings.
 */
export function chosenSubtitle(subtitle: string | undefined): { subtitleMode: 'off'; subtitleId?: string } | undefined {
  if (!subtitle) return undefined
  return subtitle === 'off' ? { subtitleMode: 'off' } : { subtitleMode: 'off', subtitleId: subtitle }
}
