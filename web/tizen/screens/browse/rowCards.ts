import type { components } from '../../../src/api/schema'
import type { LiveChannel } from '../../../src/api/liveTV'
import type { ItemFilter, ItemSort } from '../../../src/lib/browseOptions'
import { posterLinks, type CardTarget } from '../../../src/lib/cardLinks'
import { formatDate, formatRuntime, seCode } from '../../../src/lib/format'
import type { ImageSize } from '../../../src/lib/images'
import { nowAndNext, progress, timeRange } from '../../../src/lib/liveGuide'
import { attribution, isListRow, posterSubtitle, seeAllTarget, seeAllWords, titleParts, type ScreenRow, type SeeAllTarget } from '../../../src/lib/screenRows'
import { watchlistTitle } from '../../../src/lib/watchlists'
import type { PlayRequest, Route } from '../../app/routes'
import type { CardBadge, CardVariant } from '../../ui/Card'

// A row's cards on the TV, whatever its style (Android TV's TvRows.kt):
// what each says, its picture, and what OK and Hold OK do. Pure, so Home's
// row screens, a library's rows and their tests share one set of rules.

type BrowseItem = components['schemas']['BrowseItem']
type ContinueItem = components['schemas']['ContinueItem']
type EpisodeCard = components['schemas']['EpisodeCard']

/** What OK or Hold OK on a card does; the screen carries it out. */
export type CardAction =
  | { kind: 'play'; request: PlayRequest }
  | { kind: 'open'; route: Route }
  | { kind: 'watched'; movieId: number; watched: boolean }
  /** A file the server hasn't matched to anything: say so. */
  | { kind: 'notMatched' }
  /** A library row's See all on its own library's screen: the grid below, sorted the row's way. */
  | { kind: 'grid'; sort?: ItemSort; filter?: ItemFilter; genre?: string; decade?: number }
  /** The card's actions (open it, Mark watched, its watchlist item), where the server keeps watchlists. */
  | { kind: 'menu'; item: BrowseItem }

export type RowCard = {
  /** Unique in the row: the card's focus id is `<row group>/<key>`. */
  key: string
  variant: CardVariant
  title: string
  subtitle?: string
  /** A TMDB path and the size to fetch it at, for useMediaUrls().imageUrl. */
  image?: { path: string; size: ImageSize }
  progress?: number
  badges?: CardBadge[]
  /** Watched all the way: the white tick. */
  done?: boolean
  live?: { number: string; name: string; now?: string }
  /** Names the whole card for a screen reader. */
  label?: string
  /** A row's last card, drawn as words (See all, Full guide). */
  more?: { title: string; detail?: string; glyph: 'grid' | 'list' | 'guide' }
  press?: CardAction
  hold?: CardAction
}

export type LibraryRef = { id: number; type: string }

export type RowContext = {
  libraries: readonly LibraryRef[]
  /** Live TV's channels that can be watched, in the server's order. */
  channels: readonly LiveChannel[]
  /** The server has listings: the Live row is On now, with the guide at its end. */
  listings: boolean
  now: number
  /** What a row's See all does here; none draws no See all card. */
  seeAll?: (target: SeeAllTarget, row: ScreenRow) => CardAction | undefined
  /** Hold OK on a title opens its actions, with its watchlist item: the server keeps watchlists. */
  menu?: boolean
  /** A Movies or TV screen's type: a list's See all opens its page on it. */
  screenType?: 'movie' | 'tv'
}

/** One of the person's watchlists, as a row: a list row with no source, since nothing fetches it. */
export function isWatchlistRow(row: Pick<ScreenRow, 'id' | 'source'>): boolean {
  return isListRow(row) && !row.source
}

/** The kit's card for a row's style. */
export function rowVariant(row: Pick<ScreenRow, 'style'>): CardVariant {
  switch (row.style) {
    case 'wide': return 'wide'
    case 'episode': return 'episode'
    case 'continue': return 'continue'
    case 'live': return 'live'
    default: return 'poster'
  }
}

