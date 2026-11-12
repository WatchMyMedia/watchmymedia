import type { LiveChannel, LiveGuideChannel, LiveProgramme } from '../api/liveTV'

// The guide's listings (PLAN-LIVE-TV-GUIDE.md §6.1–§6.2). Times are instants:
// the grid is laid out by elapsed time, so the hour that repeats when daylight
// saving ends takes its real hour, and Intl labels it twice.

/** The API level whose replies carry listings, logos and now and next. */
export const GUIDE_API_LEVEL = 13

/** Listings show only from a server that has them; otherwise the guide stays as 0.5.0 drew it, and every channel plays. */
export function showListings(apiLevel: number | undefined, capabilities: { guide?: boolean } | undefined): boolean {
  return (apiLevel ?? 0) >= GUIDE_API_LEVEL && !!capabilities?.guide
}

/** The API level with favourite channels, listings search and reminders (G6, PLAN-LIVE-TV-GUIDE.md §5.7). */
export const GUIDE_EXTRAS_LEVEL = 15

/** Favourites, search and reminders show only in the guide with listings, from a server that has them; below it the guide is G2's. */
export function showGuideExtras(apiLevel: number | undefined, capabilities: { guide?: boolean } | undefined): boolean {
  return showListings(apiLevel, capabilities) && (apiLevel ?? 0) >= GUIDE_EXTRAS_LEVEL
}

export const HALF_HOUR = 30 * 60_000
export const HOUR = 60 * 60_000
/** Each request covers 6 hours from a 3-hour boundary: any 2-hour window starting in it fits one reply, and the next block is prefetched. */
export const FETCH_HOURS = 6
export const BLOCK = 3 * HOUR

/** The start of the half hour holding `now`, by subtracting elapsed time so the repeated hour keeps both halves. */
export function halfHourStart(now: number): number {
  const local = new Date(now)
  return now - (local.getMinutes() % 30) * 60_000 - local.getSeconds() * 1000 - local.getMilliseconds()
}

export function fetchBlock(start: number): number {
  return Math.floor(start / BLOCK) * BLOCK
}

export function windowSlots(start: number, hours: number): Date[] {
  return Array.from({ length: hours * 2 }, (_, i) => new Date(start + i * HALF_HOUR))
}

/** The latest a window may start: its end reaches the last listing, and never before now's half hour. */
export function latestStart(nowStart: number, listedThrough: number | undefined, hours: number): number {
  if (listedThrough === undefined) return nowStart
  return Math.max(nowStart, halfHourStart(listedThrough - 1) + HALF_HOUR - hours * HOUR)
}

export function clampStart(start: number, earliest: number, latest: number): number {
  return Math.min(Math.max(start, earliest), Math.max(earliest, latest))
}

const ms = (iso: string) => Date.parse(iso)

export type GuideCell =
  | { kind: 'programme'; programme: LiveProgramme; start: number; end: number; left: number; width: number; before: boolean; after: boolean }
  | { kind: 'gap'; start: number; end: number; left: number; width: number; listingsEnd?: number }

/**
 * Lays a channel's programmes across the window [start, end): each cell is
 * sized by the time it covers, clipped at the window's edges, and marked
 * when its programme started before the window or runs past it. Gaps stay
 * gaps; a trailing gap with no programme after it ends the channel's
 * listings. A programme that overlaps the one before starts where that one
 * ends, so cells never overlap.
 */
