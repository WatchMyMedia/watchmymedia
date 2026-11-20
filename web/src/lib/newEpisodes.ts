import type { components } from '../api/schema'
import { relativeTime } from './format'
import { episodePath } from './showPage'

/**
 * New episodes of the shows a person follows: when this browser
 * checks, what a notification and the banner say and where they open, and
 * what the browser keeps for each person: the cursor, the switch and the
 * banner. The server keeps the follows and the arrivals; each browser keeps
 * its own place in the feed.
 */

type Follow = components['schemas']['Follow']
type NewEpisodes = components['schemas']['NewEpisodes']
type NewEpisodeGroup = components['schemas']['NewEpisodeGroup']
type Numbered = { season: number; number: number }

/** Notify me, the list and the feed come with this API level; the bell and the Settings tab are hidden below it. */
export const FOLLOW_API_LEVEL = 29

export function canFollow(apiLevel: number | undefined): boolean {
  return (apiLevel ?? 0) >= FOLLOW_API_LEVEL
}

/** While a tab is open, the check runs this long after the last library.changed. */
export const SETTLE_MS = 3 * 60_000

/** More shows than this at once become these and one notification for the rest. */
export const MAX_SHOW_NOTICES = 5

/**
 * The check runs only when it has something to do: the person
 * follows a show, this browser's switch is on and the page is a secure
 * context (the Web Lock needs one). It doesn't need the browser's
 * permission: a tab in front shows the banner without it, and only a hidden
 * tab's system notifications need it.
 */
export function shouldCheck(o: { follows: number; on: boolean; secure: boolean }): boolean {
  return o.follows > 0 && o.on && o.secure
}

/** What a check that found something shows: the banner in a tab in front, system notifications from a hidden one when allowed, else the banner when the tab is next seen. */
export function deliveryFor(visible: boolean, permission: NotificationPermission | 'unsupported'): 'banner' | 'system' {
  return !visible && permission === 'granted' ? 'system' : 'banner'
}

/** "S3 E7"; a special is S0 E3, by the same rule. */
export function episodeCode(e: Numbered): string {
  return `S${e.season} E${e.number}`
}

/** "S3 E7 to E9" in one season, "S2 E10 to S3 E2" across seasons. */
export function episodeRange(first: Numbered, last: Numbered): string {
  return first.season === last.season ? `${episodeCode(first)} to E${last.number}` : `${episodeCode(first)} to ${episodeCode(last)}`
}

/**
 * A show's new episodes, as one notification (title and body) or the
 * banner (heading, show and line: the title split at " · ", and the body).
 * key is the notification's tag, so a later check replaces a show's.
 */
export type EpisodeNotice = {
  key: string
  heading: string
  show: string
  line: string
  title: string
  body: string
  path: string
  /** Open's accessible name in the banner: the show and the earliest episode. */
  openLabel: string
  posterPath?: string
}

/**
 * One show's notice, opening its page at the earliest new episode.
 * The line is the episode, the first to the last listed, or, when more
 * arrived than the feed lists (it sends the earliest five), "From" the
 * earliest.
 */
export function groupNotice(g: NewEpisodeGroup): EpisodeNotice {
  const first = g.episodes[0]
  const last = g.episodes[g.episodes.length - 1]
  const heading = g.count === 1 ? 'New episode' : `${g.count} new episodes`
  const line = g.count > 1 && first && g.count > g.episodes.length
    ? `From ${episodeCode(first)}`
    : !first || !last || (first.season === last.season && first.number === last.number)
      ? [episodeCode(first ?? g.open), first?.title].filter(Boolean).join(' · ')
      : episodeRange(first, last)
  return {
    key: `new-episodes:${g.show.id}`,
    heading,
    show: g.show.title,
    line,
    title: `${heading} · ${g.show.title}`,
    body: line,
    path: episodePath(g.show.id, g.open.season, g.open.episodeId),
    openLabel: `Open ${g.show.title}, ${episodeCode(g.open)}`,
    posterPath: g.show.posterPath,
  }
}

/** "3 more shows have new episodes", "1 more show has new episodes". */
export function moreShowsLine(more: number): string {
  return more === 1 ? '1 more show has new episodes' : `${more} more shows have new episodes`
}

