import type { LiveChannel, LiveGuideChannel, LiveProgramme } from '../../../src/api/liveTV'
import {
  FAVOURITES, HALF_HOUR, HOUR, halfHourStart, isOn, latestStart, moveSelection, startShowing, timeOfDay, timeRange,
} from '../../../src/lib/liveGuide'
import { markHoldsRecord, recordingMarkWords } from '../../../src/lib/liveRecordings'
import { leadText, movedTo, remindState, type LiveReminder, type RemindState } from '../../../src/lib/liveReminders'
import { channelLabel, leftWords } from './liveWords'

// The Samsung guide: two hours across, six
// channels down, the focused programme in a two-line strip above them.
// These rules decide where the remote's arrows go and what the strip and
// a programme's panel say; the screen only draws them. The listings rules
// themselves are the web's (src/lib/liveGuide.ts).

/** Two hours across, four half hours. */
export const GUIDE_HOURS = 2
/** Channels in view at once. */
export const VISIBLE_ROWS = 6

const ms = (iso: string) => Date.parse(iso)

/** What has focus in the grid: a channel's own cell, a programme, or the "No listings" cell of a channel without them. */
export type GuideTarget =
  | { kind: 'channel'; channelId: string }
  | { kind: 'programme'; channelId: string; programmeId: string }
  | { kind: 'none'; channelId: string }

/**
 * The channels the guide opens on: Favourites when the person has chosen
 * some, otherwise All channels, as every app does. Opened at a channel that
 * isn't a favourite (the live player's Guide), it shows them all, so that
 * channel is in it.
 */
export function openingFilter(channels: readonly Pick<LiveGuideChannel, 'id' | 'favourite'>[], at?: string): string {
  if (!channels.some((c) => c.favourite)) return 'all'
  if (at !== undefined && !channels.some((c) => c.id === at && c.favourite)) return 'all'
  return FAVOURITES
}

/** The cell's data-focus id. */
export function targetId(t: GuideTarget): string {
  return t.kind === 'channel' ? `guide/${t.channelId}` : t.kind === 'none' ? `guide/${t.channelId}/~` : `guide/${t.channelId}/${t.programmeId}`
}

export function parseTarget(id: string | undefined): GuideTarget | undefined {
  const m = id ? /^guide\/([^/]+)(?:\/(.+))?$/.exec(id) : null
  if (!m) return undefined
  if (!m[2]) return { kind: 'channel', channelId: m[1] }
  return m[2] === '~' ? { kind: 'none', channelId: m[1] } : { kind: 'programme', channelId: m[1], programmeId: m[2] }
}

/** The first of the rows drawn: the focused row stays third from the top until the list's end. */
export function firstRow(focusIndex: number, count: number, visible = VISIBLE_ROWS): number {
  return Math.max(0, Math.min(focusIndex - 2, count - visible))
}

/** How far back and on the window may go: from now's half hour to the end of the listings. */
export function guideBounds(now: number, listedThrough: number | undefined) {
  const earliest = halfHourStart(now)
  return { earliest, latest: latestStart(earliest, listedThrough, GUIDE_HOURS) }
}

export type GuideRowData = Pick<LiveGuideChannel, 'id' | 'listings' | 'programmes'>

function shown(row: GuideRowData, start: number, end: number): LiveProgramme[] {
  return [...row.programmes].sort((a, b) => ms(a.start) - ms(b.start)).filter((p) => ms(p.end) > start && ms(p.start) < end && ms(p.end) > ms(p.start))
}

/** The cell in `row` at `at` (the time ▲ ▼ keep to): the programme on then, else the next one in view, else the last; or the No listings cell. */
export function cellAt(row: GuideRowData, at: number, start: number, end: number): GuideTarget {
  if (!row.listings) return { kind: 'none', channelId: row.id }
  const list = shown(row, start, end)
  const pick = list.find((p) => ms(p.start) <= at && at < ms(p.end)) ?? list.find((p) => ms(p.start) >= at) ?? list[list.length - 1]
  return pick ? { kind: 'programme', channelId: row.id, programmeId: pick.id } : { kind: 'none', channelId: row.id }
}

export type GuideKey = 'up' | 'down' | 'left' | 'right'

/** Where a key leaves the guide: the tabs above it (▲ from the first channel), or the menu (◀ from the first programme). */
export type GuideExit = 'above' | 'rail'

export type GuideStep = { target: GuideTarget; start: number; anchor: number }

