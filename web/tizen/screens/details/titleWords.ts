import type { components } from '../../../src/api/schema'
import { episodeMinutes, isPartlyWatched, minutesLeft } from '../../../src/lib/episode'
import { formatRuntime, plural } from '../../../src/lib/format'
import { endsAtWords, leftWords } from '../../ui/timeWords'
import { partway } from './movieLabels'

// What a viewer asks of a title's page, first: what is it, how long, where
// was I, when would it end. The facts lines of a movie, an episode and a
// show, the line under them with the time left and the end time, and the
// season tabs' words.

type MovieDetail = components['schemas']['MovieDetail']
type ShowDetail = components['schemas']['ShowDetail']
type Episode = components['schemas']['Episode']
type Season = components['schemas']['Season']

const SEP = ' · '
const MIN = 60_000

/** "2026 · 2 h 14 min · PG-13 · Thriller · rated 7.6 of 10". */
export function movieFactLine(m: Pick<MovieDetail, 'year' | 'runtimeMin' | 'contentRating' | 'genres' | 'rating'>): string {
  return [
    m.year ? String(m.year) : '', formatRuntime(m.runtimeMin ?? 0), m.contentRating ?? '', m.genres[0] ?? '',
    m.rating ? `rated ${m.rating.toFixed(1)} of 10` : '',
  ].filter(Boolean).join(SEP)
}

/** The short line over a page scrolled past its top: "2026 · 2 h 14 min · PG-13". */
export function movieShortLine(m: Pick<MovieDetail, 'year' | 'runtimeMin' | 'contentRating'>): string {
  return [m.year ? String(m.year) : '', formatRuntime(m.runtimeMin ?? 0), m.contentRating ?? ''].filter(Boolean).join(SEP)
}

/** A calendar day from a bare date or a moment: "November 2", with the year when it isn't this one. */
export function dayName(iso: string, today: Date, locale?: string): string {
  const day = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso)
  const d = day ? new Date(Number(day[1]), Number(day[2]) - 1, Number(day[3])) : new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  const sameYear = d.getFullYear() === today.getFullYear()
  return d.toLocaleDateString(locale, sameYear ? { month: 'long', day: 'numeric' } : { year: 'numeric', month: 'long', day: 'numeric' })
}

/** "today", "yesterday", "tomorrow", else "October 23": when an episode airs, a calendar day. */
export function airDay(airDate: string, today: Date, locale?: string): string {
  const day = /^(\d{4})-(\d{2})-(\d{2})/.exec(airDate)
  if (!day) return dayName(airDate, today, locale)
  const d = Date.UTC(Number(day[1]), Number(day[2]) - 1, Number(day[3]))
  const t = Date.UTC(today.getFullYear(), today.getMonth(), today.getDate())
  const diff = Math.round((d - t) / 86_400_000)
  if (diff === 0) return 'today'
  if (diff === -1) return 'yesterday'
  if (diff === 1) return 'tomorrow'
  return dayName(`${day[1]}-${day[2]}-${day[3]}`, today, locale)
}

/** The line under a title's facts: how far it got and when it would end. */
export type WatchLine = {
  /** The main button: "Resume", "Play", "Play again". */
  play: 'Resume' | 'Play' | 'Play again'
  /** In bold: "1 h 12 min left", "2 h 14 min", "Watched on November 2", "Not watched yet". */
  where: string
  /** Muted after it: "Ends at 9:24 PM", "Ends at 10:26 PM if you start now". */
  ends?: string
  /** How far through, 0–1, while partway. */
  progress?: number
}

/** A movie's line: partway, watched, or not started. */
export function movieWatch(m: Pick<MovieDetail, 'watch' | 'runtimeMin'>, now: number, locale?: string): WatchLine {
  const p = partway(m)
  if (p) {
    const left = p.durationMs - p.positionMs
    return { play: 'Resume', where: leftWords(left), ends: endsAtWords(now, left, locale), progress: Math.min(1, p.positionMs / p.durationMs) }
  }
  if (m.watch?.completed) {
    const on = m.watch.updatedAt ? dayName(m.watch.updatedAt, new Date(now), locale) : ''
    return { play: 'Play again', where: on ? `Watched on ${on}` : 'Watched' }
  }
  const length = (m.runtimeMin ?? 0) * MIN
  return { play: 'Play', where: formatRuntime(m.runtimeMin ?? 0), ends: length ? `${endsAtWords(now, length, locale)} if you start now` : undefined }
}

