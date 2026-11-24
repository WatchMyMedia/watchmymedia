import type { components } from '../../../src/api/schema'
import { homeRows, type ScreenRow } from '../../../src/lib/screenRows'
import type { HiddenTarget } from '../../../src/api/continueHidden'
import { hiddenTarget } from '../../../src/api/continueHidden'
import type { ChosenCopy } from '../../session/chosenCopies'

// Home across servers (one card across servers, API level 34): Continue
// watching is every signed-in server's, one card a title, ordered by when it
// was last played on any of them, with the latest place; the rows each
// server builds the same way (recently added, aired and released) are filled
// from all of them, newest first, one card a title. Every other row, and the
// order of the rows, is the current server's. A row already on screen never
// moves: a slow server's cards join at its end. Pure, so it is tested.

type HomeData = components['schemas']['HomeData']
type ContinueItem = components['schemas']['ContinueItem']
type BrowseItem = components['schemas']['BrowseItem']
type EpisodeCard = components['schemas']['EpisodeCard']

/**
 * Where a card's title is, when it matters: only on another server ('only',
 * "On Cabin"), or playing from another server ('from', "from Cabin"). `here`
 * is the current server's own copy of a 'from' title, whose page it opens.
 */
export type OneCardTag = { server: { id: string; name: string }; where: 'only' | 'from'; here?: { movieId?: number; showId?: number } }

/** A Continue watching card's copy on a server, so Remove takes it off every server that has it. */
export type ContinueCopy = { server: string; target: HiddenTarget }

export type TaggedContinue = ContinueItem & { oneCard?: OneCardTag; copies?: ContinueCopy[] }
export type TaggedItem = BrowseItem & { oneCard?: OneCardTag }
export type TaggedEpisode = EpisodeCard & { oneCard?: OneCardTag }

/** Another server's Home, as it answered. */
export type PeerHome = { server: { id: string; name: string }; home: HomeData }

/** The rows each server builds the same way: filled from every server. */
export const MERGED_ROWS = new Set(['continue', 'recentlyAddedMovies', 'recentlyAddedShows', 'recentlyAired', 'recentlyReleased'])

const continueKey = (c: ContinueItem, server: string) => (c.tmdbId ? `${c.kind === 'movie' ? 'movie' : 'show'}:${c.tmdbId}` : `${server}:${c.fileId}`)
const itemKey = (i: BrowseItem, server: string) => (i.tmdbId ? `${i.kind}:${i.tmdbId}` : `${server}:${i.kind}${i.id}`)
const episodeKey = (e: EpisodeCard, server: string) => (e.showTmdbId ? `${e.showTmdbId}:${e.seasonNumber}:${e.episodeNumber}` : `${server}:${e.episodeId}`)

/** A merged card's key in its row. */
export function oneCardKey(row: Pick<ScreenRow, 'style'>, card: TaggedContinue | TaggedItem | TaggedEpisode, currentId: string): string {
  const server = (card as { oneCard?: OneCardTag }).oneCard?.where === 'only' ? (card as { oneCard: OneCardTag }).oneCard.server.id : currentId
  if (row.style === 'continue') return continueKey(card as ContinueItem, server)
  if (row.style === 'episode') return episodeKey(card as EpisodeCard, server)
  return itemKey(card as BrowseItem, server)
}

/**
 * Continue watching from every server: one card a title (a show's is its
 * latest episode), the latest place on any server, newest first. A title
 * the current server hasn't in its row says where it is ("On Cabin"); one
 * whose latest place is another server's plays from there ("from Cabin"),
 * unless the person chose another copy and that server has a place in it too.
 */
export function mergeContinue(current: readonly ContinueItem[], currentId: string, others: readonly { server: { id: string; name: string }; items: readonly ContinueItem[] }[], chosen?: (c: ContinueItem) => ChosenCopy | undefined): TaggedContinue[] {
  type Entry = { server: { id: string; name: string } | undefined; item: ContinueItem }
  const groups = new Map<string, Entry[]>()
  const add = (server: Entry['server'], item: ContinueItem) => {
    const key = continueKey(item, server?.id ?? currentId)
    groups.set(key, [...(groups.get(key) ?? []), { server, item }])
  }
  for (const c of current) add(undefined, c)
  for (const o of others) for (const c of o.items) add(o.server, c)
  const out: TaggedContinue[] = []
  for (const entries of groups.values()) {
    const latest = entries.reduce((a, b) => (Date.parse(b.item.updatedAt) > Date.parse(a.item.updatedAt) ? b : a))
    const here = entries.some((e) => !e.server)
    const pick = chosen?.(latest.item)
    const preferred = pick && entries.find((e) => (e.server?.id ?? currentId) === pick.server)
    const plays = preferred ?? latest
    const copies = entries.flatMap((e) => { const t = hiddenTarget(e.item); return t ? [{ server: e.server?.id ?? currentId, target: t }] : [] })
    const base: TaggedContinue = plays === latest ? { ...latest.item } : { ...plays.item, positionMs: latest.item.positionMs, durationMs: latest.item.durationMs, updatedAt: latest.item.updatedAt }
    const tag: OneCardTag | undefined = plays.server ? { server: plays.server, where: here ? 'from' : 'only' } : undefined
    out.push({ ...base, ...(tag ? { oneCard: tag } : {}), ...(entries.length > 1 ? { copies } : {}) })
  }
  return out.sort((a, b) => Date.parse(b.updatedAt) - Date.parse(a.updatedAt))
}