/** "S03E07" as the TV writes it, "S03 E07"; other lines ("Season 1", "Complete series") as they are. */
export function spacedCode(code: string | undefined): string | undefined {
  const m = code ? /^S(\d+)E(\d+)$/.exec(code) : null
  return m ? `S${m[1]} E${m[2]}` : code || undefined
}

export function episodeCode(season: number, episode: number): string {
  return spacedCode(seCode(season, episode))!
}

/** "23 min left", "1 h 12 min left". */
export function timeLeft(ms: number): string {
  return `${formatRuntime(Math.max(1, Math.ceil(ms / 60_000)))} left`
}

/** A cardLinks target (the picture's, the most specific) as the TV's route. */
export function routeOf(t: CardTarget): Route {
  switch (t.to) {
    case '/movies/$movieId': return { name: 'movie', id: Number(t.params.movieId) }
    case '/shows/$showId': return { name: 'show', id: Number(t.params.showId) }
    case '/shows/$showId/seasons/$number': return { name: 'show', id: Number(t.params.showId), season: Number(t.params.number) }
    case '/shows/$showId/seasons/$number/episodes/$episodeId': return { name: 'show', id: Number(t.params.showId), episodeId: Number(t.params.episodeId) }
  }
}

/** The line under a poster: what arrived and when ("S03 E07 · added today"), a release date on a wide card, else the year. */
export function posterLine(row: Pick<ScreenRow, 'id' | 'style'>, item: BrowseItem): string | undefined {
  if (row.style === 'wide') return formatDate(item.releaseDate) || (item.year ? String(item.year) : undefined)
  const lead = item.kind === 'show' ? spacedCode(item.subtitle) : undefined
  const tail = row.id === 'recentlyReleased' ? formatDate(item.releaseDate) : posterSubtitle(row, item)
  if (lead || tail) return [lead, tail].filter(Boolean).join(' · ')
  return item.year ? String(item.year) : undefined
}

const NEW: CardBadge = { label: 'New', tone: 'gold' }

/** The words on a card's picture: New, and what it lacks. Watched is the white tick in the corner (`done`), not a badge. */
function badges(isNew: boolean | undefined, extra?: CardBadge): CardBadge[] | undefined {
  const out = [isNew ? NEW : undefined, extra].filter((b): b is CardBadge => !!b)
  return out.length ? out : undefined
}

function spoken(...parts: (string | false | undefined)[]): string {
  return parts.filter(Boolean).join(', ')
}

/**
 * A library card's OK and Hold OK: a movie's page and its watched mark; a
 * show's most specific page; an unmatched file says so. With `menu` (the
 * server keeps watchlists), Hold OK on a title TMDB has matched opens its
 * actions instead, Mark watched among them.
 */
export function itemActions(item: BrowseItem, menu = false): Pick<RowCard, 'press' | 'hold'> {
  if (item.kind === 'unmatched') return { press: { kind: 'notMatched' } }
  const actions: Pick<RowCard, 'press' | 'hold'> = item.kind === 'movie'
    ? { press: { kind: 'open', route: { name: 'movie', id: item.id } }, hold: { kind: 'watched', movieId: item.id, watched: !item.watched } }
    : { press: { kind: 'open', route: routeOf(posterLinks(item).picture.to) } }
  return menu && watchlistTitle(item.kind, item.tmdbId) ? { ...actions, hold: { kind: 'menu', item } } : actions
}

export function itemCard(row: Pick<ScreenRow, 'id' | 'style'>, item: BrowseItem, menu = false): RowCard {
  const wide = row.style === 'wide'
  const subtitle = posterLine(row, item)
  const path = wide ? item.backdropPath : item.posterPath
  return {
    key: `${item.kind}${item.id}`,
    variant: wide ? 'wide' : 'poster',
    title: item.title,
    subtitle,
    image: path ? { path, size: wide ? 'w780' : 'w342' } : undefined,
    progress: item.progress,
    badges: badges(item.isNew),
    done: !!item.watched && !item.progress,
    label: spoken(item.title, subtitle, item.watched && 'watched'),
    ...itemActions(item, menu),
  }
}

