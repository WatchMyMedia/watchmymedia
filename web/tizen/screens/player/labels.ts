import { timeRange } from '../../../src/lib/liveGuide'
import { AUTO, autoNow, stepName } from '../../../src/lib/quality'
import type { FilePlaybackState, UpNext } from '../../player'

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

/** "S03 · E07 · The Long Dark", under the title. */
export const episodeLine = (season: number, episode: number, title: string) => `S${two(season)}${SEP}E${two(episode)}${title ? `${SEP}${title}` : ''}`

/** The Audio button's detail: the track playing, by its language. */
export function audioDetail(audio: FilePlaybackState['audio']): string | undefined {
  return (audio.options.find((a) => a.index === audio.selected) ?? audio.options[0])?.label
}

/** The Subtitles button's detail: the track showing, or Off. */
export function subtitleDetail(subtitles: SubtitleChoices): string {
  return subtitles.options.find((s) => s.id === subtitles.selected)?.label ?? 'Off'
}

/** The Quality button's detail: "Auto", "Auto · 720p" once Automatic went down a step, "Original", "1080p". */
export function qualityDetail(quality: FilePlaybackState['quality']): string {
  if (quality.id === AUTO) return quality.autoStep && quality.autoStep !== 'original' ? `Auto${SEP}${stepName(quality.autoStep)}` : 'Auto'
  return stepName(quality.id)
}

export type PickRow = { id: string; label: string; detail?: string; checked: boolean }

/**
 * The Quality panel: Automatic (saying what it sends now, once picked),
 * Original, then the fixed steps with their bitrates.
 */
export function qualityRows(quality: FilePlaybackState['quality']): PickRow[] {
  return quality.options.map((q) => {
    const checked = q.id === quality.id
    if (q.id === AUTO) return { id: q.id, label: 'Automatic', detail: checked && quality.autoStep ? autoNow(quality.autoStep) : 'Original first, lower when it can’t keep up', checked }
    if (q.id === 'original') return { id: q.id, label: 'Original', detail: 'Direct play when it can', checked }
    return { id: q.id, label: stepName(q.id), detail: q.label.split(SEP)[1], checked }
  })
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

/** The Next episode card's button: the countdown with autoplay, else waiting for OK. */
export function nextButton(next: NonNullable<FilePlaybackState['next']>): string {
  return next.seconds === undefined ? 'Play next episode' : `Next episode in ${next.seconds}`
}

/** How far the card's button has filled, 0–1, over the countdown. */
export function nextFill(next: NonNullable<FilePlaybackState['next']>, countdownMs = 10_000): number {
  return next.countdownMs === undefined ? 0 : Math.min(1, Math.max(0, 1 - next.countdownMs / countdownMs))
}

/** The card's second line: the show, and a season finale says so. */
export const nextMeta = (episode: UpNext) => episode.showTitle + (episode.seasonFinale ? `${SEP}season finale` : '')

/** "50 min", the card's badge; nothing when the runtime isn't known. */
export const runtimeWords = (min: number | undefined) => min ? `${min} min` : undefined

/** The red label beside the pill while a recording is still recording: "Recording · 24 min so far". */
export function recordingLabel(writtenMs: number): string {
  const min = Math.floor(writtenMs / 60_000)
  return `Recording${SEP}${min < 1 ? 'under a minute' : `${min} min`} so far`
}

type RecordingFacts = { number: string; channelName: string; start: string; end: string; season?: number; episode?: number; episodeTitle?: string }

/**
 * A recording's line under its title: the episode and where it was recorded
 * ("S03 · E05 · Lantern Street · recorded Mon on 4.2"), or the channel and
 * its times ("2.1 HRBR-HD · Tue 6:00–6:30 PM"), with "recording" while it is.
 */
export function recordingLine(r: RecordingFacts, now: boolean, locale?: string): string {
  const start = Date.parse(r.start), end = Date.parse(r.end)
  const day = Number.isFinite(start) ? new Intl.DateTimeFormat(locale, { weekday: 'short' }).format(start) : ''
  const parts = r.season !== undefined && r.episode !== undefined
    ? [episodeLine(r.season, r.episode, r.episodeTitle ?? ''), ['recorded', day, 'on', r.number].filter(Boolean).join(' ')]
    : [[r.number, r.channelName].filter(Boolean).join(' '), Number.isFinite(start) && Number.isFinite(end) ? `${day} ${timeRange(start, end, '–', locale)}` : '']
  if (now) parts.push('recording')
  return parts.filter(Boolean).join(SEP)
}

/** The recording's line at the end of the controls: ad breaks found, or when they will be. */
export function adBreaksWords(segments: FilePlaybackState['segments'], recordingNow: boolean): string | undefined {
  if (recordingNow) return 'Ad breaks are found once it has finished recording'
  const breaks = segments?.filter((s) => s.kind === 'commercial').length ?? 0
  return breaks ? `${breaks} ad break${breaks === 1 ? '' : 's'} found` : undefined
}

export type PlayerKind = 'file' | 'episode' | 'trailer' | 'recording'

/** The hint line at the foot of the controls; recordings have none. */
export function hintWords(kind: PlayerKind, seeking: boolean): { seek: string; ok: string; back: string } | undefined {
  if (kind === 'recording') return undefined
  return {
    seek: seeking ? '10 s a press, held 30 s, 1 min, 2 min · seeks when you let go' : 'seek 10 s, hold for more',
    ok: kind === 'trailer' ? 'plays or pauses' : 'plays or pauses, as Play/Pause does',
    back: kind === 'trailer' ? 'hides the controls, then back to the movie' : 'closes a panel, then hides the controls, then leaves',
  }
}

/** The Episodes panel's OK hint. */
export function episodesOkHint(onTab: boolean, onPlaying: boolean): string {
  if (onTab) return '◀ ▶ changes season at once; ▼ goes to its episodes'
  return onPlaying ? 'Goes back to the video where it is' : 'Plays it; your audio and subtitle choices carry over'
}