export function layoutCells(programmes: LiveProgramme[], start: number, end: number): GuideCell[] {
  const cells: GuideCell[] = []
  const span = end - start
  const place = (from: number, to: number) => ({ left: (from - start) / span * 100, width: (to - from) / span * 100 })
  let cursor = start
  let lastEnd: number | undefined
  for (const programme of [...programmes].sort((a, b) => ms(a.start) - ms(b.start))) {
    const ps = ms(programme.start)
    const pe = ms(programme.end)
    if (!(pe > ps)) continue
    if (pe <= start) { lastEnd = Math.max(lastEnd ?? pe, pe); continue }
    if (ps >= end) break
    const from = Math.max(ps, cursor)
    const to = Math.min(pe, end)
    if (to <= from) continue
    if (from > cursor) cells.push({ kind: 'gap', start: cursor, end: from, ...place(cursor, from) })
    cells.push({ kind: 'programme', programme, start: from, end: to, ...place(from, to), before: ps < start, after: pe > end })
    cursor = to
    lastEnd = Math.max(lastEnd ?? pe, pe)
  }
  if (cursor < end) {
    const more = programmes.some((p) => ms(p.end) > ms(p.start) && ms(p.start) >= cursor)
    cells.push({ kind: 'gap', start: cursor, end, ...place(cursor, end), listingsEnd: !more && lastEnd !== undefined && lastEnd <= cursor ? lastEnd : undefined })
  }
  return cells
}

export function isOn(programme: LiveProgramme, now: number): boolean {
  return ms(programme.start) <= now && now < ms(programme.end)
}

/** How far through its programme `now` is, 0–100. */
export function progress(programme: LiveProgramme, now: number): number {
  const start = ms(programme.start)
  const length = ms(programme.end) - start
  return length > 0 ? Math.min(100, Math.max(0, (now - start) / length * 100)) : 0
}

/** "48 min", "1 h", "2 h 5 min". */
export function duration(milliseconds: number): string {
  const minutes = Math.max(1, Math.ceil(milliseconds / 60_000))
  if (minutes < 60) return `${minutes} min`
  return `${Math.floor(minutes / 60)} h${minutes % 60 ? ` ${minutes % 60} min` : ''}`
}

/** What is on and what follows at `now`, from a channel's now and next, so the banner moves on at the end time without asking the server. */
export function nowAndNext(channel: Pick<LiveChannel, 'now' | 'next'>, now: number): { now?: LiveProgramme; next?: LiveProgramme } {
  if (channel.now && ms(channel.now.end) > now && ms(channel.now.start) <= now) return { now: channel.now, next: channel.next }
  if (channel.next && isOn(channel.next, now)) return { now: channel.next }
  return { next: channel.next && ms(channel.next.start) > now ? channel.next : undefined }
}

/** "S2 E4 · The Low Tide", "2026 E14", "Movie · 1958"; empty for news. A season numbered by year is shown as the year. */
export function episodeLine(p: LiveProgramme): string {
  const number = p.season != null && p.episode != null ? (p.season >= 1000 ? `${p.season} E${p.episode}` : `S${p.season} E${p.episode}`)
    : p.episodeLabel ? p.episodeLabel
      : p.kind === 'movie' && p.airDate ? `Movie · ${p.airDate.slice(0, 4)}` : ''
  return [number, p.episodeTitle].filter(Boolean).join(' · ')
}

function spokenEpisode(p: LiveProgramme): string {
  if (p.season != null && p.episode != null) return p.season >= 1000 ? `${p.season} episode ${p.episode}` : `season ${p.season} episode ${p.episode}`
  return p.episodeLabel ?? ''
}

function clock(locale?: string) {
  return new Intl.DateTimeFormat(locale, { hour: 'numeric', minute: '2-digit' })
}

/** "8:00–9:00 PM", or "11:30 PM–12:30 AM": the first time drops AM or PM when both share it. */
export function timeRange(start: number, end: number, separator = '–', locale?: string): string {
  const format = clock(locale)
  const first = format.formatToParts(start)
  const period = (parts: Intl.DateTimeFormatPart[]) => parts.find((part) => part.type === 'dayPeriod')?.value
  let shown = first
  if (period(first) !== undefined && period(first) === period(format.formatToParts(end))) {
    const at = first.findIndex((part) => part.type === 'dayPeriod')
    shown = first.filter((_, i) => i !== at && !(i === at - 1 && first[i].type === 'literal'))
  }
  return plain(shown.map((part) => part.value).join('').trim() + separator + format.format(end))
}

// Some ICU versions put a narrow no-break space before AM and PM; others a space.
const plain = (text: string) => text.replace(/\u202f/g, ' ')

export function timeOfDay(at: number, locale?: string): string {
  return plain(clock(locale).format(at))
}

