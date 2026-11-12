import type { components } from '../api/schema'
import { relativeTime } from './format'

type BrowseItem = components['schemas']['BrowseItem']
type EpisodeCard = components['schemas']['EpisodeCard']

// What the parts of a Home or Overview card link to (PLAN-HOME.md §6.1,
// PLAN-SHOW-PAGE.md §4.1): the show's title opens the show, the season part
// the show on that season, the episode part and the picture the episode's
// address, which puts that episode on top of the show's page.

/** A card part's destination, as the router's Link takes it. */
export type CardTarget =
  | { to: '/movies/$movieId'; params: { movieId: string } }
  | { to: '/shows/$showId'; params: { showId: string } }
  | { to: '/shows/$showId/seasons/$number'; params: { showId: string; number: string } }
  | { to: '/shows/$showId/seasons/$number/episodes/$episodeId'; params: { showId: string; number: string; episodeId: string } }

/** A run of a card's text line: a link when it has a target, with a label naming the whole destination; plain text otherwise. */
export type CardSegment = { text: string; to?: CardTarget; label?: string }

export type CardLinks = {
  /** The picture's target and what it names; a bigger pointer target for a line's link, not a tab stop. */
  picture: { to: CardTarget; label: string }
  /** The first line: the title, linked. */
  title: CardSegment
  /** The second line, in runs. */
  line: CardSegment[]
  /** A third line, plain: "Aired yesterday" on a Recently aired card. */
  third?: string
}

const SEP: CardSegment = { text: ' · ' }

const seasonName = (n: number) => (n === 0 ? 'Specials' : `season ${n}`)

const showTarget = (showId: number): CardTarget => ({ to: '/shows/$showId', params: { showId: String(showId) } })
const seasonTarget = (showId: number, season: number): CardTarget => ({
  to: '/shows/$showId/seasons/$number',
  params: { showId: String(showId), number: String(season) },
})
const episodeTarget = (showId: number, season: number, episodeId: number): CardTarget => ({
  to: '/shows/$showId/seasons/$number/episodes/$episodeId',
  params: { showId: String(showId), number: String(season), episodeId: String(episodeId) },
})

/**
 * A poster card's parts. `tail` is the plain text that ends the second line
 * ("added today" in a Recently added row, a list's year); without one the
 * line is the title's year and count, as grids show it.
 *
 * A Recently added show card's subtitle says what arrived: "S03E07" with
 * `episodeId` and `seasonNumber` is one episode, so the season and the
 * episode are links and the picture opens the episode;
 * "Season 1" is a whole season, so the season is a link and the picture
 * opens it; "Complete series" and "6 episodes" link the show only.
 */
export function posterLinks(item: BrowseItem, tail?: string): CardLinks {
  if (item.kind === 'movie') {
    const to: CardTarget = { to: '/movies/$movieId', params: { movieId: String(item.id) } }
    const text = tail ?? [item.year || '', item.fileCount && item.fileCount > 1 ? `${item.fileCount} versions` : ''].filter(Boolean).join(' · ')
    return { picture: { to, label: item.title }, title: { text: item.title, to, label: item.title }, line: text ? [{ text }] : [] }
  }
  const show = showTarget(item.id)
  const title: CardSegment = { text: item.title, to: show, label: item.title }
  const parts = addedParts(item)
  if (!parts) {
    const text = tail ?? [item.year || '', item.episodeCount ? `${item.episodeCount} ep` : ''].filter(Boolean).join(' · ')
    return { picture: { to: show, label: item.title }, title, line: text ? [{ text }] : [] }
  }
  const line = tail ? [...parts.line, SEP, { text: tail }] : parts.line
  return { picture: parts.picture, title, line }
}

function addedParts(item: BrowseItem): Pick<CardLinks, 'picture' | 'line'> | undefined {
  const sub = item.subtitle
  if (!sub) return undefined
  const one = /^S(\d+)E(\d+)$/.exec(sub)
  if (one && item.episodeId !== undefined && item.seasonNumber !== undefined) {
    const season = item.seasonNumber
    const episode = Number(one[2])
    const where = `${item.title}, ${seasonName(season)}`
    const label = `${where}, episode ${episode}`
    return {
      picture: { to: episodeTarget(item.id, season, item.episodeId), label },
      line: [
        { text: `S${one[1]}`, to: seasonTarget(item.id, season), label: where },
        SEP,
        { text: `E${one[2]}`, to: episodeTarget(item.id, season, item.episodeId), label },
      ],
    }
  }
  const whole = /^Season (\d+)$/.exec(sub)
  if (whole) {
    const season = Number(whole[1])
    const label = `${item.title}, ${seasonName(season)}`
    return { picture: { to: seasonTarget(item.id, season), label }, line: [{ text: sub, to: seasonTarget(item.id, season), label }] }
  }
  // "Complete series", "6 episodes", or a line this version doesn't know: the show.
  return { picture: { to: showTarget(item.id), label: item.title }, line: [{ text: sub }] }
}

/**
 * A Recently aired card's parts: the show's title opens the show; "S03"
 * the season; "E07" and the still the episode, the
 * episode's title following in plain text; the third line says when it aired.
 */
export function episodeLinks(ep: EpisodeCard): CardLinks {
  const where = `${ep.showTitle}, ${seasonName(ep.seasonNumber)}`
  const label = `${where}, episode ${ep.episodeNumber}${ep.title ? `, ${ep.title}` : ''}`
  const at = episodeTarget(ep.showId, ep.seasonNumber, ep.episodeId)
  const pad = (n: number) => String(n).padStart(2, '0')
  return {
    picture: { to: at, label },
    title: { text: ep.showTitle, to: showTarget(ep.showId), label: ep.showTitle },
    line: [
      { text: `S${pad(ep.seasonNumber)}`, to: seasonTarget(ep.showId, ep.seasonNumber), label: where },
      SEP,
      { text: `E${pad(ep.episodeNumber)}`, to: at, label },
      ...(ep.title ? [{ text: ` ${ep.title}` }] : []),
    ],
    third: ep.airDate ? `Aired ${relativeTime(ep.airDate)}` : undefined,
  }
}