/** An episode's line; none for one that isn't on disk. */
export function episodeWatch(e: Pick<Episode, 'watch' | 'files' | 'runtimeMin' | 'hasFile'>, now: number, locale?: string): WatchLine | undefined {
  if (!e.hasFile) return undefined
  if (isPartlyWatched(e)) {
    const left = minutesLeft(e) * MIN
    const file = e.files.find((f) => f.id === e.watch?.fileId) ?? e.files[0]
    const d = e.watch?.durationMs || file?.durationMs || 0
    return { play: 'Resume', where: leftWords(left), ends: endsAtWords(now, left, locale), progress: d > 0 ? Math.min(1, (e.watch?.positionMs ?? 0) / d) : undefined }
  }
  if (e.watch?.completed) return { play: 'Play again', where: 'Watched' }
  const length = episodeMinutes(e) * MIN
  return { play: 'Play', where: 'Not watched yet', ends: length ? `${endsAtWords(now, length, locale)} if you start now` : undefined }
}

/** "Harbor Lights · Season 3 · Episode 7", "Harbor Lights · Specials · Episode 2". */
export function episodeKickerWords(show: string, season: number, episode: number): string {
  return [show, season === 0 ? 'Specials' : `Season ${season}`, `Episode ${episode}`].join(SEP)
}

/** "47 min · TV-14 · aired October 23"; one to come, "airs November 18". */
export function episodeFactLine(e: Pick<Episode, 'aired' | 'airDate' | 'runtimeMin' | 'files'>, show: Pick<ShowDetail, 'contentRating'>, today: Date, locale?: string): string {
  const min = episodeMinutes(e)
  const when = e.airDate ? `${e.aired ? 'aired' : 'airs'} ${airDay(e.airDate, today, locale)}` : e.aired ? '' : 'not aired yet'
  return [min ? `${min} min` : '', show.contentRating ?? '', when].filter(Boolean).join(SEP)
}

/** "2022 to now · 3 seasons · TV-14 · Drama · still airing", or "2014 to 2019 · … · ended". */
export function showFactLine(show: Pick<ShowDetail, 'year' | 'lastAirDate' | 'status' | 'seasons' | 'contentRating' | 'genres'>): string {
  const ended = !!show.status && /ended|cancel/i.test(show.status)
  const years = !show.year ? '' : ended && show.lastAirDate && show.lastAirDate.slice(0, 4) !== String(show.year) ? `${show.year} to ${show.lastAirDate.slice(0, 4)}`
    : ended ? String(show.year) : `${show.year} to now`
  const seasons = show.seasons.filter((s) => s.number > 0).length
  const state = !show.status ? '' : /cancel/i.test(show.status) ? 'cancelled' : ended ? 'ended' : 'still airing'
  return [years, seasons ? plural(seasons, 'season') : '', show.contentRating ?? '', show.genres[0] ?? '', state].filter(Boolean).join(SEP)
}

/** A season tab's words after its name: "4 of 6 watched" on the chosen one, the episodes on disk on the rest; none once it is all watched (a tick). */
export function tabWords(t: Pick<Season, 'availableCount' | 'watchedCount' | 'episodeCount'>, chosen: boolean, showMissing: boolean): { count?: string; done: boolean } {
  const done = t.availableCount > 0 && t.watchedCount >= t.availableCount
  if (done && !showMissing) return { done: true }
  if (showMissing) return { count: t.availableCount === 0 ? 'none here' : `${t.availableCount} of ${t.episodeCount} here`, done }
  if (chosen && t.availableCount > 0) return { count: `${t.watchedCount} of ${t.availableCount} watched`, done }
  return { count: String(t.availableCount), done }
}

/** Beside the tabs: "Next episode Thu, Nov 18", from the season's next to air; none when nothing is to come. */
export function nextEpisodeLine(episodes: Pick<Episode, 'number' | 'aired' | 'airDate'>[], today: Date, locale?: string): string | undefined {
  const next = [...episodes].sort((a, b) => a.number - b.number).find((e) => !e.aired && e.airDate)
  if (!next?.airDate) return undefined
  const day = /^(\d{4})-(\d{2})-(\d{2})/.exec(next.airDate)
  const d = day ? new Date(Number(day[1]), Number(day[2]) - 1, Number(day[3])) : new Date(next.airDate)
  if (Number.isNaN(d.getTime())) return undefined
  const near = airDay(next.airDate, today, locale)
  return `Next episode ${near === 'today' || near === 'tomorrow' ? near : d.toLocaleDateString(locale, { weekday: 'short', month: 'short', day: 'numeric' })}`
}

/** An episode card's title: "7 · The Long Dark". */
export const cardTitle = (e: Pick<Episode, 'number' | 'title'>) => `${e.number}${SEP}${e.title || `Episode ${e.number}`}`