/**
 * One arrow in the grid. ◀ ▶ go programme by programme; at the window's
 * edge, with more of the programme out of view, the window moves half an
 * hour and focus stays; a programme out of view brings the window to it.
 * ▲ ▼ keep to `anchor`, the time focus came from, as cable guides do, even
 * through channels without listings. ◀ from the first programme in view,
 * with the window at its earliest, goes to the menu. Nothing wraps: ▼ on
 * the last channel and ▶ on the last listed programme stay put.
 */
export function guideStep(rows: GuideRowData[], target: GuideTarget, key: GuideKey, start: number, anchor: number, bounds: { earliest: number; latest: number }): GuideStep | GuideExit | undefined {
  const index = rows.findIndex((r) => r.id === target.channelId)
  if (index < 0) return undefined
  const row = rows[index]
  const end = start + GUIDE_HOURS * HOUR
  if (key === 'up' || key === 'down') {
    const next = rows[index + (key === 'down' ? 1 : -1)]
    if (!next) return key === 'up' ? 'above' : undefined
    if (target.kind === 'channel') return { target: { kind: 'channel', channelId: next.id }, start, anchor }
    return { target: cellAt(next, anchor, start, end), start, anchor }
  }
  if (target.kind === 'channel') {
    if (key === 'left') return 'rail'
    return { target: cellAt(row, anchor, start, end), start, anchor }
  }
  if (target.kind === 'none') return key === 'left' ? 'rail' : undefined
  const current = row.programmes.find((p) => p.id === target.programmeId)
  if (!current) return key === 'left' ? 'rail' : { target: cellAt(row, anchor, start, end), start, anchor }
  // More of this programme beyond the edge: the window moves half an hour, focus stays.
  if (key === 'right' && ms(current.end) > end && start + HALF_HOUR <= bounds.latest) {
    const moved = start + HALF_HOUR
    return { target, start: moved, anchor: Math.max(ms(current.start), moved) }
  }
  if (key === 'left' && ms(current.start) < start && start - HALF_HOUR >= bounds.earliest) {
    const moved = start - HALF_HOUR
    return { target, start: moved, anchor: Math.max(ms(current.start), moved) }
  }
  const next = moveSelection([{ id: row.id, listings: row.listings, programmes: row.programmes }], { channelId: row.id, programmeId: current.id }, key, start, end)
  const programme = next?.programmeId ? row.programmes.find((p) => p.id === next.programmeId) : undefined
  if (!programme) return key === 'left' ? 'rail' : undefined
  const moved = startShowing(programme, start, GUIDE_HOURS, bounds.earliest, bounds.latest)
  // One that ended before the guide's earliest half hour can't be shown: ◀ goes to the menu instead.
  if (ms(programme.end) <= moved) return 'rail'
  if (ms(programme.start) >= moved + GUIDE_HOURS * HOUR) return undefined
  return { target: { kind: 'programme', channelId: row.id, programmeId: programme.id }, start: moved, anchor: Math.max(ms(programme.start), moved) }
}

/**
 * CH ▲ ▼ in the guide: a screen of channels at a time, keeping to the
 * same time; at the ends it stops on the first or last channel.
 */
export function pageStep(rows: GuideRowData[], target: GuideTarget, direction: 'up' | 'down', start: number, anchor: number): GuideStep | undefined {
  const index = rows.findIndex((r) => r.id === target.channelId)
  if (index < 0 || !rows.length) return undefined
  const to = Math.max(0, Math.min(rows.length - 1, index + (direction === 'down' ? VISIBLE_ROWS : -VISIBLE_ROWS)))
  if (to === index) return undefined
  return { target: cellAt(rows[to], anchor, start, start + GUIDE_HOURS * HOUR), start, anchor }
}

/** The four half hours along the top: "8:00 PM", "8:30", "9:00", "9:30": the day's half only on the first. */
export function slotLabels(start: number, locale?: string): string[] {
  return Array.from({ length: GUIDE_HOURS * 2 }, (_, i) => {
    const t = timeOfDay(start + i * HALF_HOUR, locale)
    return i === 0 ? t : t.replace(/\s?[AP]\.?M\.?$/i, '')
  })
}

