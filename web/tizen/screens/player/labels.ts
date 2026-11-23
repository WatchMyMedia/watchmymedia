import { clock } from '../../../src/lib/clock'
import { resolutionLabel } from '../../../src/lib/format'
import { timeOfDay, timeRange } from '../../../src/lib/liveGuide'
import { AUTO, stepName } from '../../../src/lib/quality'
import type { AudioOption, FilePlaybackState, SubtitleOption, UpNext } from '../../player'
import { endsAtWords, leftWords } from '../../ui/timeWords'

// The player screen's words, as the Samsung TV boards
// and Android TV's PlayerLabels
// say them. The engine's own words (the pill, track labels, errors, notes)
// come from player/labels.ts; nothing here is the server's text.

const SEP = ' · '

/** The subtitles' choices, without the cues showing. */
export type SubtitleChoices = Pick<FilePlaybackState['subtitles'], 'options' | 'selected' | 'online'>
const two = (n: number) => String(n).padStart(2, '0')

/** "S03 E07", the Episodes and Next episode buttons' detail. */
export const episodeCode = (season: number, episode: number) => `S${two(season)} E${two(episode)}`

/** "Season 3 · Episode 7 · The Long Dark", under the title. */
export const episodeLine = (season: number, episode: number, title: string) => `Season ${season}${SEP}Episode ${episode}${title ? `${SEP}${title}` : ''}`

/** "S3 E7", short, where a line names the episode beside the show (Up next's foot). */
export const shortCode = (season: number, episode: number) => `S${season} E${episode}`

/** The Audio button's detail: the track playing, by its language. */
export function audioDetail(audio: FilePlaybackState['audio']): string | undefined {
  return (audio.options.find((a) => a.index === audio.selected) ?? audio.options[0])?.label
}

/** The Subtitles button's detail: the track showing, or Off. */
export function subtitleDetail(subtitles: SubtitleChoices): string {
  return subtitles.options.find((s) => s.id === subtitles.selected)?.label ?? 'Off'
}

/** Audio and subtitles' button detail: "English, off", "English, Español". */
export function tracksDetail(audio: FilePlaybackState['audio'], subtitles: SubtitleChoices): string | undefined {
  const a = audioDetail(audio)
  const sub = subtitleDetail(subtitles)
  return [a, sub === 'Off' ? 'off' : sub].filter(Boolean).join(', ') || undefined
}

/**
 * An audio track's line, in words rather than formats: a commentary or a
 * described track says so (its own name, as the file gives it), else how
 * it sounds: "Surround sound", "Stereo", "Mono".
 */
export function audioLine(option: Pick<AudioOption, 'channels' | 'title'>): string | undefined {
  const title = option.title?.trim()
  if (title && /comment/i.test(title)) return title.charAt(0).toUpperCase() + title.slice(1)
  if (title && /descri/i.test(title)) return 'Describes what is on screen'
  const c = option.channels ?? 0
  return c >= 6 ? 'Surround sound' : c === 2 ? 'Stereo' : c === 1 ? 'Mono' : undefined
}

/** A subtitle track's line in words: "With sounds described" for the hard of hearing, "Only when another language is spoken" for forced ones. */
export function subtitleLine(option: Pick<SubtitleOption, 'forced' | 'hearingImpaired' | 'burnIn' | 'source'>): string | undefined {
  if (option.hearingImpaired) return 'With sounds described'
  if (option.forced) return 'Only when another language is spoken'
  if (option.burnIn) return 'Drawn into the picture; the video restarts from here'
  if (option.source === 'opensubtitles') return 'Found online'
  return undefined
}

/** The Quality button's detail: "Automatic", "Automatic · 720p" once it went down a step, "Original", "1080p". */
export function qualityDetail(quality: FilePlaybackState['quality']): string {
  if (quality.id === AUTO) return quality.autoStep && quality.autoStep !== 'original' ? `Automatic${SEP}${stepName(quality.autoStep)}` : 'Automatic'
  return stepName(quality.id)
}

export type PickRow = { id: string; label: string; detail?: string; checked: boolean }

const QUALITY_LINES: Record<string, string> = {
  original: 'Always the file as it is', 2160: 'The sharpest the server makes', 1080: 'Good on a slow connection', 360: 'Smallest',
}

