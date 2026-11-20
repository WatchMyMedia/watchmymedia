import type { components } from '../../../src/api/schema'
import { episodeActions, episodeMinutes, isPartlyWatched, minutesLeft, showYears, trackSummary } from '../../../src/lib/episode'
import { formatBytes, formatDate, plural, resolutionLabel, seCode } from '../../../src/lib/format'
import { canRequestSeason, missingStatus, seasonSummary, seasonTabCount, type MissingStatus } from '../../../src/lib/requests'
import { episodeOnTop, missingActions, seasonRequested, type Arrival, type MissingActions, type OnTopReason } from '../../../src/lib/showPage'
import type { PlayRequest, Route } from '../../app/routes'
import { codecWords } from '../../player'
import { channelsText } from './movieLabels'

// The one show page on the TV (ShowPage and ShowRelated on the Samsung TV
// canvas; PLAN-SHOW-PAGE.md; the Android TV app's ShowPage*.kt and
// ShowPageViewModel): which episode is on top, what its parts say, and what
// the season and the show offer. The web's showPage.ts, episode.ts and
// requests.ts decide the rules both apps share.

type ShowDetail = components['schemas']['ShowDetail']
type Season = components['schemas']['Season']
type SeasonDetail = components['schemas']['SeasonDetail']
type Episode = components['schemas']['Episode']
type MediaFile = components['schemas']['MediaFile']
type MediaInfo = components['schemas']['MediaInfo']
type RequestOptions = components['schemas']['RequestOptions']
type DeleteApp = components['schemas']['DeleteApp']
type ReplaceResult = components['schemas']['ReplaceResult']

const SEP = ' · '

/** How the page was reached, from its route: the show, a season, or an episode. */
export function arrivalOf(route: Extract<Route, { name: 'show' }>): Arrival {
  if (route.episodeId !== undefined) return { kind: 'episode', id: route.episodeId }
  return route.season !== undefined ? { kind: 'season' } : { kind: 'show' }
}

/**
 * What the page has chosen: the season on its tabs and the episode on top
 * with why it is there (its chip). `arrived` until a tab or a card is
 * chosen, so the first season shown picks its episode the way the page was
 * reached. The screen pins the episode it resolves, so a refetch (a mark,
 * an event) never moves it.
 */
export type Selection = { season: number; episodeId?: number; why?: OnTopReason; arrived: boolean }

export type OnTop = { episode?: Episode; why?: OnTopReason; gone?: boolean }

/** The episode on top of [season] for a selection (showPage.ts's episodeOnTop, §3). */
export function onTopFor(sel: Selection, season: Pick<SeasonDetail, 'episodes'>, show: ShowDetail, arrival: Arrival): OnTop {
  if (sel.episodeId !== undefined) {
    const picked = season.episodes.find((e) => e.id === sel.episodeId)
    if (picked) return { episode: picked, why: sel.why }
  }
  return episodeOnTop(sel.arrived ? arrival : { kind: 'season' }, season.episodes, show)
}

/** "Specials", "Season 3". */
export const seasonName = (n: number) => (n === 0 ? 'Specials' : `Season ${n}`)

/** "Harbor Lights · S03 E07", or "· SP E02" for Specials, as the boards write it. */
export function episodeKicker(show: string, season: number, episode: number): string {
  const code = season === 0 ? `SP E${String(episode).padStart(2, '0')}` : seCode(season, episode).replace('E', ' E')
  return `${show}${SEP}${code}`
}

/** "today", "yesterday", "tomorrow" or the date, for an air date (a calendar day). */
export function dayWords(iso: string | undefined, today: Date): string {
  if (!iso) return ''
  const day = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso)
  if (!day) return formatDate(iso)
  const d = Date.UTC(Number(day[1]), Number(day[2]) - 1, Number(day[3]))
  const t = Date.UTC(today.getFullYear(), today.getMonth(), today.getDate())
  const diff = Math.round((d - t) / 86_400_000)
  return diff === 0 ? 'today' : diff === -1 ? 'yesterday' : diff === 1 ? 'tomorrow' : formatDate(iso.slice(0, 10))
}

/** The facts under the episode's title: "Aired yesterday", "52 min", "★ 8.4"; one not here, its air date and length. */
export function episodeFacts(e: Pick<Episode, 'aired' | 'airDate' | 'runtimeMin' | 'files' | 'rating' | 'hasFile'>, today: Date): string[] {
  const min = episodeMinutes(e)
  return [
    e.airDate ? `${e.aired ? 'Aired' : 'Airs'} ${dayWords(e.airDate, today)}` : e.aired ? '' : 'Not aired yet',
    min ? `${min} min` : '',
    e.hasFile && e.rating ? `★ ${e.rating.toFixed(1)}` : '',
  ].filter(Boolean)
}

