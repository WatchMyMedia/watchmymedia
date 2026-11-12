import type { components } from '../api/schema'
import { isPartlyWatched } from './episode'
import { canRequestSeason, isOpen } from './requests'

type Episode = components['schemas']['Episode']
type Season = components['schemas']['Season']
type NextEpisode = components['schemas']['NextEpisode']

// The one show page (PLAN-SHOW-PAGE.md): /shows/:id, /shows/:id/seasons/:n
// and /shows/:id/seasons/:n/episodes/:id draw the same page; only the
// episode on top differs (§3).

/** How the page was reached: the show's address, a season's, or an episode's. */
export type Arrival = { kind: 'show' } | { kind: 'season' } | { kind: 'episode'; id: number }

/** Why the episode on top was chosen for you, for its chip; none when the address named it. */
export type OnTopReason = 'next' | 'resume' | 'start' | 'again'

export const onTopChip: Record<OnTopReason, string> = {
  next: 'Up next',
  resume: 'Up next · resume',
  start: 'Start here',
  again: 'Watched it all',
}

/** "Specials" or "Season 3". */
export const seasonName = (n: number) => (n === 0 ? 'Specials' : `Season ${n}`)

/** An episode's address, which every pick on the page replaces the address with. */
export function episodePath(showId: number, season: number, episodeId: number): string {
  return `/shows/${showId}/seasons/${season}/episodes/${episodeId}`
}

/**
 * The season tabs: those with episodes on disk, or every one with Show
 * missing on, and always the open one; by number with Specials last (§2.4).
 */
export function seasonTabs<S extends Pick<Season, 'number' | 'availableCount'>>(seasons: S[], showMissing: boolean, open?: number): S[] {
  const order = (n: number) => (n === 0 ? Number.MAX_SAFE_INTEGER : n)
  return seasons.filter((s) => showMissing || s.availableCount > 0 || s.number === open).sort((a, b) => order(a.number) - order(b.number))
}

/**
 * The season the page opens on. A season's or an episode's address names
 * it; the show's address opens the up-next episode's season, else the
 * lowest season above 0 with episodes on disk, else Specials if only they
 * are, else the lowest season above 0. Undefined for a show with no seasons.
 */
export function arrivalSeason(show: { seasons: Pick<Season, 'number' | 'availableCount'>[]; nextEpisode?: Pick<NextEpisode, 'seasonNumber'> }, season?: number): number | undefined {
  if (season !== undefined) return season
  if (show.nextEpisode) return show.nextEpisode.seasonNumber
  const sorted = [...show.seasons].sort((a, b) => a.number - b.number)
  return (
    sorted.find((s) => s.number > 0 && s.availableCount > 0)?.number ??
    sorted.find((s) => s.availableCount > 0)?.number ??
    sorted.find((s) => s.number > 0)?.number ??
    sorted[0]?.number
  )
}

export type OnTop<E> = {
  episode?: E
  why?: OnTopReason
  /** The episode the address named isn't in the season any more. */
  gone?: boolean
}

/**
 * Which of a season's episodes goes on top (§3):
 *  - the show's address: the up-next episode from the server's
 *    `nextEpisode`, "Up next · resume" when partly watched and "Start here"
 *    when nothing of the show is watched yet; with no up-next and something
 *    on disk, the season's first on disk, "Watched it all";
 *  - a season's address: its first episode on disk not finished, else its
 *    first on disk, else its first;
 *  - an episode's address: that episode, else as a season's, marked gone.
 */
export function episodeOnTop<E extends Pick<Episode, 'id' | 'number' | 'hasFile' | 'watch'>>(
  arrival: Arrival,
  episodes: E[],
  show: { seasons: Pick<Season, 'watchedCount'>[]; nextEpisode?: Pick<NextEpisode, 'episodeId'>; fileCount: number },
): OnTop<E> {
  const sorted = [...episodes].sort((a, b) => a.number - b.number)
  const onDisk = sorted.filter((e) => e.hasFile)
  const seasonPick = () => onDisk.find((e) => !e.watch?.completed) ?? onDisk[0] ?? sorted[0]
  if (arrival.kind === 'episode') {
    const e = sorted.find((x) => x.id === arrival.id)
    return e ? { episode: e } : { episode: seasonPick(), gone: true }
  }
  if (arrival.kind === 'season') return { episode: seasonPick() }
  const nextId = show.nextEpisode?.episodeId
  const next = nextId === undefined ? undefined : sorted.find((e) => e.id === nextId)
  if (next) {
    if (isPartlyWatched(next)) return { episode: next, why: 'resume' }
    const nothingWatched = show.seasons.every((s) => s.watchedCount === 0) && !sorted.some(isPartlyWatched)
    return { episode: next, why: nothingWatched ? 'start' : 'next' }
  }
  if (show.fileCount > 0 && onDisk[0]) return { episode: onDisk[0], why: 'again' }
  return { episode: seasonPick() }
}

/**
 * Where the page goes after deleting the episode on top (§3): the next
 * episode in the season's row, else the one before. None when it was the only one.
 */
export function afterEpisodeDelete<E extends Pick<Episode, 'id' | 'number'>>(listed: E[], id: number): { episode: E; which: 'next' | 'previous' } | undefined {
  const sorted = [...listed].sort((a, b) => a.number - b.number)
  const i = sorted.findIndex((e) => e.id === id)
  if (i < 0) return undefined
  if (sorted[i + 1]) return { episode: sorted[i + 1], which: 'next' }
  if (sorted[i - 1]) return { episode: sorted[i - 1], which: 'previous' }
  return undefined
}