/** What the corner of the grid says the listings are for: "Tonight", "This morning", "Tomorrow", "Thursday". */
export function dayHeading(start: number, now: number, locale?: string): string {
  const days = Math.round((localDay(start) - localDay(now)) / 86_400_000)
  const hour = new Date(start).getHours()
  if (days <= 0) return hour >= 17 ? 'Tonight' : hour >= 12 ? 'This afternoon' : 'This morning'
  if (days === 1) return 'Tomorrow'
  return new Intl.DateTimeFormat(locale, { weekday: 'long' }).format(start)
}

const localDay = (at: number) => { const d = new Date(at); return new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime() }

/** "Season 6 · Episode 10", or the listing's own label; nothing for a film. */
export function seasonEpisode(p: Pick<LiveProgramme, 'season' | 'episode' | 'episodeLabel' | 'kind'>): string {
  if (p.season != null && p.episode != null) return p.season >= 1000 ? `${p.season} · Episode ${p.episode}` : `Season ${p.season} · Episode ${p.episode}`
  return p.kind === 'movie' ? '' : p.episodeLabel ?? ''
}

/**
 * A cell's second line, in words: New or Live, then the episode's title
 * (or its season and number), a film's year, and "on since 6:00" for one
 * that started before the window.
 */
export function cellLine(p: LiveProgramme, start: number, locale?: string): string {
  const begun = ms(p.start) < start ? `on since ${timeOfDay(ms(p.start), locale).replace(/\s?[AP]\.?M\.?$/i, '')}` : undefined
  const what = p.kind === 'movie' ? `Movie${p.airDate ? ` · ${p.airDate.slice(0, 4)}` : ''}` : p.episodeTitle || seasonEpisode(p)
  return [p.new ? 'New' : p.live ? 'Live' : undefined, what || undefined, begun].filter(Boolean).join(' · ')
}

// ---------------------------------------------------------------- the strip

/** The focused programme in two lines above the grid: what it is, when, and what OK does. */
export type Strip = {
  title: string
  isNew: boolean
  /** "8:00–9:00 PM · 48 min left", "Tonight, 9:00–10:00 PM". */
  when: string
  /** "7.1 LKSD". */
  where: string
  /** "Season 6 · Episode 10 · Pies. The six who are left…" */
  about: string
  /** What OK does: "Watch", "Record or remind". */
  ok: string
  airing: boolean
}

/** "Tonight, 9:00–10:00 PM", "Tomorrow, 6:00–7:00 PM", "Thursday, 9:00–10:00 PM". */
export function laterWords(start: number, end: number, now: number, locale?: string): string {
  const days = Math.round((localDay(start) - localDay(now)) / 86_400_000)
  const day = days <= 0 ? (new Date(start).getHours() >= 17 ? 'Tonight' : 'Today') : days === 1 ? 'Tomorrow' : new Intl.DateTimeFormat(locale, { weekday: 'long' }).format(start)
  return `${day}, ${timeRange(start, end, '–', locale)}`
}

/** What a later programme's OK opens, in two or three words. */
export function laterOk(canRecord: boolean, canRemind: boolean): string {
  if (canRecord && canRemind) return 'Record or remind'
  if (canRecord) return 'Record'
  if (canRemind) return 'Remind me'
  return 'Choices'
}

export function stripFor(channel: LiveChannel, programme: LiveProgramme | undefined, ctx: HeroContext): Strip {
  const { now, locale } = ctx
  const where = channelLabel(channel)
  if (!programme) return { title: channel.name, isNew: false, when: 'No listings', where, about: 'This channel has no listings. It still plays.', ok: 'Watch', airing: true }
  const p = programme
  const start = ms(p.start), end = ms(p.end)
  const airing = isOn(p, now)
  const state = remindState(p, ctx.reminder ?? p.reminder, ctx.lead, now)
  const about = [[seasonEpisode(p), p.episodeTitle].filter(Boolean).join(' · '), p.description].filter(Boolean).join('. ')
  return {
    title: p.title, isNew: !!p.new, airing, where,
    when: airing ? `${timeRange(start, end, '–', locale)} · ${leftWords(end, now)}` : laterWords(start, end, now, locale),
    about: about || p.categories?.join(' · ') || '',
    ok: airing ? 'Watch' : laterOk(ctx.canRecord && end > now, remindable(state)),
  }
}

/** Where now falls across the window, 0–1, or undefined when it isn't in view. */
export function nowFraction(start: number, now: number): number | undefined {
  const f = (now - start) / (GUIDE_HOURS * HOUR)
  return f >= 0 && f < 1 ? f : undefined
}

// ---------------------------------------------------------------- what the strip and panel know