/** A cell's accessible name: "8:00 to 8:30 PM, The Sleuth Files, season 2 episode 4, New". */
export function programmeLabel(p: LiveProgramme, locale?: string): string {
  const episode = spokenEpisode(p)
  return [timeRange(ms(p.start), ms(p.end), ' to ', locale), p.title, episode, p.new ? 'New' : '', p.live ? 'Live' : ''].filter(Boolean).join(', ')
}

function localDay(at: number): number {
  const d = new Date(at)
  return new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime()
}

function addDays(day: number, n: number): number {
  const d = new Date(day)
  return new Date(d.getFullYear(), d.getMonth(), d.getDate() + n).getTime()
}

export type GuideDay = { label: string; short: string; start: number }

/** Today, Tomorrow, then each day listed, to the day listings end. */
export function guideDays(now: number, listedThrough: number | undefined, locale?: string): GuideDay[] {
  const weekday = new Intl.DateTimeFormat(locale, { weekday: 'short' })
  const today = localDay(now)
  const days: GuideDay[] = []
  for (let i = 0; i < 15; i++) {
    const start = addDays(today, i)
    if (i > 0 && (listedThrough === undefined || start >= listedThrough)) break
    days.push({ label: i === 0 ? 'Today' : i === 1 ? 'Tomorrow' : weekday.format(start), short: weekday.format(start), start })
  }
  return days
}

/** The day holding `at`, as an index into guideDays. */
export function dayIndex(days: GuideDay[], at: number): number {
  let index = 0
  days.forEach((day, i) => { if (day.start <= at) index = i })
  return index
}

/** A day's window starts at now's time of day on that day; today's at now. */
export function dayStart(day: GuideDay, now: number): number {
  const from = new Date(halfHourStart(now))
  const d = new Date(day.start)
  return localDay(now) === day.start ? halfHourStart(now) : new Date(d.getFullYear(), d.getMonth(), d.getDate(), from.getHours(), from.getMinutes()).getTime()
}

/** "7 PM" today, else "Wed 7 PM"; minutes only when they aren't :00. */
export function shortDayTime(at: number, now: number, locale?: string): string {
  const d = new Date(at)
  const options: Intl.DateTimeFormatOptions = { hour: 'numeric', ...(d.getMinutes() ? { minute: '2-digit' } : {}) }
  if (localDay(at) !== localDay(now)) options.weekday = 'short'
  return plain(new Intl.DateTimeFormat(locale, options).format(at))
}

export type GuideFilter = { id: string; label: string; match: RegExp }
/** Filters built on the feed's own categories; one shows only when some programme has it. */
export const GUIDE_FILTERS: GuideFilter[] = [
  { id: 'movie', label: 'Movies', match: /^movies?$/i },
  { id: 'sports', label: 'Sports', match: /sport|football|soccer|basketball|baseball|hockey|golf|tennis|racing|boxing|wrestling|olympics/i },
  { id: 'news', label: 'News', match: /news|weather/i },
  { id: 'kids', label: 'Kids', match: /child|kids|animated|animation/i },
]

export function inFilter(p: LiveProgramme, filter: GuideFilter): boolean {
  return (p.categories ?? []).some((category) => filter.match.test(category)) || (filter.id === 'movie' && p.kind === 'movie')
}

export function availableFilters(channels: Pick<LiveGuideChannel, 'programmes'>[]): GuideFilter[] {
  return GUIDE_FILTERS.filter((filter) => channels.some((channel) => channel.programmes.some((p) => inFilter(p, filter))))
}

/** A channel stays under a filter while any programme in the window has the category. */
export function channelInFilter(channel: Pick<LiveGuideChannel, 'programmes'>, filter: GuideFilter | undefined, start: number, end: number): boolean {
  return !filter || channel.programmes.some((p) => ms(p.end) > start && ms(p.start) < end && inFilter(p, filter))
}

/** The Favourites chip's id: it comes first among the filters (§6.5). */
export const FAVOURITES = 'favourites'

/** The Favourites chip shows once the person has a favourite, and stays while it is chosen so its empty state can show. */
export function showFavouritesChip(channels: Pick<LiveGuideChannel, 'favourite'>[], filterId: string): boolean {
  return filterId === FAVOURITES || channels.some((channel) => channel.favourite)
}

