import type { LiveProgramme } from '../api/liveTV'
import type { components } from '../api/schema'
import { duration, episodeLine, timeOfDay, timeRange } from './liveGuide'

/**
 * Live TV reminders and listings search (PLAN-LIVE-TV-GUIDE.md §6.5, G7):
 * when a reminder is due, what it says, and how search results and the
 * Reminders list word their times. Reminders live on the server; this page
 * only delivers them while it is open.
 */

export type LiveReminder = components['schemas']['LiveTVReminder']

/** The lead times Settings › Playback offers, in minutes; 5 unless chosen. */
export const LEAD_OPTIONS: { minutes: number; label: string }[] = [
  { minutes: 0, label: 'When it starts' },
  { minutes: 1, label: '1 minute before' },
  { minutes: 5, label: '5 minutes before' },
  { minutes: 10, label: '10 minutes before' },
  { minutes: 15, label: '15 minutes before' },
]
export const DEFAULT_LEAD = 5

export function leadMinutes(value: number | null | undefined): number {
  return LEAD_OPTIONS.some((o) => o.minutes === value) ? (value as number) : DEFAULT_LEAD
}

/** "5 min before", or "when it starts". */
export function leadText(lead: number): string {
  return lead === 0 ? 'when it starts' : `${lead} min before`
}

const ms = (iso: string) => Date.parse(iso)

function localDay(at: number): number {
  const d = new Date(at)
  return new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime()
}

/** How many local days after now's day `at` falls on. */
function daysFrom(now: number, at: number): number {
  return Math.round((localDay(at) - localDay(now)) / 86_400_000)
}

const weekday = (at: number, locale?: string) => new Intl.DateTimeFormat(locale, { weekday: 'short' }).format(at)

/** "Today", else the weekday: "Tue". */
export function dayShort(at: number, now: number, locale?: string): string {
  return daysFrom(now, at) === 0 ? 'Today' : weekday(at, locale)
}

/** The details' line for a later programme: "On 4.1 at 9:00 PM", "On 4.1 tomorrow at 8:00 PM", "On 4.1 Wed at 9:00 PM". */
export function onAt(number: string, start: number, now: number, locale?: string): string {
  const days = daysFrom(now, start)
  const day = days === 0 ? '' : days === 1 ? ' tomorrow' : ` ${weekday(start, locale)}`
  return `On ${number}${day} at ${timeOfDay(start, locale)}`
}

/**
 * What a programme in the details or a search result offers:
 * - remind: it starts more than the lead time ahead, and has no reminder;
 * - set or moved: it has one (moved when a refresh moved the programme);
 * - soon: it starts within the lead time, too soon for a reminder;
 * - none: it is on now or over, so it offers Watch.
 */
export type RemindState = 'remind' | 'set' | 'moved' | 'soon' | 'none'

export function remindState(p: Pick<LiveProgramme, 'start'>, reminder: Pick<LiveReminder, 'state'> | boolean | undefined, lead: number, now: number): RemindState {
  const start = ms(p.start)
  if (start <= now) return 'none'
  if (reminder) return typeof reminder === 'object' && reminder.state === 'moved' ? 'moved' : 'set'
  return start - now > lead * 60_000 ? 'remind' : 'soon'
}

/** "Moved to 9:30 PM", with the day when it isn't today. */
export function movedTo(start: number, now: number, locale?: string): string {
  return `Moved to ${daysFrom(now, start) === 0 ? '' : `${weekday(start, locale)} `}${timeOfDay(start, locale)}`
}

/** A reminder as the guide's programme type, for its episode line and times. */
export function reminderProgramme(r: LiveReminder): LiveProgramme {
  return { id: r.programmeId, title: r.title, episodeTitle: r.episodeTitle, season: r.season, episode: r.episode, start: r.start, end: r.end }
}

/** "7.1 LKSD-HD", or the number alone when the channel is gone. */
export function reminderChannel(r: Pick<LiveReminder, 'number' | 'channelName'>): string {
  return [r.number, r.channelName].filter(Boolean).join(' ')
}