export function continueCard(c: ContinueItem): RowCard {
  // "S02 E04 · 23 min left", "1 h 12 min left": where you are and what is left, whatever the server's own line says.
  const code = c.kind === 'episode' && c.seasonNumber && c.episodeNumber ? episodeCode(c.seasonNumber, c.episodeNumber) : ''
  const subtitle = c.durationMs ? [code, timeLeft(c.durationMs - c.positionMs)].filter(Boolean).join(' · ') : c.subtitle || code
  const path = c.stillPath ?? c.backdropPath
  const page: Route | undefined = c.showId ? { name: 'show', id: c.showId } : c.movieId ? { name: 'movie', id: c.movieId } : undefined
  return {
    key: `cw${c.fileId}`,
    variant: 'continue',
    title: c.title,
    subtitle,
    image: path ? { path, size: 'w780' } : undefined,
    progress: c.durationMs ? c.positionMs / c.durationMs : 0,
    label: spoken(c.title, subtitle),
    press: { kind: 'play', request: { kind: 'file', fileId: c.fileId, startMs: c.positionMs, title: c.title, subtitle: c.subtitle, backdropPath: c.backdropPath ?? c.stillPath } },
    hold: page ? { kind: 'open', route: page } : undefined,
  }
}

export function episodeCard(e: EpisodeCard): RowCard {
  const code = episodeCode(e.seasonNumber, e.episodeNumber)
  const subtitle = [code, e.title].filter(Boolean).join(' · ')
  const path = e.stillPath ?? e.showBackdropPath
  const open: CardAction = { kind: 'open', route: { name: 'show', id: e.showId, episodeId: e.episodeId } }
  return {
    key: `e${e.episodeId}`,
    variant: 'episode',
    title: e.showTitle,
    subtitle,
    image: path ? { path, size: 'w780' } : undefined,
    badges: badges(e.isNew, e.hasFile ? undefined : { label: 'Not in library' }),
    done: !!e.watched,
    label: spoken(e.showTitle, `season ${e.seasonNumber} episode ${e.episodeNumber}`, e.title, e.watched && 'watched'),
    // OK opens the episode on its show, focused on Play; holding OK plays it at once.
    press: open,
    hold: e.fileId ? { kind: 'play', request: { kind: 'file', fileId: e.fileId, title: e.showTitle, subtitle, backdropPath: e.showBackdropPath ?? e.stillPath } } : open,
  }
}

export function channelCard(channel: LiveChannel, listings: boolean, now: number): RowCard {
  const { now: on, next } = listings ? nowAndNext(channel, now) : { now: undefined, next: undefined }
  const start = on ? Date.parse(on.start) : 0
  const end = on ? Date.parse(on.end) : 0
  return {
    key: `ch${channel.id}`,
    variant: 'live',
    title: on?.title ?? channel.name,
    // "48 min left · then Station 9": how long it has, and what comes after.
    subtitle: on ? [timeLeft(end - now), next ? `then ${next.title}` : ''].filter(Boolean).join(' · ') : listings ? 'No listings' : undefined,
    live: { number: channel.number, name: channel.name, now: on?.title ?? (listings ? channel.name : 'Watch live') },
    progress: on ? progress(on, now) / 100 : undefined,
    label: spoken(`Channel ${channel.number}`, channel.name, on && `${on.title}, until ${timeRange(start, end).split('–')[1]}`),
    press: { kind: 'play', request: { kind: 'live', channelId: channel.id } },
  }
}

const GUIDE_CARD: RowCard = {
  key: 'guide', variant: 'live', title: 'Full guide', label: 'Full guide',
  more: { title: 'Full guide', detail: 'Every channel and what’s next', glyph: 'guide' },
  press: { kind: 'open', route: { name: 'liveGuide' } },
}

