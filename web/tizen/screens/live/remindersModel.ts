import { duration, episodeLine, timeOfDay } from '../../../src/lib/liveGuide'
import {
  dueReminders, movedTo, nextReminderAt, noticeKey, pruneNotices, reminderChannel, reminderProgramme, type LiveReminder,
} from '../../../src/lib/liveReminders'

// Reminders on the TV: the list's words, and
// which reminder the banner shows over any screen while the app is open.
// When a reminder is due and what it says are the web's rules
// (src/lib/liveReminders.ts); this adds the banner's own bookkeeping.

const ms = (iso: string) => Date.parse(iso)
const localDay = (at: number) => { const d = new Date(at); return new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime() }
const weekday = (at: number, locale?: string) => new Intl.DateTimeFormat(locale, { weekday: 'short' }).format(at)

export type ReminderRow = {
  title: string
  /** "S3 E14 · Bargain Bins · 2.1 HRBR-HD". */
  sub: string
  /** "In 18 min", "Tomorrow", "Moved to 9:30 PM", "No longer in the listings". */
  state: string
  tone: 'set' | 'moved' | 'gone'
  /** "Mon 8:30 PM", "Was Tue 8:30 PM". */
  when: string
  label: string
}

/** One row of the Reminders list. */
export function reminderRow(r: LiveReminder, now: number, locale?: string): ReminderRow {
  const start = ms(r.start)
  const sub = [episodeLine(reminderProgramme(r)), reminderChannel(r)].filter(Boolean).join(' · ')
  const at = `${weekday(start, locale)} ${timeOfDay(start, locale)}`
  const days = Math.round((localDay(start) - localDay(now)) / 86_400_000)
  if (r.state === 'gone') return { title: r.title, sub, state: 'No longer in the listings', tone: 'gone', when: `Was ${at}`, label: `${r.title}, no longer in the listings` }
  const state = r.state === 'moved' ? movedTo(start, now, locale)
    : start <= now ? 'On now'
      : days === 0 ? `In ${duration(start - now)}`
        : days === 1 ? 'Tomorrow' : weekday(start, locale)
  return { title: r.title, sub, state, tone: r.state === 'moved' ? 'moved' : 'set', when: at, label: `${r.title}, ${state}, ${at}` }
}

/** The list's heading line: "5 reminders · soonest first · each 5 min before it starts, set in Settings › Playback". */
export function remindersHeading(count: number, lead: number): string {
  const n = count === 1 ? '1 reminder' : `${count} reminders`
  return `${n} · soonest first · each ${lead === 0 ? 'when it starts' : `${lead} min before it starts`}, set in Settings › Playback`
}

// ---------------------------------------------------------------- the banner

/** The banner hides by itself after this long. */
export const BANNER_MS = 15_000

/** The reminder the banner shows now: the soonest due one not yet shown on this TV. */
export function bannerReminder(items: LiveReminder[], lead: number, now: number, seen: Record<string, number>): LiveReminder | undefined {
  return dueReminders(items, lead, now).find((r) => !(noticeKey(r) in seen))
}

/** When to look again: the next reminder coming due, or in 15 s while one is due ("Starting in 4 min" moves on). */
export function nextCheck(items: LiveReminder[], lead: number, now: number): number | undefined {
  if (dueReminders(items, lead, now).length) return now + 15_000
  return nextReminderAt(items, lead, now)
}

const SEEN_KEY = 'wmm.tizen.reminders.seen'

/** The reminders this TV has shown in the last day, so a restart doesn't bring them back. */
export function loadSeen(storage: Storage | undefined, now: number): Record<string, number> {
  try {
    const parsed = JSON.parse(storage?.getItem(SEEN_KEY) ?? '{}') as unknown
    if (!parsed || typeof parsed !== 'object') return {}
    const entries = Object.entries(parsed as Record<string, unknown>).filter((e): e is [string, number] => typeof e[1] === 'number')
    return pruneNotices(Object.fromEntries(entries), now)
  } catch { return {} }
}

export function markSeen(storage: Storage | undefined, seen: Record<string, number>, r: Pick<LiveReminder, 'id' | 'start'>, now: number): Record<string, number> {
  const next = { ...pruneNotices(seen, now), [noticeKey(r)]: now }
  try { storage?.setItem(SEEN_KEY, JSON.stringify(next)) } catch { /* Storage may be full or blocked: shown once this session. */ }
  return next
}