/** The Reminders list's line: "Today 10:00 PM · 7.1 LKSD-HD"; a gone one "Was today 9:00 PM · 4.1 NFLD-HD". */
export function reminderWhen(r: LiveReminder, now: number, locale?: string): string {
  const start = ms(r.start)
  const day = dayShort(start, now, locale)
  const at = `${day} ${timeOfDay(start, locale)} · ${reminderChannel(r)}`
  return r.state === 'gone' ? `Was ${day === 'Today' ? 'today' : day} ${timeOfDay(start, locale)} · ${reminderChannel(r)}` : at
}

/** A gone reminder's times in its panel: "Was Mon 9:00–10:30 PM". */
export function wasWhen(r: LiveReminder, locale?: string): string {
  return `Was ${weekday(ms(r.start), locale)} ${timeRange(ms(r.start), ms(r.end), '–', locale)}`
}

/** A reminder comes from the lead time before its start until the programme ends; a gone one never. */
export function reminderDue(r: LiveReminder, lead: number, now: number): boolean {
  return r.state !== 'gone' && ms(r.start) - lead * 60_000 <= now && now < ms(r.end)
}

/** The reminders due now, soonest first. */
export function dueReminders(items: LiveReminder[], lead: number, now: number): LiveReminder[] {
  return items.filter((r) => reminderDue(r, lead, now)).sort((a, b) => ms(a.start) - ms(b.start))
}

/** When the next reminder comes due, for a timer; undefined when none is ahead. */
export function nextReminderAt(items: LiveReminder[], lead: number, now: number): number | undefined {
  let next: number | undefined
  for (const r of items) {
    if (r.state === 'gone') continue
    const at = ms(r.start) - lead * 60_000
    if (at > now && (next === undefined || at < next)) next = at
  }
  return next
}

/** One notice a reminder and start: a reminder the listings moved comes again at its new time. */
export function noticeKey(r: Pick<LiveReminder, 'id' | 'start'>): string {
  return `${r.id}@${r.start}`
}

/** Keeps a day of delivered notices, so a reload doesn't bring them back and the record stays small. */
export function pruneNotices(seen: Record<string, number>, now: number): Record<string, number> {
  return Object.fromEntries(Object.entries(seen).filter(([, at]) => now - at < 86_400_000))
}

/**
 * What a reminder says, in the banner and the system notification:
 * "Starting in 5 min", "The Sleuth Files", "4.1 Station 4 · S2 E5 · 8:00 PM".
 */
export function reminderNotice(r: LiveReminder, now: number, locale?: string): { heading: string; title: string; line: string; notification: string } {
  const start = ms(r.start)
  const left = start - now
  const heading = left > 30_000 ? `Starting in ${Math.ceil(left / 60_000)} min` : left > -60_000 ? 'Starting now' : 'On now'
  const line = [reminderChannel(r), episodeLine(reminderProgramme(r)), timeOfDay(start, locale)].filter(Boolean).join(' · ')
  return { heading, title: r.title, line, notification: `${heading} · ${r.title}` }
}

/** The shortest query listings search takes. */
export const SEARCH_MIN = 2

/** The words to search for, or undefined until there are enough. */
export function searchTerm(text: string): string | undefined {
  const q = text.trim().replace(/\s+/g, ' ')
  return q.length >= SEARCH_MIN ? q.slice(0, 100) : undefined
}

/** A result's time: "Now on 4.1", "Today 10:00 PM on 2.1", "Tue 9:00 PM on 4.1". */
export function searchWhen(p: Pick<LiveProgramme, 'start' | 'end'>, number: string, now: number, locale?: string): string {
  const start = ms(p.start)
  if (start <= now && now < ms(p.end)) return `Now on ${number}`
  return `${dayShort(start, now, locale)} ${timeOfDay(start, locale)} on ${number}`
}

/** For one on now: " · 8:00–9:45 PM · 1 h 33 min left". */
export function searchSpan(p: Pick<LiveProgramme, 'start' | 'end'>, now: number, locale?: string): string {
  const start = ms(p.start), end = ms(p.end)
  if (!(start <= now && now < end)) return ''
  return ` · ${timeRange(start, end, '–', locale)} · ${duration(end - now)} left`
}
