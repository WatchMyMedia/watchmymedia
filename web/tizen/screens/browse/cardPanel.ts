import type { components } from '../../../src/api/schema'
import type { LiveChannel } from '../../../src/api/liveTV'
import { formatDate, formatRuntime, plural } from '../../../src/lib/format'
import { duration, nowAndNext, timeRange } from '../../../src/lib/liveGuide'
import { isListRow, titleParts, type ScreenRow } from '../../../src/lib/screenRows'
import { episodeCode, holdWords, isWatchlistRow, timeLeft, type RowCard } from './rowCards'
import { resumeLine } from './homePages'
import { bareName } from '../search/crossSearch'
import { oneCardWords, type OneCardTag } from './oneCardHome'

// What the panel over a Home row screen says about its focused card
// (Android's CardPanel): a kicker, the title, facts,
// tags, an overview and what OK does, over the card's picture.

type BrowseItem = components['schemas']['BrowseItem']
type ContinueItem = components['schemas']['ContinueItem']
type EpisodeCard = components['schemas']['EpisodeCard']

/** What OK on the panel's card does. */
export type PanelTarget = 'movie' | 'show' | 'episode' | 'resume' | 'channel' | 'guide' | 'list' | 'library' | 'file'

export type CardPanel = {
  kicker: string
  title: string
  /** "2022 · 27 episodes · Drama", joined with " · ". */
  facts: string[]
  /** "4K", "HDR". */
  tags: string[]
  /** "★ 7.8". */
  rating?: string
  overview: string
  target: PanelTarget
  /** The card's 16:9 picture (a TMDB path), when it has one. */
  picture?: string
  /** The placeholder gradient's seed while the picture loads, or without one. */
  seed: string
}

function itemPanel(row: ScreenRow, item: BrowseItem, index: number, card: RowCard): CardPanel {
  const [heading] = titleParts(row)
  const released = formatDate(item.releaseDate)
  // A watchlist's row is in the order titles were saved, so it says which watchlist and what the title is, not a place.
  const kicker = isWatchlistRow(row) ? `${row.title} · ${item.kind === 'show' ? 'TV show' : 'Movie'}`
    : isListRow(row) ? `#${index + 1} on the list`
    : row.style === 'wide' ? (released ? `Released ${released}` : heading)
      : [heading, card.subtitle].filter(Boolean).join(' · ')
  const genres = item.genres.slice(0, 2).join(', ')
  const year = item.year ? String(item.year) : ''
  const facts = item.kind === 'movie' ? [year, formatRuntime(item.runtimeMin ?? 0), genres]
    : item.kind === 'show' ? [year, item.episodeCount ? plural(item.episodeCount, 'episode') : '', genres]
      : [item.fileCount ? plural(item.fileCount, 'file') : '']
  // A title only another server has says so at the end of its facts: "… · On Cabin".
  const on = oneCardWords((item as { oneCard?: OneCardTag }).oneCard, bareName)
  return {
    kicker,
    title: item.title,
    facts: [...facts, on ?? ''].filter(Boolean),
    tags: [item.quality || '', item.hdr ? 'HDR' : ''].filter(Boolean),
    rating: item.rating ? `★ ${item.rating.toFixed(1)}` : undefined,
    overview: item.overview,
    target: item.kind === 'movie' ? 'movie' : item.kind === 'show' ? (item.episodeId !== undefined ? 'episode' : 'show') : 'file',
    picture: item.backdropPath,
    seed: item.title,
  }
}

/**
 * When an episode aired, by the calendar: "today", "yesterday", "3 days
 * ago", else the date. An air date is a day, not a moment, so it is never
 * "3 h ago".
 */
export function airedDay(airDate: string, now = Date.now()): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(airDate)
  if (!m) return formatDate(airDate)
  const today = new Date(now)
  const days = Math.round((new Date(today.getFullYear(), today.getMonth(), today.getDate()).getTime() - new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3])).getTime()) / 86_400_000)
  if (days === 0) return 'today'
  if (days === 1) return 'yesterday'
  if (days > 1 && days < 7) return `${days} days ago`
  return formatDate(`${m[1]}-${m[2]}-${m[3]}`)
}

