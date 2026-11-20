import type { LiveChannel, LiveGuideChannel, LiveProgramme } from '../../../src/api/liveTV'
import {
  HALF_HOUR, HOUR, duration, episodeLine, halfHourStart, isOn, latestStart, moveSelection, progress, startShowing, timeOfDay, timeRange,
} from '../../../src/lib/liveGuide'
import { markHoldsRecord, recordingMarkWords } from '../../../src/lib/liveRecordings'
import { leadText, movedTo, onAt, remindState, type LiveReminder, type RemindState } from '../../../src/lib/liveReminders'
import { channelLabel, leftWords } from './liveWords'

// The Samsung guide: two hours across, seven
// channels down, the focused programme in the hero. These rules decide
// where the remote's arrows go and what the hero and Hold OK say; the
// screen only draws them. The listings rules themselves are the web's
// (src/lib/liveGuide.ts).

/** Two hours across, four half hours. */
export const GUIDE_HOURS = 2
/** Channels in view at once. */
export const VISIBLE_ROWS = 7

const ms = (iso: string) => Date.parse(iso)

/** What has focus in the grid: a channel's own cell, a programme, or the "No listings" cell of a channel without them. */
export type GuideTarget =
  | { kind: 'channel'; channelId: string }
  | { kind: 'programme'; channelId: string; programmeId: string }
  | { kind: 'none'; channelId: string }

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

