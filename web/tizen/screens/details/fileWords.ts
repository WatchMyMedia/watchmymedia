import type { components } from '../../../src/api/schema'
import { formatBytes, knownLanguage, languageName, resolutionLabel } from '../../../src/lib/format'
import { codecWords } from '../../player'
import { channelsText, fileName, movieVersions } from './movieLabels'

// A title's file in words for the people watching: three tags about the
// picture and sound, the audio and subtitles line, each version's name and
// line, and File details, which starts with a sentence on how it plays on
// this TV and keeps the numbers under it, one press from the first screen.

type MediaFileDetail = components['schemas']['MediaFileDetail']
type MovieDetail = components['schemas']['MovieDetail']
type PlaybackPlan = components['schemas']['PlaybackPlan']
type MediaInfo = components['schemas']['MediaInfo']

const SEP = ' · '

/** Sound formats as people know them: "Dolby TrueHD", "Dolby Digital Plus". */
const SOUND: Record<string, string> = {
  truehd: 'Dolby TrueHD', eac3: 'Dolby Digital Plus', ac3: 'Dolby Digital', dts: 'DTS', aac: 'AAC', flac: 'FLAC', opus: 'Opus', mp3: 'MP3', pcm: 'PCM',
}

export function soundName(codec: string | undefined): string | undefined {
  if (!codec) return undefined
  const c = codec.toLowerCase()
  return SOUND[c] ?? (c.startsWith('pcm') ? 'PCM' : c.startsWith('dts') ? 'DTS' : codecWords(codec))
}

/** "Surround 7.1", "Surround 5.1", "Stereo", "Mono": how many speakers, in words. */
export function speakerWords(channels: number | undefined, layout?: string): string | undefined {
  const text = channelsText(channels, layout)
  if (!text) return undefined
  if (text === '2.0') return 'Stereo'
  if (text === '1.0') return 'Mono'
  return /^\d\.\d$/.test(text) && Number(text[0]) >= 3 ? `Surround ${text}` : text
}

/** The default video and audio streams of a version, from its probe. */
function streams(f: MediaFileDetail) {
  const video = f.probe?.video.find((v) => v.default) ?? f.probe?.video[0]
  const audio = f.probe?.audio.find((a) => a.default) ?? f.probe?.audio[0]
  return { video, audio }
}

/** "Dolby Vision", "HDR", or nothing for standard colour. */
export function colourWords(hdr: string | undefined): string | undefined {
  if (!hdr) return undefined
  return hdr === 'dolbyvision' ? 'Dolby Vision' : 'HDR'
}

/** A version's name: "4K with Dolby Vision", "1080p", "4K with HDR"; its file type when neither is known. */
export function versionName(f: MediaFileDetail): string {
  const { video } = streams(f)
  const size = resolutionLabel(f.width ?? video?.width, f.height ?? video?.height)
  const colour = colourWords(f.hdr ?? video?.hdr)
  if (size && colour) return `${size} with ${colour}`
  return size || colour || fileName(f.relativePath).split('.').pop()?.toUpperCase() || 'Version'
}

/**
 * The three words on a title's first screen about its picture and sound,
 * from its default version: "4K", "Dolby Vision", "Surround sound".
 * Nothing about codecs or containers.
 */
export function titleTags(m: Pick<MovieDetail, 'quality' | 'hdr' | 'files'>): string[] {
  const v = movieVersions(m)[0]
  const { video, audio } = v ? streams(v) : { video: undefined, audio: undefined }
  const size = m.quality || (v ? resolutionLabel(v.width ?? video?.width, v.height ?? video?.height) : '')
  const colour = (v && colourWords(v.hdr ?? video?.hdr)) ?? (m.hdr ? 'HDR' : undefined)
  const surround = audio && (audio.channels ?? 0) > 2 ? 'Surround sound' : undefined
  return [size, colour, surround].filter((t): t is string => !!t)
}

/** A track's language, or its title when the language isn't known. */
function trackName(language: string | undefined, title: string | undefined): string | undefined {
  return knownLanguage(language) ? languageName(language) : title?.trim() || undefined
}

/**
 * Beside the tags: the sound's language and the subtitles the file shows
 * by itself, "English · subtitles off" or "English · Spanish subtitles".
 * The player can change both.
 */
export function trackLine(probe: Pick<MediaInfo, 'audio' | 'subtitles'> | undefined): string | undefined {
  if (!probe) return undefined
  const audio = probe.audio.find((a) => a.default) ?? probe.audio[0]
  const shown = probe.subtitles.find((s) => s.forced) ?? probe.subtitles.find((s) => s.default)
  const sound = audio && trackName(audio.language, audio.title)
  const subtitles = shown ? `${trackName(shown.language, shown.title) ?? 'Unknown language'} subtitles` : 'subtitles off'
  return [sound, subtitles].filter(Boolean).join(SEP)
}

/** What a plan does to the sound, in words: to surround or stereo when the plan says how many speakers, else just so it plays. */
function convertedTo(channels: number | undefined): string {
  if (!channels) return 'converted so this TV can play it'
  return `converted to ${channels > 2 ? 'surround' : 'stereo'} this TV can play`
}

