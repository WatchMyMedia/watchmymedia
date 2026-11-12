import type { components } from '../api/schema'
import { formatDate, formatDuration, languageName, seCode } from './format'

type Episode = components['schemas']['Episode']
type MediaInfo = components['schemas']['MediaInfo']
type BrowseItem = components['schemas']['BrowseItem']

/** Partway through: a saved position short of the end. */
export function isPartlyWatched(e: Pick<Episode, 'watch'>): boolean {
  return !!e.watch && !e.watch.completed && e.watch.positionMs > 0
}

/** Length in minutes: TMDB's runtime, else the best file's. */
export function episodeMinutes(e: Pick<Episode, 'runtimeMin' | 'files'>): number {
  if (e.runtimeMin) return e.runtimeMin
  const ms = e.files[0]?.durationMs ?? 0
  return ms ? Math.round(ms / 60_000) : 0
}

/** Minutes left of a partly watched episode, at least 1; 0 when the length is unknown. */
export function minutesLeft(e: Pick<Episode, 'watch' | 'files'>): number {
  if (!e.watch || !isPartlyWatched(e)) return 0
  const file = e.files.find((f) => f.id === e.watch?.fileId) ?? e.files[0]
  const durationMs = e.watch.durationMs || file?.durationMs || 0
  return durationMs > e.watch.positionMs ? Math.max(1, Math.round((durationMs - e.watch.positionMs) / 60_000)) : 0
}

/** The episode page's meta line, before its Watched mark: "Aired Oct 28, 2026", "52 min", "★ 8.4", "29 min left". */
export function episodeFacts(e: Pick<Episode, 'aired' | 'airDate' | 'runtimeMin' | 'files' | 'rating' | 'watch'>): string[] {
  const min = episodeMinutes(e)
  const left = minutesLeft(e)
  return [
    e.airDate ? `${e.aired ? 'Aired' : 'Airs'} ${formatDate(e.airDate)}` : '',
    min ? `${min} min` : '',
    e.rating ? `★ ${e.rating.toFixed(1)}` : '',
    left ? `${left} min left` : '',
  ].filter(Boolean)
}

/** The audio's channel layout and the embedded subtitles' languages, for the tech line: "5.1", "Subtitles: English, Spanish". */
export function trackSummary(probe: Pick<MediaInfo, 'audio' | 'subtitles'> | undefined): { channels: string; subtitles: string } {
  const audio = probe?.audio.find((a) => a.default) ?? probe?.audio[0]
  const layout = audio?.channelLayout?.replace(/\(.*\)$/, '') ?? ''
  const channels = /^\d\.\d$/.test(layout) ? layout : layout === 'stereo' ? '2.0' : layout === 'mono' ? '1.0' : ''
  const langs = [...new Set((probe?.subtitles ?? []).filter((s) => s.language && s.language !== 'und').map((s) => languageName(s.language)))]
  return { channels, subtitles: langs.length ? `Subtitles: ${langs.join(', ')}` : '' }
}

/** A link to the player: no start resumes from the saved position by itself, 0 is the beginning. */
export type PlayTarget = { label: string; fileId: number; start?: number }

export type EpisodeActions = {
  /** The main action, or a label saying why there is nothing to play. */
  primary: ({ kind: 'play' } & PlayTarget) | { kind: 'unavailable'; label: string }
  /** Partway through: start over instead. */
  fromStart?: PlayTarget
  /** With a file: toggles the watched mark. `watched` is what pressing it sets. */
  mark?: { label: string; watched: boolean }
}

/**
 * What an episode's page offers, by whether it has a file, has aired and how
 * far it was watched (PLAN-EPISODE-PAGE.md §2): "Play · 52 min", or "Resume
 * from 23:10" and "From the start"; Mark watched or Mark unwatched beside it.
 */
export function episodeActions(e: Pick<Episode, 'aired' | 'airDate' | 'hasFile' | 'files' | 'watch' | 'runtimeMin'>): EpisodeActions {
  const best = e.files[0]
  if (!e.hasFile || !best) {
    const label = e.aired ? 'Not in library' : e.airDate ? `Airs ${formatDate(e.airDate)}` : 'Not aired yet'
    return { primary: { kind: 'unavailable', label } }
  }
  const mark = e.watch?.completed ? { label: 'Mark unwatched', watched: false } : { label: 'Mark watched', watched: true }
  if (e.watch && isPartlyWatched(e)) {
    // Resume the version the saved position belongs to, at that position.
    const file = e.files.find((f) => f.id === e.watch?.fileId) ?? best
    return {
      primary: { kind: 'play', label: `Resume from ${formatDuration(e.watch.positionMs)}`, fileId: file.id, start: e.watch.positionMs / 1000 },
      fromStart: { label: 'From the start', fileId: file.id, start: 0 },
      mark,
    }
  }
  const min = episodeMinutes(e)
  return { primary: { kind: 'play', label: min ? `Play · ${min} min` : 'Play', fileId: best.id }, mark }
}

const airsLater = (e: Pick<Episode, 'aired' | 'airDate' | 'hasFile'>) => (!e.aired && !e.hasFile && e.airDate ? ` · Airs ${formatDate(e.airDate)}` : '')

/** "E06", and for one not aired yet "E08 · Airs Nov 4, 2026": the season row's code line. */
export function rowCode(e: Pick<Episode, 'number' | 'aired' | 'airDate' | 'hasFile'>): string {
  return `E${String(e.number).padStart(2, '0')}${airsLater(e)}`
}

/** "Harbor Lights · S03 E07": the page's kicker and the delete dialog's subtitle. */
export function episodeLine(show: string, season: number, episode: number): string {
  return `${show} · ${seCode(season, episode).replace('E', ' E')}`
}

/** "2022–" while it runs, "2019–2023" once ended, "2022" with one date. */
export function showYears(s: { year?: number; lastAirDate?: string; status?: string }): string {
  if (!s.year) return ''
  if (!s.lastAirDate) return String(s.year)
  return `${s.year}–${s.status === 'Ended' ? s.lastAirDate.slice(0, 4) : ''}`
}

/**
 * The episode a Recently added show card stands for when it was the only one
 * added (PLAN-EPISODE-PAGE.md §10): the card opens that episode's page rather
 * than the show's. Season 0 is Specials, so only a missing number means none.
 */
export function addedEpisode(item: Pick<BrowseItem, 'kind' | 'id' | 'episodeId' | 'seasonNumber'>): { showId: string; number: string; episodeId: string } | undefined {
  if (item.kind !== 'show' || item.episodeId === undefined || item.seasonNumber === undefined) return undefined
  return { showId: String(item.id), number: String(item.seasonNumber), episodeId: String(item.episodeId) }
}

/**
 * The season's episodes as the show page's row lists them: those on disk and
 * those still to air, or every one with Show missing on, and always `keep`,
 * the one on top.
 */
export function pageEpisodes<E extends Pick<Episode, 'id' | 'hasFile' | 'aired'>>(episodes: E[], keep: number, all: boolean): E[] {
  return all ? episodes : episodes.filter((e) => e.hasFile || !e.aired || e.id === keep)
}