/** The notification for the shows after the first five: no body, and it opens Home. */
export function moreShowsNotice(more: number): EpisodeNotice {
  const title = moreShowsLine(more)
  return { key: 'new-episodes:more', heading: title, show: '', line: '', title, body: '', path: '/', openLabel: 'Open Home' }
}

/** A hidden tab's notifications for a reply: one a show, at most five, then one for the rest. */
export function episodeNotices(groups: readonly NewEpisodeGroup[], max = MAX_SHOW_NOTICES): EpisodeNotice[] {
  if (groups.length <= max) return groups.map(groupNotice)
  return [...groups.slice(0, max).map(groupNotice), moreShowsNotice(groups.length - max)]
}

/** A tab in front's banner: one a check, the show with the newest arrival (the feed's first), and how many more. */
export type EpisodeBanner = { key: string; notice: EpisodeNotice; more: number }

export function bannerFor(groups: readonly NewEpisodeGroup[], cursor: string): EpisodeBanner | undefined {
  if (!groups.length) return undefined
  return { key: `new-episodes@${cursor}`, notice: groupNotice(groups[0]), more: groups.length - 1 }
}

// The bell on a show's page (show-off, show-on, show-fail).

/** The bell's words: Notify me, or Notifications on while following. */
export function bellWords(following: boolean, show: string): { label: string; name: string } {
  return following
    ? { label: 'Notifications on', name: `Notifications on for ${show}. Turn them off` }
    : { label: 'Notify me', name: `Notify me of new episodes of ${show}` }
}

/** Why a change didn't stick: turning on, turning off, or the server's limit of 500 (a 409). */
export type FollowFailure = 'on' | 'off' | 'limit'

export function followFailure(follow: boolean, status: number | undefined): FollowFailure {
  return follow && status === 409 ? 'limit' : follow ? 'on' : 'off'
}

export function followFailLine(failure: Exclude<FollowFailure, 'limit'>, show: string): string {
  return `Couldn’t turn ${failure} notifications for ${show}. Check your connection and try again.`
}

/**
 * One check. Without a cursor, the first call asks without after and
 * only keeps the cursor: a browser that has just turned notifications on
 * starts from now. Undefined when the server couldn't be reached, so the
 * cursor stays and the next check catches up.
 */
export async function runCheck(cursor: string | undefined, fetch: (after?: string) => Promise<NewEpisodes | undefined>): Promise<NewEpisodes | undefined> {
  const reply = await fetch(cursor)
  if (!reply) return undefined
  return cursor === undefined ? { cursor: reply.cursor, shows: [] } : reply
}

// What this browser keeps for each person on each server: the cursor, the
// switch (off only when turned off here) and the banner, which every tab of
// the browser shows until it is opened or dismissed.

const CURSOR = 'wmm.newEpisodes.cursor:'
const OFF = 'wmm.newEpisodes.off:'
const BANNER = 'wmm.newEpisodes.banner:'
const ASKED = 'wmm.newEpisodes.asked'
const CHANGED = 'wmm.newEpisodes.libraryChangedAt'
const SIGNED_IN = 'wmm.newEpisodes.signedIn'

/** Who the browser keeps these for: a server and a person on it. */
export function personKey(serverId: string, userId: number): string {
  return `${serverId}:${userId}`
}
export const switchStorageKey = (person: string) => OFF + person
export const bannerStorageKey = (person: string) => BANNER + person

function read(key: string): string | undefined {
  try { return localStorage.getItem(key) ?? undefined } catch { return undefined }
}
function write(key: string, value: string | undefined) {
  try {
    if (value === undefined) localStorage.removeItem(key)
    else localStorage.setItem(key, value)
  } catch { /* storage may be blocked */ }
}

export const loadCursor = (person: string) => read(CURSOR + person)
export const saveCursor = (person: string, cursor: string) => write(CURSOR + person, cursor)
export const forgetCursor = (person: string) => write(CURSOR + person, undefined)

/** This browser's switch for this person: on unless turned off here. */
export const notifySwitch = (person: string) => read(OFF + person) !== '1'
export const setNotifySwitch = (person: string, on: boolean) => write(OFF + person, on ? undefined : '1')