/** The picture in words for a sentence: "in 4K with Dolby Vision", "in 1080p". */
function pictureIn(f: MediaFileDetail): string {
  const name = versionName(f)
  return /^(4K|\d+p|SD)/.test(name) ? `, in ${name}` : ''
}

/**
 * How a version plays on this TV, in a sentence or two and in words: what
 * plays as it is and what is converted, and why when the TV can't play the
 * original. No codec names beyond the sound format people know.
 */
export function playSentence(f: MediaFileDetail, plan: PlaybackPlan | undefined): string {
  if (!plan) return 'How it plays on this TV is worked out when it starts.'
  const { audio } = streams(f)
  const source = soundName(audio?.codec ?? f.audioCodec)
  const sound = plan.audio.copy ? undefined
    : `The sound is ${convertedTo(plan.audio.channels)}${source ? `, because it can’t play ${source}` : ''}.`
  if (plan.mode === 'direct') return `It plays on this TV as it is${pictureIn(f)}, without converting anything.`
  if (plan.mode === 'transcode' && !plan.video.copy) {
    const size = resolutionLabel(plan.video.width, plan.video.height)
    const picture = `The picture is converted${size ? ` to ${size}` : ''} for this TV${plan.video.toneMap ? ', in standard colour' : ''}.`
    return [picture, sound].filter(Boolean).join(' ')
  }
  const picture = `The picture plays on this TV as it is${pictureIn(f)}.`
  return [picture, sound ?? 'The sound plays as it is too.'].join(' ')
}

/** What a version's line ends with: "plays next" for the chosen one, else how it would play. */
export function versionEnd(plan: PlaybackPlan | undefined, chosen: boolean): string | undefined {
  if (chosen) return 'plays next'
  if (!plan) return undefined
  if (plan.mode === 'direct') return 'plays without converting anything'
  if (plan.mode === 'transcode' && !plan.video.copy) return 'converts the picture'
  return plan.audio.copy ? 'plays as it is' : 'converts the sound'
}

/** A version's line: "Surround 7.1 · 48 GB · plays next". */
export function versionLine(f: MediaFileDetail, plan: PlaybackPlan | undefined, chosen: boolean): string {
  const { audio } = streams(f)
  return [speakerWords(audio?.channels, audio?.channelLayout), f.size ? formatBytes(f.size) : undefined, versionEnd(plan, chosen)].filter(Boolean).join(SEP)
}

/** Choose a version's line in More: "4K with Dolby Vision, or 1080p". */
export function versionsLine(files: MediaFileDetail[]): string {
  const names = files.map(versionName)
  return names.length <= 1 ? names.join('') : `${names.slice(0, -1).join(', ')}, or ${names[names.length - 1]}`
}

/** "English and Spanish", "English, French and Spanish". */
function andList(items: string[]): string {
  return items.length <= 1 ? items.join('') : `${items.slice(0, -1).join(', ')} and ${items[items.length - 1]}`
}

export type NumberRow = { label: string; value: string }

/**
 * File details' numbers, for the people who want them: the picture's codec,
 * size and HDR; the sound and what it is converted to; the subtitles as
 * text or pictures; the file's name and size.
 */
export function fileNumbers(f: MediaFileDetail, plan: PlaybackPlan | undefined): NumberRow[] {
  const { video, audio } = streams(f)
  const width = f.width ?? video?.width, height = f.height ?? video?.height
  const hdr = f.hdr ?? video?.hdr
  const dv = f.dvProfile ?? video?.dvProfile
  const colour = !hdr ? undefined : hdr === 'dolbyvision' ? `Dolby Vision${dv ? ` profile ${dv}` : ''}` : hdr === 'hdr10' ? 'HDR10' : hdr === 'hlg' ? 'HLG' : String(hdr).toUpperCase()
  const picture = [codecWords(f.videoCodec ?? video?.codec), width && height ? `${width} × ${height}` : undefined, colour].filter(Boolean).join(SEP)
  const source = [soundName(audio?.codec ?? f.audioCodec), channelsText(audio?.channels, audio?.channelLayout)].filter(Boolean).join(' ')
  const target = plan && !plan.audio.copy ? [soundName(plan.audio.codec) ?? 'AAC', channelsText(plan.audio.channels)].filter(Boolean).join(' ') : undefined
  const subs = f.probe?.subtitles ?? []
  const text = [...new Set(subs.filter((s) => !s.image).map((s) => trackName(s.language, s.title) ?? 'Unknown language'))]
  const pictures = [...new Set(subs.filter((s) => s.image).map((s) => trackName(s.language, s.title) ?? 'Unknown language'))]
  const subtitles = [
    text.length ? `${andList(text)} as text` : '',
    pictures.length ? `${andList(pictures)} as pictures, drawn into the video when shown` : '',
  ].filter(Boolean).join('; ')
  return [
    ...(picture ? [{ label: 'Picture', value: picture }] : []),
    ...(source ? [{ label: 'Sound', value: target ? `${source}, converted to ${target}` : source }] : []),
    { label: 'Subtitles', value: subtitles || 'None in the file' },
    { label: 'File', value: [fileName(f.relativePath), f.size ? formatBytes(f.size) : ''].filter(Boolean).join(SEP) },
  ]
}