export type HeroContext = { now: number; favourite: boolean; reminder?: LiveReminder | boolean; lead: number; canRecord: boolean; locale?: string }

/** Whether a reminder can be set or removed for it. */
export function remindable(state: RemindState) { return state === 'remind' || state === 'set' || state === 'moved' }

// ---------------------------------------------------------------- menus

/** The live player's and Recordings' menus share these. */
export type MenuIcon = 'record' | 'bell' | 'bellOff' | 'play' | 'star' | 'starFilled' | 'start' | 'stop' | 'edit' | 'close' | 'swap' | 'trash' | 'guide'
export type MenuAction = 'record' | 'recordOptions' | 'remind' | 'forget' | 'watch' | 'favourite' | 'unfavourite'
export type MenuItem<A extends string = MenuAction> = { id: A; label: string; icon: MenuIcon; disabled?: boolean }
export type Menu<A extends string = MenuAction> = { title: string; subtitle: string; items: MenuItem<A>[]; note?: string }

// ---------------------------------------------------------------- a programme's panel

export type PanelRowId = 'recordOnce' | 'recordSeries' | 'recordMore' | 'recordOptions' | 'remind' | 'forget' | 'watch' | 'favourite' | 'unfavourite'
export type PanelMark = 'dot' | 'ring' | 'bell' | 'bellOff' | 'play' | 'star' | 'starFilled' | 'record'
export type PanelRow = { id: PanelRowId; label: string; detail?: string; mark: PanelMark; next?: boolean; disabled?: boolean }
export type ProgrammePanelView = { title: string; when: string; where: string; about: string; rows: PanelRow[]; foot?: string }

/**
 * OK on a later programme, or Hold OK on any: Record this episode, Record
 * every new episode, more ways to record, Remind me, Watch the channel now
 * and its favourite, in that order. A programme already set to record
 * offers its recording's options instead of the first three.
 */
export function programmePanel(channel: LiveChannel, p: LiveProgramme, ctx: HeroContext & { onNow?: string }): ProgrammePanelView {
  const { now, locale } = ctx
  const start = ms(p.start), end = ms(p.end)
  const airing = isOn(p, now)
  const series = !!p.seriesKey
  const state = remindState(p, ctx.reminder ?? p.reminder, ctx.lead, now)
  const marked = ctx.canRecord && markHoldsRecord(p.recording)
  const rows: PanelRow[] = []
  if (ctx.canRecord && end > now) {
    if (marked) rows.push({ id: 'recordOptions', label: 'Recording options', detail: recordingMarkWords(p.recording), mark: 'record', next: true })
    else {
      rows.push({ id: 'recordOnce', label: airing ? 'Record the rest' : series ? 'Record this episode' : 'Record this programme', mark: 'dot' })
      if (series) rows.push({ id: 'recordSeries', label: 'Record every new episode', mark: 'ring' })
      rows.push({ id: 'recordMore', label: 'More ways to record', detail: series ? 'Every episode, any channel, start and end' : 'Start and end', mark: 'record', next: true })
    }
  }
  if (state === 'remind') rows.push({ id: 'remind', label: `Remind me ${leadText(ctx.lead)}`, mark: 'bell' })
  else if (state === 'set' || state === 'moved') rows.push({ id: 'forget', label: 'Remove reminder', detail: state === 'moved' ? `${movedTo(start, now, locale)} · Reminder ${leadText(ctx.lead)}` : `Reminder set · ${leadText(ctx.lead)}`, mark: 'bellOff' })
  rows.push({ id: 'watch', label: airing ? `Watch ${channel.number}` : `Watch ${channel.number} now`, detail: !channel.available ? 'Not available right now' : !airing && ctx.onNow ? `${ctx.onNow} is on` : undefined, mark: 'play', disabled: !channel.available })
  rows.push(ctx.favourite ? { id: 'unfavourite', label: 'Remove from favourites', detail: channelLabel(channel), mark: 'starFilled' } : { id: 'favourite', label: 'Add to favourites', detail: channelLabel(channel), mark: 'star' })
  const s = stripFor(channel, p, ctx)
  return {
    title: p.title, when: airing ? s.when : laterWords(start, end, now, locale), where: s.where,
    about: [[seasonEpisode(p), p.episodeTitle].filter(Boolean).join(' · '), p.new ? 'New' : undefined, p.description].filter(Boolean).join('. '),
    rows, foot: remindable(state) ? 'Reminders show while WatchMyMedia is open on this TV.' : undefined,
  }
}