/** A row's See all card, where it has somewhere to go here. */
export function seeAllCard(row: ScreenRow, ctx: RowContext): RowCard | undefined {
  const target = ctx.seeAll && seeAllTarget(row, ctx.libraries, ctx.screenType)
  const press = target && ctx.seeAll?.(target, row)
  if (!target || !press) return undefined
  const words = seeAllWords(target, titleParts(row)[0])
  return {
    key: 'all', variant: row.style === 'poster' ? 'poster' : 'wide', title: words.title, label: words.label,
    more: { title: words.title, detail: words.detail, glyph: target.kind === 'list' ? 'list' : 'grid' },
    press,
  }
}

/** Every card of a row, its See all (or the guide) last. */
export function rowCards(row: ScreenRow, ctx: RowContext): RowCard[] {
  let cards: RowCard[]
  switch (row.style) {
    case 'continue': cards = (row.continue ?? []).map(continueCard); break
    case 'episode': cards = (row.episodes ?? []).map(episodeCard); break
    case 'live':
      cards = ctx.channels.map((c) => channelCard(c, ctx.listings, ctx.now))
      if (ctx.listings) cards.push(GUIDE_CARD)
      return cards
    default: cards = (row.items ?? []).map((i) => itemCard(row, i, ctx.menu))
  }
  const all = seeAllCard(row, ctx)
  return all ? [...cards, all] : cards
}

/** A row's heading and the muted words beside it: a list's "18 of 50 · From TMDB", Live TV's "Watch a channel". */
export function rowHeading(row: ScreenRow, listings: boolean, clock?: string): [string, string | undefined] {
  if (row.style === 'live') return listings ? ['On now', clock ? `Live TV · ${clock}` : 'Live TV'] : ['Live TV', 'Watch a channel']
  const [title, small] = titleParts(row)
  if (row.listId !== undefined || row.source) return [title, attribution(row.subtitle, row.source).map((s) => s.text).join('') || undefined]
  return [title, small]
}

/** The route of a Home row's See all: a list's page, or its library sorted the row's way. */
export function seeAllRoute(target: SeeAllTarget, row: Pick<ScreenRow, 'id' | 'title'>, libraries: readonly LibraryRef[]): Route | undefined {
  if (target.kind === 'list') return { name: 'list', id: target.listId, type: target.type }
  const libraryId = target.search.library ?? libraries.find((l) => l.type === target.type)?.id
  if (libraryId === undefined) return undefined
  const { sort, filter, genre, decade } = target.search
  return { name: 'seeAll', libraryId, title: titleParts(row)[0], sort, filter, genre, decade }
}

/** What Hold OK does, for the hint: "marks it watched", "plays it". */
export function holdWords(action: CardAction | undefined): string | undefined {
  switch (action?.kind) {
    case 'watched': return action.watched ? 'marks it watched' : 'marks it unwatched'
    case 'play': return 'plays it'
    case 'open': return 'opens its page'
    case 'menu': return 'for its menu'
    default: return undefined
  }
}

/** What OK on a card does, for the hint beside it: "Open the movie", "The whole list". */
export function okWords(action: CardAction | undefined): string | undefined {
  switch (action?.kind) {
    case 'open': {
      const r = action.route
      if (r.name === 'movie') return 'Open the movie'
      if (r.name === 'show') return r.episodeId !== undefined ? 'Open the episode' : 'Open the show'
      if (r.name === 'list') return 'The whole list'
      if (r.name === 'seeAll') return 'See them all'
      if (r.name === 'liveGuide') return 'Open the guide'
      return 'Open it'
    }
    case 'play': return action.request.kind === 'live' ? 'Watch the channel' : action.request.kind === 'file' && action.request.startMs ? 'Resume' : 'Play'
    case 'grid': return 'See them all'
    case 'notMatched': return 'Not matched yet'
    default: return undefined
  }
}

/** Hold OK's hint beside a card: "Mark watched", "Play it". */
export function holdHint(action: CardAction | undefined): string | undefined {
  switch (action?.kind) {
    case 'watched': return action.watched ? 'Mark watched' : 'Mark unwatched'
    case 'play': return 'Play it'
    case 'open': return 'Its page'
    case 'menu': return 'Its menu'
    default: return undefined
  }
}