function episodePanel(row: ScreenRow, e: EpisodeCard): CardPanel {
  const [heading] = titleParts(row)
  return {
    kicker: [e.isNew ? 'New episode' : heading, e.airDate ? `aired ${airedDay(e.airDate)}` : ''].filter(Boolean).join(' · '),
    title: e.title || e.showTitle,
    facts: [e.showTitle, episodeCode(e.seasonNumber, e.episodeNumber), e.hasFile ? '' : 'Not in library', oneCardWords((e as { oneCard?: OneCardTag }).oneCard, bareName) ?? ''].filter(Boolean),
    tags: [],
    overview: e.overview,
    target: 'episode',
    picture: e.stillPath ?? e.showBackdropPath,
    seed: e.showTitle,
  }
}

function continuePanel(c: ContinueItem): CardPanel {
  return {
    kicker: `Continue watching · ${timeLeft(c.durationMs - c.positionMs)}`,
    title: c.title,
    facts: [resumeLine(c), c.durationMs > 0 ? formatRuntime(Math.round(c.durationMs / 60_000)) : ''].filter(Boolean),
    tags: [],
    overview: '',
    target: 'resume',
    picture: c.stillPath ?? c.backdropPath,
    seed: c.title,
  }
}

function channelPanel(channel: LiveChannel, listings: boolean, now: number): CardPanel {
  const on = listings ? nowAndNext(channel, now).now : undefined
  const where = `${channel.number} ${channel.name}`
  if (!on) return { kicker: `Live TV · ${where}`, title: channel.name, facts: [listings ? 'No listings' : 'Watch live'], tags: [], overview: '', target: 'channel', seed: channel.name }
  const start = Date.parse(on.start), end = Date.parse(on.end)
  return {
    kicker: `On now · ${where} · ${timeRange(start, end)}`,
    title: on.title,
    facts: [`${duration(end - now)} left`, on.categories?.[0] ?? ''].filter(Boolean),
    tags: [],
    overview: on.description ?? '',
    target: 'channel',
    seed: on.title,
  }
}

/**
 * The panel for the card at `index` of a row's `cards` (rowCards): the card
 * itself, or its last card, a See all (a list's page or a library's grid) or
 * Live TV's Full guide, over no picture of its own.
 */
export function cardPanel(row: ScreenRow, cards: readonly RowCard[], index: number, ctx: { channels: readonly LiveChannel[]; listings: boolean; now: number }): CardPanel | undefined {
  const card = cards[index]
  if (!card) return undefined
  // A server still being asked, or Try again: the row's own name above it, and its words.
  if (card.tile) return { kicker: titleParts(row)[0], title: card.tile.kind === 'retry' ? 'Try again' : 'Still asking', facts: [card.tile.text], tags: [], overview: '', target: 'library', seed: row.title }
  if (card.more) {
    const target: PanelTarget = card.more.glyph === 'guide' ? 'guide' : card.more.glyph === 'list' ? 'list' : 'library'
    const kicker = row.style === 'live' ? 'Live TV' : titleParts(row)[0]
    const detail = target === 'list' && row.subtitle ? row.subtitle : card.more.detail
    return { kicker, title: card.more.title, facts: detail ? [detail] : [], tags: [], overview: '', target, seed: kicker }
  }
  switch (row.style) {
    case 'continue': { const c = row.continue?.[index]; return c && continuePanel(c) }
    case 'episode': { const e = row.episodes?.[index]; return e && episodePanel(row, e) }
    case 'live': { const c = ctx.channels[index]; return c && channelPanel(c, ctx.listings, ctx.now) }
    default: { const i = row.items?.[index]; return i && itemPanel(row, i, index, card) }
  }
}

const OK_WORDS: Record<PanelTarget, string> = {
  movie: 'opens the movie', show: 'opens the show', episode: 'opens the episode', resume: 'resumes',
  channel: 'watches the channel', guide: 'opens the guide', list: 'opens the list', library: 'opens the library', file: 'says it isn’t matched yet',
}

/**
 * The line under the panel: what OK and Hold OK do, and how to move. On the
 * row's first card ◀ leaves the row for the menu (the board's row start).
 */
export function panelHint(target: PanelTarget, card: RowCard | undefined, first: boolean): string {
  const hold = holdWords(card?.hold)
  return [OK_WORDS[target], hold && `hold OK ${hold}`, first ? '▶ more in this row · ◀ menu' : '◀ ▶ more in this row', '▼ next row'].filter(Boolean).join(' · ')
}