/** The tags and the line after them for the episode's file: "4K", "HEVC", "HDR", "EAC3 5.1"; "Subtitles: English, Spanish · 4.2 GB". */
export function episodeTech(file: MediaFile | undefined, probe: Pick<MediaInfo, 'audio' | 'subtitles'> | undefined): { tags: string[]; line: string } {
  if (!file) return { tags: [], line: '' }
  const audio = probe?.audio.find((a) => a.default) ?? probe?.audio[0]
  const { subtitles } = trackSummary(probe)
  const tags = [
    resolutionLabel(file.width, file.height),
    codecWords(file.videoCodec),
    file.hdr === 'dolbyvision' ? 'DV' : file.hdr ? 'HDR' : '',
    [codecWords(audio?.codec ?? file.audioCodec), channelsText(audio?.channels, audio?.channelLayout)].filter(Boolean).join(' '),
  ].filter((t): t is string => !!t)
  return { tags, line: [subtitles, file.size ? formatBytes(file.size) : ''].filter(Boolean).join(SEP) }
}

/** The main button: "Resume from 23:10" partway through, else "Play"; undefined without a file. */
export function playLabel(e: Pick<Episode, 'aired' | 'airDate' | 'hasFile' | 'files' | 'watch' | 'runtimeMin'>): string | undefined {
  const { primary } = episodeActions(e)
  if (primary.kind !== 'play') return undefined
  return isPartlyWatched(e) ? primary.label : 'Play'
}

/** What the player route gets for an episode: [start] 0 from the start; none resumes where the server says. */
export function episodePlay(show: Pick<ShowDetail, 'title' | 'backdropPath'>, season: number, e: Episode, start?: 0): PlayRequest | undefined {
  const { primary } = episodeActions(e)
  if (primary.kind !== 'play') return undefined
  return {
    kind: 'file', fileId: primary.fileId, startMs: start, title: show.title,
    subtitle: `${season === 0 ? 'SP' : `S${String(season).padStart(2, '0')}`} E${String(e.number).padStart(2, '0')}${SEP}${e.title || `Episode ${e.number}`}`,
    backdropPath: e.stillPath ?? show.backdropPath,
  }
}

/** A missing episode's state, when the server can say why it's missing (Sonarr or requests on). */
export function missingOf(e: Episode, options: RequestOptions | undefined, now = Date.now()): MissingStatus | undefined {
  if (e.hasFile || !(options?.shows || options?.manage)) return undefined
  return missingStatus(e, now)
}

/** The line under a card: its missing state, "29 min left", "Watched", or its length. */
export function cardLine(e: Episode, missing: MissingStatus | undefined, today: Date): { text: string; tone?: MissingStatus['tone']; dot: boolean } {
  if (!e.hasFile) {
    if (missing) return { text: missing.label.split(SEP)[0], tone: missing.tone, dot: e.aired }
    return { text: e.aired ? 'Not in library' : e.airDate ? `Airs ${dayWords(e.airDate, today)}` : 'Not aired yet', tone: 'muted', dot: false }
  }
  const left = minutesLeft(e)
  if (left) return { text: `${left} min left`, dot: false }
  if (e.watch?.completed) return { text: 'Watched', dot: false }
  const min = episodeMinutes(e)
  return { text: min ? `${min} min` : '', dot: false }
}

/** "E07", "SP E02"'s "E02": the code on a card. */
export const cardCode = (e: Pick<Episode, 'number'>) => `E${String(e.number).padStart(2, '0')}`

/** How far through, 0–1; undefined when not partway. */
export function episodeProgress(e: Pick<Episode, 'watch' | 'files'>): number | undefined {
  if (!e.watch || !isPartlyWatched(e)) return undefined
  const file = e.files.find((f) => f.id === e.watch?.fileId) ?? e.files[0]
  const d = e.watch.durationMs || file?.durationMs || 0
  return d > 0 ? Math.min(1, e.watch.positionMs / d) : undefined
}

/** A season tab's count: the episodes on disk; with Show missing, "6 of 10" or "not in library". */
export function tabCount(t: Pick<Season, 'episodeCount' | 'availableCount'>, showMissing: boolean): string {
  return showMissing ? seasonTabCount(t) || 'not in library' : String(t.availableCount)
}

/**
 * Beside the tabs: "6 episodes · 4 watched" (those on disk), or with Show
 * missing "6 of 10 episodes · 4 missing", then the season's next to air:
 * "E09 airs Nov 18". None in the library: "6 episodes · none in the library".
 */