/** Newest first: by when it was added, or released, or aired. */
function newest<T>(list: readonly T[], at: (t: T) => string | undefined): T[] {
  return [...list].sort((a, b) => (at(b) ?? '').localeCompare(at(a) ?? ''))
}

/** A browse row from every server: one card a title, the current server's copy when it has one, newest first. */
export function mergeItems(rowId: string, current: readonly BrowseItem[], currentId: string, others: readonly { server: { id: string; name: string }; items: readonly BrowseItem[] }[]): TaggedItem[] {
  const seen = new Set<string>()
  const out: TaggedItem[] = []
  for (const i of current) { seen.add(itemKey(i, currentId)); out.push(i) }
  for (const o of others) for (const i of o.items) {
    const key = itemKey(i, o.server.id)
    if (seen.has(key)) continue
    seen.add(key)
    out.push({ ...i, oneCard: { server: o.server, where: 'only' } })
  }
  return newest(out, (i) => (rowId === 'recentlyReleased' ? i.releaseDate : i.addedAt))
}

/** An episode row from every server: one card an episode, newest first by air date. */
export function mergeEpisodes(current: readonly EpisodeCard[], currentId: string, others: readonly { server: { id: string; name: string }; items: readonly EpisodeCard[] }[]): TaggedEpisode[] {
  const seen = new Set<string>()
  const out: TaggedEpisode[] = []
  for (const e of current) { seen.add(episodeKey(e, currentId)); out.push(e) }
  for (const o of others) for (const e of o.items) {
    const key = episodeKey(e, o.server.id)
    if (seen.has(key)) continue
    seen.add(key)
    out.push({ ...e, oneCard: { server: o.server, where: 'only' } })
  }
  return newest(out, (e) => e.airDate)
}

/** The cards in the order a row was first drawn in, new cards after them: a row on screen never moves. */
export function keepOrder<T>(order: readonly string[] | undefined, items: readonly T[], key: (t: T) => string): T[] {
  if (!order) return [...items]
  const at = new Map(order.map((k, i) => [k, i]))
  const known = items.filter((t) => at.has(key(t))).sort((a, b) => at.get(key(a))! - at.get(key(b))!)
  return [...known, ...items.filter((t) => !at.has(key(t)))]
}

/**
 * Home's rows with every other server's: the current server's rows in its
 * order, those in MERGED_ROWS filled from every server, each kept in the
 * order `frozen` remembers for it once it was on screen.
 */
export function mergeHomeRows(rows: readonly ScreenRow[], currentId: string, others: readonly PeerHome[], frozen: ReadonlyMap<string, readonly string[]>, chosen?: (c: ContinueItem) => ChosenCopy | undefined): ScreenRow[] {
  if (!others.length) return [...rows]
  const theirs = others.map((o) => ({ server: o.server, rows: homeRows(o.home) }))
  const from = <K extends 'continue' | 'items' | 'episodes'>(id: string, field: K) =>
    theirs.map((t) => ({ server: t.server, items: (t.rows.find((r) => r.id === id)?.[field] ?? []) as NonNullable<ScreenRow[K]> }))
  return rows.map((row) => {
    if (!MERGED_ROWS.has(row.id)) return row
    const order = frozen.get(row.id)
    if (row.style === 'continue') {
      const merged = mergeContinue(row.continue ?? [], currentId, from(row.id, 'continue'), chosen)
      return { ...row, continue: keepOrder(order, merged, (c) => oneCardKey(row, c, currentId)) }
    }
    if (row.style === 'episode') {
      const merged = mergeEpisodes(row.episodes ?? [], currentId, from(row.id, 'episodes'))
      return { ...row, episodes: keepOrder(order, merged, (e) => oneCardKey(row, e, currentId)) }
    }
    const merged = mergeItems(row.id, row.items ?? [], currentId, from(row.id, 'items'))
    return { ...row, items: keepOrder(order, merged, (i) => oneCardKey(row, i, currentId)) }
  })
}

/** A merged row's cards' keys, to keep its order once it is on screen. */
export function rowOrder(row: ScreenRow, currentId: string): string[] | undefined {
  if (!MERGED_ROWS.has(row.id)) return undefined
  const list = row.style === 'continue' ? row.continue : row.style === 'episode' ? row.episodes : row.items
  return (list as (TaggedContinue | TaggedItem | TaggedEpisode)[] | undefined)?.map((c) => oneCardKey(row, c, currentId))
}

/**
 * A Continue watching card the current server's row hasn't, but whose title
 * the current server has (`here`, from its copies by TMDB id): it plays from
 * the other server, and its page is the current server's own.
 */
export function withHere(items: readonly TaggedContinue[], here: (c: TaggedContinue) => { movieId?: number; showId?: number } | undefined): TaggedContinue[] {
  return items.map((c) => {
    if (c.oneCard?.where !== 'only') return c
    const own = here(c)
    return own ? { ...c, oneCard: { ...c.oneCard, where: 'from' as const, here: own } } : c
  })
}

/** "On Cabin", "from Cabin": a card's words about its server. */
export function oneCardWords(tag: OneCardTag | undefined, bare: (name: string) => string): string | undefined {
  if (!tag) return undefined
  return tag.where === 'only' ? `On ${bare(tag.server.name)}` : `from ${bare(tag.server.name)}`
}

/** Said at the end of a merged row while a server is still out, and when one didn't answer. */
export const stillAskingHome = (names: readonly string[]) => `Still asking ${names.join(' and ')}…`
export const notOnHome = (name: string) => `${name} didn’t answer, so its titles aren’t on Home.`