/** The first of the rows drawn: the focused row stays fourth from the top until the list's end. */
export function firstRow(focusIndex: number, count: number, visible = VISIBLE_ROWS): number {
  return Math.max(0, Math.min(focusIndex - 3, count - visible))
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

/** Where a key leaves the guide: the row above the grid (▲ from the first channel), or the rail (◀ from a channel cell). */
export type GuideExit = 'above' | 'rail'

export type GuideStep = { target: GuideTarget; start: number; anchor: number }

/**
 * One arrow in the grid. ◀ ▶ go programme by programme; at the window's
 * edge, with more of the programme out of view, the window moves half an
 * hour and focus stays; a programme out of view brings the window to it.
 * ▲ ▼ keep to `anchor`, the time focus came from, as cable guides do, even
 * through channels without listings. ◀ from the first programme goes to
 * the channel's cell, and ◀ again to the rail.
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
  if (target.kind === 'none') return key === 'left' ? { target: { kind: 'channel', channelId: row.id }, start, anchor } : undefined
  const current = row.programmes.find((p) => p.id === target.programmeId)
  if (!current) return { target: { kind: 'channel', channelId: row.id }, start, anchor }
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
  if (!programme) return key === 'left' ? { target: { kind: 'channel', channelId: row.id }, start, anchor } : undefined
  const moved = startShowing(programme, start, GUIDE_HOURS, bounds.earliest, bounds.latest)
  // One that ended before the guide's earliest half hour can't be shown: ◀ goes to the channel instead.
  if (ms(programme.end) <= moved) return { target: { kind: 'channel', channelId: row.id }, start, anchor }
  if (ms(programme.start) >= moved + GUIDE_HOURS * HOUR) return undefined
  return { target: { kind: 'programme', channelId: row.id, programmeId: programme.id }, start: moved, anchor: Math.max(ms(programme.start), moved) }
}

/** The four half hours along the top: "8:00 PM", "8:30 PM", … */
export function slotLabels(start: number): string[] {
  return Array.from({ length: GUIDE_HOURS * 2 }, (_, i) => timeOfDay(start + i * HALF_HOUR))
}

/** Where now falls across the window, 0–1, or undefined when it isn't in view. */
export function nowFraction(start: number, now: number): number | undefined {
  const f = (now - start) / (GUIDE_HOURS * HOUR)
  return f >= 0 && f < 1 ? f : undefined
}

// ---------------------------------------------------------------- the hero

export type Hero = {
  channel: LiveChannel
  programme?: LiveProgramme
  favourite: boolean
  /** "On now", "Later today", "Tomorrow", "Wednesday"; "Live" without listings. */
  when: string
  airing: boolean
  /** "Reminder set · 5 min before", "Moved to 9:30 PM · Reminder 5 min before". */
  reminderLine?: string
  /** The household's recording, in words: "Will record · Series", "Recording now". */
  recordingLine?: string
  title: string
  episode: string
  tags: { new: boolean; live: boolean; hd: boolean }
  categories: string
  /** "Mon 8:00–9:00 PM", or the no-listings line. */
  time: string
  progress?: number
  left?: string
  /** "Starts in 48 min", later today only. */
  startsIn?: string
  /** "On 4.1 at 9:00 PM" for a later programme. */
  later?: string
  description?: string
  /** "Watch 4.1", or "Watch 4.1 now" for a later programme. */
  watch: string
  /** What Hold OK offers, when it offers anything. */
  hold?: string
}

function dayName(at: number, now: number, locale?: string): string {
  const day = (t: number) => { const d = new Date(t); return new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime() }
  const days = Math.round((day(at) - day(now)) / 86_400_000)
  if (days <= 0) return 'Later today'
  if (days === 1) return 'Tomorrow'
  return new Intl.DateTimeFormat(locale, { weekday: 'long' }).format(at)
}

const weekdayShort = (at: number, locale?: string) => new Intl.DateTimeFormat(locale, { weekday: 'short' }).format(at)

export type HeroContext = { now: number; favourite: boolean; reminder?: LiveReminder | boolean; lead: number; canRecord: boolean; locale?: string }

/** Whether Hold OK can set or remove a reminder for it. */
export function remindable(state: RemindState) { return state === 'remind' || state === 'set' || state === 'moved' }

/**
 * What Hold OK offers on a programme, as its menu has it (Android TV's
 * tvHoldHint): Record (Record the rest while it is on) for people who may
 * record, beside the reminder's own action; a recording already set offers
 * its options.
 */
export function holdHint(p: LiveProgramme, now: number, canRecord: boolean, reminded: boolean, canRemind: boolean): string | undefined {
  const marked = canRecord && markHoldsRecord(p.recording)
  const record = canRecord && ms(p.end) > now && !marked
  const remind = reminded ? 'Remove reminder' : 'Remind me'
  if (record && isOn(p, now)) return 'Record the rest'
  if (record && canRemind) return reminded ? 'Record or remove reminder' : 'Record or remind me'
  if (record) return 'Record'
  if (marked) return canRemind ? `Recording options, ${remind.toLowerCase()}` : 'Recording options'
  return canRemind ? remind : undefined
}

/** The hero for a programme, or for a channel (its cell, or one without listings). */
export function heroFor(channel: LiveChannel, programme: LiveProgramme | undefined, ctx: HeroContext): Hero {
  const { now, lead, locale } = ctx
  if (!programme) {
    return {
      channel, favourite: ctx.favourite, when: 'Live', airing: true, title: channel.name, episode: '', tags: { new: false, live: false, hd: false }, categories: '',
      time: 'No listings for this channel. It still plays.', watch: `Watch ${channel.number}`,
    }
  }
  const p = programme
  const start = ms(p.start), end = ms(p.end)
  const airing = isOn(p, now)
  const state = remindState(p, ctx.reminder ?? p.reminder, lead, now)
  const reminderLine = state === 'set' ? `Reminder set · ${leadText(lead)}` : state === 'moved' ? `${movedTo(start, now, locale)} · Reminder ${leadText(lead)}` : undefined
  const mark = ctx.canRecord && markHoldsRecord(p.recording) ? recordingMarkWords(p.recording) : undefined
  const sameDay = new Date(start).toDateString() === new Date(now).toDateString()
  return {
    channel, programme: p, favourite: ctx.favourite, airing,
    when: airing ? 'On now' : dayName(start, now, locale),
    reminderLine, recordingLine: mark,
    title: p.title, episode: episodeLine(p), tags: { new: !!p.new, live: !!p.live, hd: !!p.hd }, categories: (p.categories ?? []).join(' · '),
    time: `${weekdayShort(start, locale)} ${timeRange(start, end, '–', locale)}`,
    ...(airing ? { progress: progress(p, now) / 100, left: leftWords(end, now) } : {}),
    ...(!airing && sameDay && start > now ? { startsIn: `Starts in ${duration(start - now)}` } : {}),
    ...(!airing && start > now ? { later: onAt(channel.number, start, now, locale) } : {}),
    description: p.description,
    watch: airing ? `Watch ${channel.number}` : `Watch ${channel.number} now`,
    hold: holdHint(p, now, ctx.canRecord, state === 'set' || state === 'moved', remindable(state)),
  }
}

// ---------------------------------------------------------------- Hold OK menus

export type MenuIcon = 'record' | 'bell' | 'bellOff' | 'play' | 'star' | 'starFilled' | 'start' | 'stop' | 'edit' | 'close' | 'swap' | 'trash' | 'guide'
export type MenuAction = 'record' | 'recordOptions' | 'remind' | 'forget' | 'watch' | 'favourite' | 'unfavourite'
export type MenuItem<A extends string = MenuAction> = { id: A; label: string; icon: MenuIcon; disabled?: boolean }
export type Menu<A extends string = MenuAction> = { title: string; subtitle: string; items: MenuItem<A>[]; note?: string }

/** Hold OK on a channel: its favourite, and Watch. */
export function channelMenu(channel: LiveChannel, favourite: boolean): Menu {
  return {
    title: channelLabel(channel), subtitle: favourite ? 'A favourite' : 'Not a favourite',
    items: [
      favourite ? { id: 'unfavourite', label: 'Remove from favourites', icon: 'starFilled' } : { id: 'favourite', label: 'Add to favourites', icon: 'star' },
      { id: 'watch', label: `Watch ${channel.number}`, icon: 'play', disabled: !channel.available },
    ],
  }
}

/** Hold OK on a programme: Record (or its options), Remind me or Remove reminder when it can have one, and Watch. */
export function programmeMenu(channel: LiveChannel, p: LiveProgramme, ctx: HeroContext): Menu {
  const { now, locale } = ctx
  const airing = isOn(p, now)
  const ended = ms(p.end) <= now
  const state = remindState(p, ctx.reminder ?? p.reminder, ctx.lead, now)
  const canRemind = remindable(state)
  const marked = ctx.canRecord && markHoldsRecord(p.recording)
  const items: MenuItem[] = []
  if (ctx.canRecord && !ended) items.push(marked ? { id: 'recordOptions', label: 'Recording options', icon: 'record' } : { id: 'record', label: airing ? 'Record the rest' : 'Record', icon: 'record' })
  if (canRemind) items.push(state === 'remind' ? { id: 'remind', label: 'Remind me', icon: 'bell' } : { id: 'forget', label: 'Remove reminder', icon: 'bellOff' })
  items.push({ id: 'watch', label: airing ? `Watch ${channel.number}` : `Watch ${channel.number} now`, icon: 'play', disabled: !channel.available })
  const record = ctx.canRecord && !ended && !marked ? 'Record asks: this episode, or every new one.' : undefined
  const note = canRemind ? [record, 'Reminders show while WatchMyMedia is open on this TV.'].filter(Boolean).join(' ') : record
  const mark = marked ? recordingMarkWords(p.recording) : undefined
  return {
    title: p.title,
    subtitle: [`${weekdayShort(ms(p.start), locale)} ${timeOfDay(ms(p.start), locale)} on ${channel.number}`, mark].filter(Boolean).join(' · '),
    items, note,
  }
}