export function seasonLine(here: Pick<Season, 'episodeCount' | 'availableCount' | 'watchedCount'>, episodes: Pick<Episode, 'number' | 'aired' | 'airDate'>[], showMissing: boolean, today: Date): string {
  if (here.availableCount === 0) return `${plural(here.episodeCount, 'episode')}${SEP}none in the library`
  const base = showMissing ? seasonSummary(here) : `${plural(here.availableCount, 'episode')}${SEP}${here.watchedCount >= here.availableCount ? 'all watched' : `${here.watchedCount} watched`}`
  const next = [...episodes].sort((a, b) => a.number - b.number).find((e) => !e.aired && e.airDate)
  return next ? `${base}${SEP}${cardCode(next)} airs ${dayWords(next.airDate, today)}` : base
}

/** Mark season watched, or "Season watched" once every episode of it on disk is (pressing it unmarks); none without episodes on disk. */
export function seasonMark(here: Pick<Season, 'availableCount' | 'watchedCount'>): { label: string; watched: boolean } | undefined {
  if (here.availableCount === 0) return undefined
  return here.watchedCount >= here.availableCount ? { label: 'Season watched', watched: false } : { label: 'Mark season watched', watched: true }
}

/** Mark show watched, or "Show watched" once every season on disk is; none with nothing on disk. */
export function showMark(seasons: Pick<Season, 'availableCount' | 'watchedCount'>[]): { label: string; watched: boolean } | undefined {
  const onDisk = seasons.filter((s) => s.availableCount > 0)
  if (!onDisk.length) return undefined
  return onDisk.every((s) => s.watchedCount >= s.availableCount) ? { label: 'Show watched', watched: false } : { label: 'Mark show watched', watched: true }
}

/** What the person may do here: the admin's actions need an admin with Sonarr connected. */
export type Abilities = { admin: boolean; requests: boolean; problems: boolean; delete: boolean }

export function abilities(isAdmin: boolean, options: RequestOptions | undefined): Abilities {
  return { admin: isAdmin && !!options?.manage, requests: !!options?.shows, problems: !!options?.problems, delete: !!options?.delete }
}

/** What can be done about the missing episode on top: Request season N, and for admins Search now and Monitor. */
export function topActions(e: Episode, season: Season, show: Pick<ShowDetail, 'managedBy'>, can: Abilities, today: string): MissingActions {
  return missingActions(e, {
    admin: can.admin,
    canRequest: can.requests,
    seasonAskable: season.number > 0 && canRequestSeason(season, today),
    managed: !!show.managedBy,
    seasonUnmonitored: season.monitored === false,
  })
}

/** With Show missing on, the season's own: Request (or Requested), and for admins Search now and Monitor. */
export function seasonActions(season: Season, episodes: Pick<Episode, 'hasFile' | 'aired'>[], show: Pick<ShowDetail, 'managedBy'>, can: Abilities, today: string) {
  const requested = seasonRequested(season)
  return {
    request: can.requests && season.number > 0 && canRequestSeason(season, today),
    requested,
    search: can.admin && !!show.managedBy && episodes.some((e) => !e.hasFile && e.aired),
    monitor: can.admin && season.monitored === false,
  }
}

/** The episode's More: Report a problem, and for admins Replace file and Delete episode…; empty without a file. */
export function episodeMore(e: Episode, show: Pick<ShowDetail, 'managedBy'>, can: Abilities): { report: boolean; replace: boolean; delete: boolean } {
  const file = e.hasFile && !!e.files[0]
  return { report: file && can.problems, replace: file && can.admin && !!show.managedBy, delete: file && can.delete }
}

/** "2022– · 3 seasons · 28 episodes · Drama · ★ 8.1": About the show's facts. */
export function showFacts(show: Pick<ShowDetail, 'year' | 'lastAirDate' | 'status' | 'seasons' | 'episodeCount' | 'genres' | 'rating'>): string {
  const seasons = show.seasons.filter((s) => s.number > 0).length
  return [
    showYears(show),
    seasons ? plural(seasons, 'season') : '',
    show.episodeCount ? plural(show.episodeCount, 'episode') : '',
    show.genres[0] ?? '',
    show.rating ? `★ ${show.rating.toFixed(1)}` : '',
  ].filter(Boolean).join(SEP)
}

const ended = (status: string) => /ended|cancel/i.test(status)