/** The tab to open after deleting a season: the next tab, else the one before. */
export function afterSeasonDelete(tabs: Pick<Season, 'number'>[], number: number): number | undefined {
  const i = tabs.findIndex((s) => s.number === number)
  if (i < 0) return undefined
  return tabs[i + 1]?.number ?? tabs[i - 1]?.number
}

/** The line over the row after a delete: "Deleted “Neap”. Showing the next episode." */
export function deletedLine(title: string, which?: 'next' | 'previous'): string {
  const then = which === 'next' ? ' Showing the next episode.' : which === 'previous' ? ' Showing the one before.' : ''
  return `Deleted “${title}”.${then}`
}

/** "7 episodes · 6 watched", "8 episodes · all watched", "3 of 10 episodes · 1 watched": the line beside the season's actions. */
export function seasonWatchLine(s: Pick<Season, 'episodeCount' | 'availableCount' | 'watchedCount'>): string {
  const n = s.availableCount
  const count = n < s.episodeCount ? `${n} of ${s.episodeCount} episodes` : `${n} ${n === 1 ? 'episode' : 'episodes'}`
  if (n === 0) return `${s.episodeCount} ${s.episodeCount === 1 ? 'episode' : 'episodes'} · none in the library`
  return `${count} · ${s.watchedCount >= n ? 'all watched' : `${s.watchedCount} watched`}`
}

/** A season's mark: "Mark season 3 watched", or "✓ Season 3 watched" once every episode of it on disk is; pressing that unmarks it. None without episodes on disk. */
export function seasonMark(s: Pick<Season, 'number' | 'availableCount' | 'watchedCount'>): { label: string; watched: boolean } | undefined {
  if (s.availableCount === 0) return undefined
  const name = seasonName(s.number)
  return s.watchedCount >= s.availableCount ? { label: `✓ ${name} watched`, watched: false } : { label: `Mark ${name.replace('Season', 'season')} watched`, watched: true }
}

/** The show's mark in About the show: "Mark show watched", or "✓ Show watched" once every season on disk is. None with nothing on disk. */
export function showMark(seasons: Pick<Season, 'availableCount' | 'watchedCount'>[]): { label: string; watched: boolean } | undefined {
  const onDisk = seasons.filter((s) => s.availableCount > 0)
  if (!onDisk.length) return undefined
  return onDisk.every((s) => s.watchedCount >= s.availableCount) ? { label: '✓ Show watched', watched: false } : { label: 'Mark show watched', watched: true }
}

/** The seasons Request missing seasons offers: above 0, some of it aired and missing, and nobody asked yet. */
export function askableSeasons(seasons: Pick<Season, 'number' | 'episodeCount' | 'availableCount' | 'airDate' | 'request'>[], today: string): number[] {
  return seasons.filter((s) => s.number > 0 && canRequestSeason(s, today)).map((s) => s.number)
}

/** What can be done about a missing episode on top (design/mockups/web/ShowPageMissing). */
export type MissingActions = {
  /** Request this episode's season, the main action. */
  request: boolean
  /** The person's own season request is waiting. */
  requested: boolean
  /** Ask Sonarr to search for it now. */
  search: boolean
  /** Ask Sonarr to monitor the season. */
  monitor: boolean
  /** An admin opens the request waiting for them. */
  inbox: boolean
  /** It is on its way (downloading, approved or not aired yet): nothing to do but wait. */
  coming: boolean
}

/**
 * The actions beside a missing episode on top, by why it is missing:
 * monitored and missing, an admin searches and others may request the
 * season; not in the library, anyone who can request asks for the season
 * and an admin may monitor it; requested, the person sees it is and an
 * admin opens it in the Inbox. Downloading, approved and not aired yet
 * have nothing to do but wait.
 */
export function missingActions(
  e: Pick<Episode, 'hasFile' | 'aired' | 'download' | 'request' | 'monitored'>,
  ctx: { admin: boolean; canRequest: boolean; seasonAskable: boolean; managed: boolean; seasonUnmonitored: boolean },
): MissingActions {
  const none = { request: false, requested: false, search: false, monitor: false, inbox: false, coming: false }
  if (e.hasFile) return none
  if (!e.aired || e.download || e.request?.status === 'approved') return { ...none, coming: true }
  if (e.request?.status === 'pending') return { ...none, requested: !ctx.admin, inbox: ctx.admin }
  const missing = e.monitored === true
  return {
    ...none,
    request: ctx.canRequest && ctx.seasonAskable && (missing ? !ctx.admin : true),
    search: ctx.admin && ctx.managed && missing,
    monitor: ctx.admin && !missing && ctx.seasonUnmonitored,
  }
}

/** Whether a season's own request is waiting: the "Requested" mark beside its actions. */
export const seasonRequested = (s: Pick<Season, 'request'>) => isOpen(s.request)

/**
 * Where the player's Back goes once it moves on to the next episode: that
 * episode's address when Back was on the show (PLAN-SHOW-PAGE.md §4.1), else
 * where it was (Home, a list).
 */
export function backAfterNext(back: string | undefined, next: { showId: number; seasonNumber: number; episodeId: number }): string | undefined {
  if (back && /^\/shows\/\d+(\/|$|\?)/.test(back)) return episodePath(next.showId, next.seasonNumber, next.episodeId)
  return back
}
