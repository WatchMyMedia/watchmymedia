import type { components } from '../api/schema'
import { formatDate, formatRuntime } from './format'
import { leftWords } from './endTime'
import { episodePath } from './showPage'

type BrowseItem = components['schemas']['BrowseItem']
type ContinueItem = components['schemas']['ContinueItem']

// The facts a card says beside its title, in words: what a viewer wants
// to know before opening it. Quality stays off cards (it is a fact on the
// title's page); a library may offer it as a choice of its own.

/** "2025 · 1 h 58 min"; the year alone without a runtime. */
export function movieLine(item: Pick<BrowseItem, 'year' | 'runtimeMin'>): string {
  return [item.year ? String(item.year) : '', item.runtimeMin ? formatRuntime(item.runtimeMin) : ''].filter(Boolean).join(' · ')
}

/** "4K HDR", "1080p", or empty: the best version's picture, in the words a viewer knows. */
export function qualityWords(quality?: string, hdr?: boolean): string {
  return [quality ?? '', hdr ? 'HDR' : ''].filter(Boolean).join(' ')
}

/** "★ 7.2", or empty for no score. */
export function scoreWords(rating?: number): string {
  return rating && rating > 0 ? `★ ${rating.toFixed(1)}` : ''
}

/** A show's state for a card or a header: "Next episode Dec 1", "Ended", "Returning". */
export function showStatusWords(item: Pick<BrowseItem, 'status' | 'nextAirDate'>, now = Date.now()): string {
  if (item.nextAirDate && Date.parse(item.nextAirDate) >= now - 86_400_000) return `Next episode ${shortDate(item.nextAirDate, now)}`
  switch (item.status) {
    case 'Ended':
      return 'Ended'
    case 'Canceled':
      return 'Cancelled'
    case 'Returning Series':
      return 'Returning'
    case 'In Production':
    case 'Planned':
      return 'Coming'
    default:
      return ''
  }
}

/** "Dec 1", or "Dec 1, 2027" for another year. */
export function shortDate(iso: string, now = Date.now()): string {
  const day = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso)
  const d = day ? new Date(Number(day[1]), Number(day[2]) - 1, Number(day[3])) : new Date(iso)
  if (Number.isNaN(d.getTime())) return formatDate(iso)
  const sameYear = d.getFullYear() === new Date(now).getFullYear()
  return d.toLocaleDateString(undefined, sameYear ? { month: 'short', day: 'numeric' } : { month: 'short', day: 'numeric', year: 'numeric' })
}

/** The hover card's facts: year, length, content rating and score. Episodes and seasons are the show's page's. */
export function peekFacts(item: Pick<BrowseItem, 'kind' | 'year' | 'runtimeMin' | 'contentRating' | 'rating' | 'episodeCount'>): string[] {
  const length = item.kind === 'movie' ? (item.runtimeMin ? formatRuntime(item.runtimeMin) : '') : item.episodeCount ? `${item.episodeCount} ${item.episodeCount === 1 ? 'episode' : 'episodes'}` : ''
  return [item.year ? String(item.year) : '', length, item.contentRating ?? '', scoreWords(item.rating)].filter(Boolean)
}

/** The words on a Continue watching card, from the server's title and "S03E07 The Long Dark" subtitle. */
export type ContinueWords = {
  /** "Harbor Lights · Season 3 · Episode 7" or "Movie · 2019", over the featured card's heading. */
  eyebrow: string
  /** The featured card's heading: the episode's title, or the movie's. */
  heading: string
  /** A listed card's first line: the show or the movie. */
  title: string
  /** A listed card's second line: "S3 E7 · The Long Dark", "Movie · 2019". */
  line: string
  /** "29 min left". */
  left?: string
  /** How the feature reads aloud. */
  label: string
}

export function continueWords(item: Pick<ContinueItem, 'kind' | 'title' | 'subtitle' | 'seasonNumber' | 'episodeNumber' | 'positionMs' | 'durationMs'>): ContinueWords {
  const left = leftWords(item.positionMs, item.durationMs)
  if (item.kind !== 'episode') {
    const year = item.subtitle && /^\d{4}$/.test(item.subtitle) ? item.subtitle : ''
    const line = ['Movie', year].filter(Boolean).join(' · ')
    return { eyebrow: line, heading: item.title, title: item.title, line, left, label: item.title }
  }
  const episodeTitle = (item.subtitle ?? '').replace(/^S\d+E\d+\s*/, '').trim()
  const s = item.seasonNumber ?? 0
  const e = item.episodeNumber ?? 0
  const code = s || e ? `S${s} E${e}` : ''
  const seasonPart = s === 0 ? 'Specials' : `Season ${s}`
  return {
    eyebrow: [item.title, s || e ? seasonPart : '', e ? `Episode ${e}` : ''].filter(Boolean).join(' · '),
    heading: episodeTitle || item.title,
    title: item.title,
    line: [code, episodeTitle].filter(Boolean).join(' · '),
    left,
    label: [item.title, s ? `season ${s}` : '', e ? `episode ${e}` : '', episodeTitle].filter(Boolean).join(', '),
  }
}

/** Where Back from the player goes after a Continue watching item: the movie, or the episode on top of its show's page. */
export function continueBack(item: Pick<ContinueItem, 'kind' | 'movieId' | 'showId' | 'seasonNumber' | 'episodeId'>): string {
  if (item.kind === 'movie' && item.movieId != null) return `/movies/${item.movieId}`
  if (item.showId != null && item.seasonNumber != null && item.episodeId != null) return episodePath(item.showId, item.seasonNumber, item.episodeId)
  if (item.showId != null) return `/shows/${item.showId}`
  return '/'
}