/**
 * The guide's rows, in channel order. Under Favourites, a channel shows
 * when it is a favourite, with or without listings: the rule every client
 * shares (§6.5). Under a category, a channel shows while a programme in
 * the window has it, and a channel without listings always shows.
 */
export function guideRows<T extends Pick<LiveGuideChannel, 'listings' | 'programmes' | 'favourite'>>(channels: T[], filterId: string, start: number, end: number): T[] {
  if (filterId === FAVOURITES) return channels.filter((channel) => !!channel.favourite)
  const filter = GUIDE_FILTERS.find((f) => f.id === filterId)
  return channels.filter((channel) => !channel.listings || channelInFilter(channel, filter, start, end))
}

/** The wide panel's picture: a matched TMDB still first (credited), else the provider's. */
export function panelPicture(p: LiveProgramme): { url: string; tmdb: boolean } | undefined {
  if (p.stillUrl) return { url: p.stillUrl, tmdb: true }
  if (p.pictureUrl) return { url: p.pictureUrl, tmdb: p.pictureSource === 'tmdb' }
  return undefined
}

export type GuideSelection = { channelId: string; programmeId?: string }
export type GuideRow = { id: string; listings: boolean; programmes: LiveProgramme[] }
export type GuideKey = 'left' | 'right' | 'up' | 'down' | 'home' | 'end'

function visible(programmes: LiveProgramme[], start: number, end: number) {
  return [...programmes].sort((a, b) => ms(a.start) - ms(b.start)).filter((p) => ms(p.end) > start && ms(p.start) < end && ms(p.end) > ms(p.start))
}

/**
 * Where an arrow key moves the selection. Left and right go to the
 * programme before or after, even past the window's edge (the caller moves
 * the window to show it); up and down go to the programme on at the same
 * time in the next channel, or that channel itself when it has no
 * listings there; Home and End to the row's first and last in the window.
 */
export function moveSelection(rows: GuideRow[], selection: GuideSelection, key: GuideKey, start: number, end: number): GuideSelection | undefined {
  const index = rows.findIndex((row) => row.id === selection.channelId)
  if (index < 0) return rows[0] && { channelId: rows[0].id, programmeId: rows[0].listings ? visible(rows[0].programmes, start, end)[0]?.id : undefined }
  const row = rows[index]
  const sorted = [...row.programmes].sort((a, b) => ms(a.start) - ms(b.start))
  const current = sorted.find((p) => p.id === selection.programmeId)
  switch (key) {
    case 'left': case 'right': {
      if (!current) return undefined
      const at = sorted.indexOf(current) + (key === 'right' ? 1 : -1)
      return sorted[at] ? { channelId: row.id, programmeId: sorted[at].id } : undefined
    }
    case 'home': case 'end': {
      const shown = row.listings ? visible(row.programmes, start, end) : []
      const pick = key === 'home' ? shown[0] : shown[shown.length - 1]
      return pick ? { channelId: row.id, programmeId: pick.id } : undefined
    }
    case 'up': case 'down': {
      const next = rows[index + (key === 'down' ? 1 : -1)]
      if (!next) return undefined
      if (!next.listings) return { channelId: next.id }
      const at = Math.max(current ? ms(current.start) : start, start)
      const shown = visible(next.programmes, start, end)
      const pick = shown.find((p) => ms(p.start) <= at && at < ms(p.end)) ?? shown.find((p) => ms(p.start) >= at) ?? shown[shown.length - 1]
      return { channelId: next.id, programmeId: pick?.id }
    }
  }
}

/** The window start that shows a programme, moving by half hours from `start`, within the window's bounds. */
export function startShowing(p: LiveProgramme, start: number, hours: number, earliest: number, latest: number): number {
  let next = start
  const length = hours * HOUR
  while (ms(p.end) <= next && next - HALF_HOUR >= earliest) next -= HALF_HOUR
  while (ms(p.start) >= next + length && next + HALF_HOUR <= latest) next += HALF_HOUR
  return next
}