/**
 * The Quality panel: Automatic (saying what it sends now, once it went
 * down a step), Original, then the fixed steps, each with what it is for
 * in words rather than a bitrate.
 */
export function qualityRows(quality: FilePlaybackState['quality']): PickRow[] {
  return quality.options.map((q) => {
    const checked = q.id === quality.id
    if (q.id === AUTO) return { id: q.id, label: 'Automatic', detail: checked && quality.autoStep && quality.autoStep !== 'original' ? `Now ${stepName(quality.autoStep)} for your connection` : 'Best picture the connection can hold', checked }
    return { id: q.id, label: stepName(q.id), detail: QUALITY_LINES[q.id], checked }
  })
}

/**
 * The sentence the Quality panel opens with: what is playing now, in words.
 * "Playing the file as it is, in 4K. Nothing is being converted."
 */
export function qualitySentence(s: Pick<FilePlaybackState, 'mode' | 'video' | 'quality'>): string | undefined {
  const lowered = s.quality.id === AUTO && s.quality.autoStep && s.quality.autoStep !== 'original' ? stepName(s.quality.autoStep) : undefined
  const at = s.video ? `, in ${s.video}` : ''
  switch (s.mode) {
    case 'direct': return `Playing the file as it is${at}. Nothing is being converted.`
    case 'remux': return `Playing the picture and sound as they are${at}. Only how they are packed changes for this TV.`
    case 'audio': return `Playing the picture as it is${at}. The sound is converted for this TV.`
    case 'transcode': return lowered ? `Converted to ${lowered} for your connection.` : `The server is converting this for the TV${at}.`
    default: return undefined
  }
}

const MODE_WORDS: Record<string, string> = { direct: 'Direct play', remux: 'Direct stream', audio: 'Audio converted', transcode: 'Converted' }

/** Stream details, one press away: the numbers, for whoever looks after the server. */
export function streamDetails(s: Pick<FilePlaybackState, 'mode' | 'video' | 'stream' | 'quality' | 'audio'>): { label: string; value: string }[] {
  const audio = s.audio.options.find((a) => a.index === s.audio.selected) ?? s.audio.options[0]
  return [
    { label: 'How it plays', value: s.mode ? MODE_WORDS[s.mode] ?? s.mode : '' },
    { label: 'Picture', value: s.video ?? '' },
    { label: 'Sound', value: audio?.detail ?? '' },
    { label: 'Quality', value: qualityDetail(s.quality) },
    { label: 'The whole line', value: s.stream ?? '' },
  ].filter((r) => r.value)
}

/** The OpenSubtitles credit the provider asks for, in our words. */
export const OPEN_SUBTITLES_CREDIT = 'Subtitles provided by OpenSubtitles.com'

export type OnlineRow = { id: string; label: string; detail?: string; state: 'get' | 'busy' | 'off' }

export type SubtitlePanel = {
  off: PickRow
  /** The file's own tracks (and sidecars). */
  own: PickRow[]
  /** OpenSubtitles: what was downloaded for this file, what can be, and the line under them. */
  online?: { downloaded: PickRow[]; results: OnlineRow[]; note?: string; credit: string }
}

/**
 * The Subtitles panel: Off, the file's own under "In this
 * file", then OpenSubtitles when the server has it: downloaded tracks, the
 * search's results (OK downloads one and turns it on), why they can't be
 * downloaded, and the credit with the downloads left today.
 */
export function subtitlePanel(subtitles: SubtitleChoices): SubtitlePanel {
  const row = (o: SubtitleChoices['options'][number]): PickRow => ({ id: o.id, label: o.label, detail: o.detail, checked: o.id === subtitles.selected })
  const off: PickRow = { id: 'off', label: 'Off', checked: subtitles.selected === undefined }
  const online = subtitles.online
  const own = subtitles.options.filter((o) => o.source !== 'opensubtitles').map(row)
  if (!online?.available) return { off, own }
  const downloaded = subtitles.options.filter((o) => o.source === 'opensubtitles')
  const canGet = online.canDownload && online.remaining > 0
  const results: OnlineRow[] = online.results
    .filter((r) => !r.trackId || !downloaded.some((d) => d.id === r.trackId))
    .slice(0, 12)
    .map((r) => ({ id: r.id, label: r.label, detail: r.detail, state: online.downloading === r.id ? 'busy' : canGet && !online.downloading ? 'get' : 'off' }))
  const note = online.error ?? (!online.canDownload ? 'Only admins can download subtitles on this server.'
    : online.remaining <= 0 ? 'No downloads left today.'
    : results.length === 0 && downloaded.length === 0 ? 'Nothing found online in these languages.' : undefined)
  const left = online.canDownload && online.remaining > 0 ? `${SEP}${online.remaining} download${online.remaining === 1 ? '' : 's'} left today` : ''
  return { off, own, online: { downloaded: downloaded.map(row), results, note, credit: OPEN_SUBTITLES_CREDIT + left } }
}