/** "Continuing · next episode Nov 18" (gold), "Ended" or "Cancelled"; none without a status. */
export function showStatus(show: Pick<ShowDetail, 'status' | 'nextAirDate'>, today: Date): { text: string; active: boolean } | undefined {
  const status = show.status
  if (!status) return undefined
  if (ended(status)) return { text: /cancel/i.test(status) ? 'Cancelled' : 'Ended', active: false }
  return { text: show.nextAirDate ? `Continuing${SEP}next episode ${dayWords(show.nextAirDate, today)}` : 'Continuing', active: true }
}

/** "Notify me", or "Notifications on" while the person follows the show (PLAN-NEW-EPISODES.md §7.1). */
export const followLabel = (following: boolean) => (following ? 'Notifications on' : 'Notify me')

/** The bell's name for screen readers, which carries its state. */
export const followSpoken = (following: boolean, show: string) => (following ? `Notifications on for ${show}. Turn them off` : `Notify me of new episodes of ${show}`)

/** Under the buttons while the bell has focus: who notifies. */
export const FOLLOW_TV_LINE = 'Your phone or tablet notifies you. This TV shows new episodes on Home.'

/** The server's 409: at the limit of follows. */
export const FOLLOW_LIMIT = 'You follow 500 shows, the most you can. Choose Stop notifying for one in Settings › Notifications first.'

export function followRefused(on: boolean, show: string): string {
  return `Couldn’t turn ${on ? 'on' : 'off'} notifications for ${show}. Check your connection and try again.`
}

/** The page's parts top to bottom, for scrolling: the episode on top, the seasons, the row, About the show, More like this. */
export type Zone = 'top' | 'seasons' | 'episodes' | 'about' | 'related'

/**
 * How far the page is moved up for the part with focus: the top, the tabs
 * and the row are seen with the episode on top; About the show brings the
 * tabs to [topPad] (the top scrolled away), and More like this the page's
 * end (ShowRelated). About isn't held back by the page's end, so a page
 * without More like this still shows the tabs at the top.
 */
export function zoneOffset(zone: Zone, m: { seasonsTop: number; trackHeight: number; viewHeight: number; topPad: number }): number {
  if (zone === 'top' || zone === 'seasons' || zone === 'episodes') return 0
  const about = Math.max(0, m.seasonsTop - m.topPad)
  return zone === 'about' ? about : Math.max(about, m.trackHeight - m.viewHeight)
}

/** Said when the episode a link named isn't in its season any more. */
export const EPISODE_GONE_NOTE = 'That episode isn’t here any more.'

/** Under the episode when the show is watched to the end. */
export const WATCHED_IT_ALL_NOTE = 'You’ve watched every episode. Start again here, or pick one below.'

/**
 * Where a deleted episode's file goes, as the board says it: "It goes to
 * Sonarr’s recycling bin.", or for good. The bin's path stays out of it.
 */
export function deleteWhere(apps: Pick<DeleteApp, 'connection' | 'files' | 'recycleBin'>[]): string {
  const deleting = apps.filter((a) => a.files > 0)
  if (deleting.length === 0) return ''
  const one = deleting.length === 1
  if (deleting.every((a) => a.recycleBin)) return one ? `It goes to ${deleting[0].connection}’s recycling bin.` : 'They go to each app’s recycling bin.'
  if (deleting.every((a) => !a.recycleBin)) return one ? `It’s deleted for good: ${deleting[0].connection} has no recycling bin set.` : 'They’re deleted for good: no recycling bin is set.'
  return deleting.map((a) => (a.recycleBin ? `${a.connection} moves it to its recycling bin.` : `${a.connection} deletes it for good.`)).join(' ')
}

/** One line per app that didn't answer the preview, in our words rather than its error. */
export function unansweredLines(apps: Pick<DeleteApp, 'connection' | 'error'>[]): string[] {
  return apps.filter((a) => a.error).map((a) => `${a.connection} didn’t answer, so it can’t delete its copy.`)
}

/** "Sonarr blocks this release and searches for another. The episode is gone until the new copy arrives." */
export function replaceBody(app: string, what = 'episode'): string {
  return `${app} blocks this release and searches for another. The ${what} is gone until the new copy arrives.`
}

/** After Replace file: "Sonarr blocked this release and is searching for another." */
export function replacedMessage(r: Pick<ReplaceResult, 'connection' | 'blocklisted'>): string {
  return r.blocklisted ? `${r.connection} blocked this release and is searching for another.` : `${r.connection} is searching for another copy.`
}

/**
 * Whether a Search now that "failed" was in fact accepted: the server answers
 * 202 with no body, which the web's shared `unwrap` treats as a failure (it
 * lets only 204 come without one), so the mutation rejects with status 202.
 */
export function searchAccepted(error: unknown): boolean {
  return (error as { status?: unknown } | null)?.status === 202
}
