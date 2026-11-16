import type { components } from '../api/schema'
import { relativeTime } from './format'
import { episodePath } from './showPage'

/**
 * New episodes of the shows a person follows (PLAN-NEW-EPISODES.md §6, §7.5;
 * the words are design/proposals/new-episodes/web's): when this browser
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

/** While a tab is open, the check runs this long after the last library.changed (§6.1). */
export const SETTLE_MS = 3 * 60_000

/** More shows than this at once become these and one notification for the rest (§6.2). */
export const MAX_SHOW_NOTICES = 5

/**
 * The check runs only when it has something to do (§6.1): the person
 * follows a show, this browser's switch is on and notifications are allowed.
 */
export function shouldCheck(o: { follows: number; on: boolean; permission: NotificationPermission | 'unsupported' }): boolean {
  return o.follows > 0 && o.on && o.permission === 'granted'
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

/** One show's notice, opening its page at the earliest new episode (§6.2). */
export function groupNotice(g: NewEpisodeGroup): EpisodeNotice {
  const first = g.episodes[0]
  const last = g.episodes[g.episodes.length - 1]
  const heading = g.count === 1 ? 'New episode' : `${g.count} new episodes`
  const line = !first || !last || (first.season === last.season && first.number === last.number)
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

/**
 * One check (§6.1). Without a cursor, the first call asks without after and
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
 * What Settings › Notifications shows for this browser (the settings-*
 * boards): on or off; not asked yet; blocked; a plain http:// address; or a
 * browser without notifications at all, which the boards don't draw.
 */
export type BrowserState = 'on' | 'off' | 'ask' | 'blocked' | 'http' | 'unsupported'

export function browserState(permission: NotificationPermission | 'unsupported', on: boolean, secure: boolean): BrowserState {
  if (!secure) return 'http'
  if (permission === 'unsupported') return 'unsupported'
  if (permission === 'denied') return 'blocked'
  if (permission === 'default') return 'ask'
  return on ? 'on' : 'off'
}