/** Up next's kicker: "Up next · starts in 12 seconds", or "Up next" waiting for OK. */
export function nextKicker(next: Pick<NonNullable<FilePlaybackState['next']>, 'seconds'>): string {
  if (next.seconds === undefined) return 'Up next'
  return `Up next${SEP}starts in ${next.seconds} ${next.seconds === 1 ? 'second' : 'seconds'}`
}

/** Up next's title: "Episode 8 · Ebb"; a season finale says so. */
export const nextTitle = (episode: Pick<UpNext, 'episodeNumber' | 'title' | 'seasonFinale'>) =>
  [`Episode ${episode.episodeNumber}`, episode.title].filter(Boolean).join(SEP) + (episode.seasonFinale ? `${SEP}season finale` : '')

/** Up next's line: "50 min · ends at 9:36 PM", from now; nothing when the length isn't known. */
export function nextLine(episode: Pick<UpNext, 'runtimeMin'>, now: number, locale?: string): string | undefined {
  if (!episode.runtimeMin) return undefined
  const ends = endsAtWords(now, episode.runtimeMin * 60_000, locale)
  return `${episode.runtimeMin} min${SEP}${ends.charAt(0).toLowerCase()}${ends.slice(1)}`
}

/** The line at the foot while the credits roll: "Harbor Lights · S3 E7 · The Long Dark · credits". */
export function creditsLine(title: string, line?: string): string {
  return [title, line, 'credits'].filter(Boolean).join(SEP)
}

/** The Next episode card's button: the countdown with autoplay, else waiting for OK (kept for the countdown's fill). */
export function nextButton(next: NonNullable<FilePlaybackState['next']>): string {
  return next.seconds === undefined ? 'Play next episode' : `Next episode in ${next.seconds}`
}

/** How far the countdown has run, 0–1. */
export function nextFill(next: NonNullable<FilePlaybackState['next']>, countdownMs = 10_000): number {
  return next.countdownMs === undefined ? 0 : Math.min(1, Math.max(0, 1 - next.countdownMs / countdownMs))
}

/** "50 min", the card's badge; nothing when the runtime isn't known. */
export const runtimeWords = (min: number | undefined) => min ? `${min} min` : undefined

/** The red label beside the pill while a recording is still recording: "Recording · 24 min so far". */
export function recordingLabel(writtenMs: number): string {
  const min = Math.floor(writtenMs / 60_000)
  return `Recording${SEP}${min < 1 ? 'under a minute' : `${min} min`} so far`
}

type RecordingFacts = { number: string; channelName: string; start: string; end: string; season?: number; episode?: number; episodeTitle?: string }

/** "today", "yesterday", or the weekday, for when a recording was made. */
function dayWords(at: number, today: number, locale?: string): string {
  const day = (t: number) => { const d = new Date(t); return new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime() }
  const days = Math.round((day(today) - day(at)) / 86_400_000)
  return days === 0 ? 'today' : days === 1 ? 'yesterday' : new Intl.DateTimeFormat(locale, { weekday: 'long' }).format(at)
}

/**
 * A recording's line under its title: the episode and where it was recorded
 * ("Season 3 · Episode 5 · Lantern Street · recorded Tuesday on 4.2"), or the
 * channel and its times ("2.1 HRBR · today, 6:00 – 6:30 PM"). While it
 * records, the red label beside the title says so.
 */