export function loadBanner(person: string): EpisodeBanner | undefined {
  try {
    const b = JSON.parse(read(BANNER + person) ?? 'null') as EpisodeBanner | null
    return b && typeof b.key === 'string' && b.notice ? b : undefined
  } catch {
    return undefined
  }
}
export const saveBanner = (person: string, banner: EpisodeBanner) => write(BANNER + person, JSON.stringify(banner))
export const clearBanner = (person: string) => write(BANNER + person, undefined)

/**
 * The switch turned off: the cursor goes, so turning it on again starts
 * from now, and so does the banner, so an old one doesn't come back with
 * it. The browser's permission changing keeps both: the banner never
 * needed it.
 */
export function forgetPlace(person: string) {
  forgetCursor(person)
  clearBanner(person)
}

// When a library last changed, shared by this browser's tabs so the one that
// takes the lock still checks when the window ends; kept in the page too
// where storage is blocked.
let changedHere: number | undefined
export function noteLibraryChange(at: number) {
  changedHere = at
  write(CHANGED, String(at))
}
export function lastLibraryChange(): number | undefined {
  const stored = Number(read(CHANGED))
  return Number.isFinite(stored) && stored > 0 ? Math.max(stored, changedHere ?? 0) : changedHere
}

/**
 * Who the browser is signed in as now, by the last tab to ask the server.
 * A tab still showing an earlier person after another tab signed in as
 * someone else lets the lock go and checks nothing, so it never keeps the
 * new person's cursor under the old one.
 */
export const signedInStorageKey = SIGNED_IN
export const noteSignedIn = (userId: number | undefined) => write(SIGNED_IN, userId === undefined ? undefined : String(userId))
export function signedInHere(userId: number | undefined): boolean {
  if (userId === undefined) return false
  const now = read(SIGNED_IN)
  return now === undefined || now === String(userId)
}

/** Signing out forgets every cursor and banner this browser kept: the next sign-in starts from now. */
export function forgetOnSignOut() {
  try {
    const keys: string[] = []
    for (let i = 0; i < localStorage.length; i++) {
      const k = localStorage.key(i)
      if (k?.startsWith(CURSOR) || k?.startsWith(BANNER)) keys.push(k)
    }
    keys.forEach((k) => localStorage.removeItem(k))
  } catch { /* storage may be blocked */ }
}

/**
 * The first Notify me in a browser that can still ask explains first:
 * once, with its own mark rather than the reminders', so each feature
 * explains itself once.
 */
export function shouldExplainFollow(permission: NotificationPermission | 'unsupported'): boolean {
  return permission === 'default' && !read(ASKED)
}
export const noteFollowAsked = () => write(ASKED, '1')

/**
 * A followed show's line in Settings › Notifications: the newest episode
 * that arrived since the person followed it, and when; or none yet; or, for
 * a show that has left the library, that it has.
 */
export function followLine(f: Pick<Follow, 'show' | 'latest' | 'followedAt'>): { text: string; gone: boolean } {
  if (!f.show) return { text: 'Not in the library now', gone: true }
  if (!f.latest || Date.parse(f.latest.arrivedAt) < Date.parse(f.followedAt)) return { text: 'No new episodes yet', gone: false }
  return { text: `${episodeCode(f.latest)} · ${relativeTime(f.latest.arrivedAt)}`, gone: false }
}

/** Why Stop notifying didn't stick, on the show's row. */
export function stopFailLine(show: string): string {
  return `Couldn’t stop notifying, so you still follow ${show}. Check your connection and try again.`
}

/**
 * What Settings › Notifications says about this browser's system
 * notifications: allowed; not asked yet; blocked;
 * a plain http:// address, where nothing shows at all; or a browser with no
 * notifications, which the boards don't draw. The switch is apart from it:
 * on by default, it also governs the banner, which needs no permission.
 */
export type BrowserState = 'granted' | 'ask' | 'blocked' | 'http' | 'unsupported'

export function browserState(permission: NotificationPermission | 'unsupported', secure: boolean): BrowserState {
  if (!secure) return 'http'
  if (permission === 'unsupported') return 'unsupported'
  if (permission === 'denied') return 'blocked'
  return permission === 'default' ? 'ask' : 'granted'
}