export function recordingLine(r: RecordingFacts, locale?: string, today = Date.now()): string {
  const start = Date.parse(r.start), end = Date.parse(r.end)
  const day = Number.isFinite(start) ? dayWords(start, today, locale) : ''
  if (r.season !== undefined && r.episode !== undefined) return [episodeLine(r.season, r.episode, r.episodeTitle ?? ''), ['recorded', day, 'on', r.number].filter(Boolean).join(' ')].join(SEP)
  const when = Number.isFinite(start) && Number.isFinite(end) ? `${day}, ${timeRange(start, end, ' – ', locale)}` : ''
  return [[r.number, r.channelName].filter(Boolean).join(' '), when].filter(Boolean).join(SEP)
}

/**
 * The line over the bar, at its right: "29 min left · Ends at 8:41 PM"; while
 * seeking, what would be left from the target; a recording still recording,
 * how far it has got and when it ends.
 */
export function timeWords(p: { positionMs: number; durationMs: number; timelineMs: number; targetMs?: number; recordingNow: boolean }, now: number, locale?: string): { strong: string; soft?: string } | undefined {
  if (p.recordingNow) {
    const plannedEnd = now + Math.max(0, p.timelineMs - p.durationMs)
    return { strong: `Recorded up to ${timeOfDay(now, locale)}`, soft: `It ends at ${timeOfDay(plannedEnd, locale)}` }
  }
  if (p.durationMs <= 0) return undefined
  if (p.targetMs !== undefined) return { strong: `${leftWords(Math.max(0, p.durationMs - p.targetMs))} from there` }
  const left = Math.max(0, p.durationMs - p.positionMs)
  return { strong: leftWords(left), soft: endsAtWords(now, left, locale) }
}

/** The bar's elapsed time: "18:02". */
export const elapsedWords = (ms: number) => clock(ms)

/** Where a start lands and what is left, while it starts: "Starting where you left off", "18 minutes in · 29 min left". */
export function startingWords(startMs: number | undefined, durationMs: number): { title: string; line?: string } {
  const min = Math.floor((startMs ?? 0) / 60_000)
  if (min < 1) return { title: 'Starting' }
  const left = durationMs > 0 ? leftWords(Math.max(0, durationMs - (startMs ?? 0))) : undefined
  return { title: 'Starting where you left off', line: [`${min} ${min === 1 ? 'minute' : 'minutes'} in`, left].filter(Boolean).join(SEP) }
}

/** The recording's line under the controls: ad breaks found, or when they can be skipped. */
export function adBreaksWords(segments: FilePlaybackState['segments'], recordingNow: boolean): string | undefined {
  if (recordingNow) return 'Ad breaks can be skipped once it has finished recording.'
  const breaks = segments?.filter((s) => s.kind === 'commercial').length ?? 0
  return breaks ? `${breaks} ad break${breaks === 1 ? '' : 's'} found` : undefined
}

export type PlayerKind = 'file' | 'episode' | 'trailer' | 'recording'

type VersionFile = { id: number; width?: number; height?: number; status?: string }

/**
 * The version to offer when one can't play: another file of the same title,
 * the largest one smaller than the one that failed (the likeliest to play
 * without converting), else the nearest larger one. "1080p", or "other"
 * without a size.
 */
export function otherVersion(files: readonly VersionFile[] | undefined, failedId: number): { fileId: number; label: string } | undefined {
  const others = (files ?? []).filter((f) => f.id !== failedId && f.status !== 'missing')
  if (!others.length) return undefined
  const failed = files?.find((f) => f.id === failedId)?.height ?? Infinity
  const smaller = others.filter((f) => (f.height ?? 0) < failed).sort((a, b) => (b.height ?? 0) - (a.height ?? 0))
  const larger = others.filter((f) => (f.height ?? 0) >= failed).sort((a, b) => (a.height ?? 0) - (b.height ?? 0))
  const pick = smaller[0] ?? larger[0] ?? others[0]
  return { fileId: pick.id, label: resolutionLabel(pick.width, pick.height) || 'other' }
}

/** The Episodes panel's OK hint. */
export function episodesOkHint(onTab: boolean, onPlaying: boolean): string {
  if (onTab) return '◀ ▶ changes season at once; ▼ goes to its episodes'
  return onPlaying ? 'Goes back to the video where it is' : 'Plays it; your audio and subtitle choices carry over'
}
