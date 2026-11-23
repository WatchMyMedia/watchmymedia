import type { Recording, RecordingRule } from '../../../src/api/recordings'
import { duration, timeOfDay, timeRange } from '../../../src/lib/liveGuide'
import {
  RECORDING_HEADINGS, canChooseClash, conflictWords, gapWords, manualRepeatWords, recordingProgress, recordingWords, type RecordingSection,
} from '../../../src/lib/liveRecordings'
import type { PlayRequest } from '../../app/routes'
import type { MenuItem } from './guideModel'
import { seriesWords } from './recordModel'

// Recordings: the focused card's details
// above rows for Recording now, Won't record, Upcoming, Series and
// Recorded, and what OK and Hold OK do on each.

const ms = (iso: string) => Date.parse(iso)
const weekday = (at: number, locale?: string) => new Intl.DateTimeFormat(locale, { weekday: 'short' }).format(at)
const localDay = (at: number) => { const d = new Date(at); return new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime() }

/** "Tonight", "Tomorrow", "Wed". */
function dayWords(at: number, now: number, locale?: string): string {
  const days = Math.round((localDay(at) - localDay(now)) / 86_400_000)
  return days === 0 ? (new Date(at).getHours() >= 17 ? 'Tonight' : 'Today') : days === 1 ? 'Tomorrow' : weekday(at, locale)
}

export type Entry = { kind: 'recording'; section: RecordingSection; item: Recording } | { kind: 'rule'; section: RecordingSection; item: RecordingRule }

export const entryKey = (e: Entry) => `${e.kind}:${e.item.id}`

export type Badge = { label: string; tone: 'rec' | 'wont' | 'part' | 'plain'; mark?: 'dot' | 'ring' }

export type Card = {
  key: string
  title: string
  number?: string
  line: string
  badge?: Badge
  /** 0–1 recorded, while it records. */
  progress?: number
  /** Didn't record: drawn faded. */
  dim: boolean
  picture?: string
  label: string
}

function episode(r: Pick<Recording, 'season' | 'episode' | 'episodeTitle'>): string {
  const n = r.season != null && r.episode != null ? (r.season >= 1000 ? `${r.season} E${r.episode}` : `S${r.season} E${r.episode}`) : ''
  return [n, r.episodeTitle].filter(Boolean).join(' · ')
}

function badgeOf(e: Entry): Badge | undefined {
  if (e.kind === 'rule') return e.item.kind === 'manual' ? { label: 'Manual', tone: 'plain' } : { label: 'Series', tone: 'plain', mark: 'ring' }
  const r = e.item
  switch (r.state) {
    case 'recording': return { label: 'REC', tone: 'rec', mark: 'dot' }
    case 'conflict': return { label: 'Won’t record', tone: 'wont' }
    case 'partial': return { label: 'Partial', tone: 'part' }
    case 'failed': return { label: 'Didn’t record', tone: 'plain' }
    case 'scheduled': return r.ruleId ? { label: r.series ? 'Series' : 'Manual', tone: 'plain', mark: r.series ? 'ring' : undefined } : undefined
    default: return undefined
  }
}

/** A card's second line: what is happening to it. */
export function cardLine(e: Entry, now: number, locale?: string): string {
  if (e.kind === 'rule') {
    const rule = e.item
    return rule.kind === 'manual' ? `${manualRepeatWords(rule, locale)} · Manual` : seriesWords(rule)
  }
  const r = e.item
  const start = ms(r.start), end = ms(r.end)
  switch (r.state) {
    case 'recording': return `${timeRange(start, end, '–', locale)} · ${duration(now - ms(r.startedAt ?? r.start))} so far`
    case 'conflict': return `${dayWords(start, now, locale)} ${timeOfDay(start, locale)} · ${r.reason === 'no_tuners' ? 'no tuner for recordings' : 'the tuners are busy'}`
    case 'scheduled': case 'gone': return [`${dayWords(start, now, locale)} ${timeOfDay(start, locale)}`, episode(r) || undefined, r.state === 'gone' ? 'No longer listed' : undefined].filter(Boolean).join(' · ')
    case 'failed': return `${weekday(start, locale)} · ${recordingWords(r.state, r.reason)}`
    default: {
      const gap = r.gaps[0]
      return [weekday(start, locale), gap ? gapWords(gap, (a, b) => timeRange(a, b, '–', locale)).replace(/^Missing/, 'missing').replace(/: .*$/, '') : episode(r) || timeOfDay(start, locale)].join(' · ')
    }
  }
}

export function card(e: Entry, now: number, mediaUrl?: (path?: string) => string | undefined, locale?: string): Card {
  const line = cardLine(e, now, locale)
  const badge = badgeOf(e)
  const r = e.kind === 'recording' ? e.item : undefined
  const number = r?.number ?? (e.kind === 'rule' ? e.item.number : undefined)
  return {
    key: entryKey(e), title: e.item.title, number, line, badge,
    progress: r?.state === 'recording' ? recordingProgress(r.start, r.end, now) / 100 : undefined,
    dim: r?.state === 'failed', picture: r?.pictureUrl ? mediaUrl?.(r.pictureUrl) : undefined,
    label: [e.item.title, number, line, badge ? badge.label.toLowerCase() : undefined].filter(Boolean).join(', '),
  }
}

/** A row's counter: "4 · soonest first", "Latest 4", "2". */
export function rowCount(section: RecordingSection, count: number): string {
  return section === 'upcoming' ? `${count} · soonest first` : section === 'recorded' ? `Latest ${count}` : String(count)
}

export const SECTION_TITLES: Record<RecordingSection, string> = { ...RECORDING_HEADINGS, series: 'Series' }

// ---------------------------------------------------------------- the details and actions

export type RecordingAction = 'watch' | 'watchStart' | 'instead' | 'stop' | 'cancel' | 'editSeries' | 'deleteSeries' | 'delete' | 'guide'

export type Details = {
  status: string
  tone: 'rec' | 'warn' | 'done' | 'muted'
  mark?: 'rec' | 'ring' | 'wont' | 'done' | 'warn'
  title: string
  episode: string
  meta: string
  description?: string
  /** What OK does, and its words. */
  ok?: { id: RecordingAction; label: string }
  /** What Hold OK offers, in a line. */
  hold?: string
}

const by = (name: string | undefined, me: string) => !name ? '' : name === me ? 'set by you' : `set by ${name}`

/** The Hold OK menu for a card, the first focused; OK does the first that plays or chooses. */
export function actions(e: Entry): MenuItem<RecordingAction>[] {
  if (e.kind === 'rule') {
    const rule = e.item
    return rule.canCancel ? [
      { id: 'editSeries', label: rule.kind === 'manual' ? 'Edit recording' : 'Edit series', icon: 'edit' },
      { id: 'deleteSeries', label: rule.kind === 'manual' ? 'Stop repeating · keep recordings' : 'Delete series · keep recordings', icon: 'trash' },
    ] : []
  }
  const r = e.item
  const items: MenuItem<RecordingAction>[] = []
  if (r.state === 'recording') items.push({ id: 'watchStart', label: 'Watch from the start', icon: 'start' })
  if (r.state === 'done' || r.state === 'partial') items.push({ id: 'watch', label: 'Watch', icon: 'play' })
  if (r.canCancel && canChooseClash(r)) items.push({ id: 'instead', label: 'Record this instead…', icon: 'swap' })
  if (r.canCancel && r.state === 'recording') items.push({ id: 'stop', label: 'Stop recording · keep what’s recorded', icon: 'stop' })
  if (r.canCancel && (r.state === 'scheduled' || r.state === 'conflict' || r.state === 'gone')) items.push({ id: 'cancel', label: 'Don’t record', icon: 'close' })
  if (r.canCancel && r.ruleId && r.state !== 'done' && r.state !== 'partial' && r.state !== 'failed') items.push({ id: 'editSeries', label: r.series ? 'Edit series' : 'Edit recording', icon: 'edit' })
  if (r.canDelete && (r.state === 'done' || r.state === 'partial')) items.push({ id: 'delete', label: 'Delete recording…', icon: 'trash' })
  if (r.state === 'scheduled' || r.state === 'conflict') items.push({ id: 'guide', label: 'Open in the guide', icon: 'guide' })
  return items
}

/** The focused card's details: its state, what it is, and what OK and Hold OK do. */
export function details(e: Entry, now: number, me: string, locale?: string): Details {
  const list = actions(e)
  const ok = list[0] ? { id: list[0].id, label: list[0].label.replace(/…$/, '') } : undefined
  const rest = list.slice(1).map((a) => a.label.replace(/…$/, '').replace(/ · .*$/, '').toLowerCase())
  const hold = rest.length ? rest.join(', ').replace(/^./, (c) => c.toUpperCase()) : undefined
  if (e.kind === 'rule') {
    const rule = e.item
    const manual = rule.kind === 'manual'
    return {
      status: manual ? `Manual · ${manualRepeatWords(rule, locale)}` : `Series · ${rule.newOnly ? 'every new episode' : 'every episode'}`, tone: 'rec', mark: manual ? undefined : 'ring',
      title: rule.title, episode: '',
      meta: [manual ? rule.number : seriesWords(rule), manual ? undefined : rule.padBefore || rule.padAfter ? `${rule.padBefore} min early, ${rule.padAfter} min late` : undefined, by(rule.userName, me)].filter(Boolean).join(' · '),
      description: manual ? undefined : 'An episode already recorded or in your library is skipped.',
      ok, hold,
    }
  }
  const r = e.item
  const start = ms(r.start), end = ms(r.end)
  const meta = [`${r.number} ${r.channelName}`, `${dayWords(start, now, locale)} ${timeRange(start, end, '–', locale)}`, by(r.userName, me)].filter(Boolean).join(' · ')
  const words = recordingWords(r.state, r.reason, r.conflictsWith)
  switch (r.state) {
    case 'recording': return {
      status: `${words} · ${duration(now - ms(r.startedAt ?? r.start))} so far · ends ${timeOfDay(end, locale)}`, tone: 'rec', mark: 'rec', title: r.title, episode: episode(r), meta,
      description: 'Watch from the start plays what has recorded so far and follows it as it records. It needs no tuner.', ok, hold,
    }
    case 'conflict': return {
      status: words, tone: 'warn', mark: 'wont', title: r.title, episode: episode(r), meta,
      description: r.conflictsWith?.length ? `${r.conflictsWith.map((h) => `${h.title} on ${h.number} (${by(h.userName, me)})`).join(' and ')} ${r.conflictsWith.length === 1 ? 'takes the tuner' : 'take the tuners'} then.` : undefined,
      ok, hold,
    }
    case 'scheduled': case 'gone': return { status: r.series ? `${words} · series` : words, tone: 'rec', mark: r.series ? 'ring' : undefined, title: r.title, episode: episode(r), meta, ok, hold }
    case 'done': case 'partial': return {
      status: r.state === 'partial' && r.gaps[0] ? gapWords(r.gaps[0], (a, b) => timeRange(a, b, '–', locale)).replace(/^Missing/, 'Partial · missing').replace(/: .*$/, '') : `${words} · ${weekday(start, locale)} ${timeRange(start, end, '–', locale)}`,
      tone: r.state === 'partial' ? 'warn' : 'done', mark: r.state === 'partial' ? 'warn' : 'done', title: r.title, episode: episode(r), meta,
      description: r.gaps.length ? r.gaps.map((g) => gapWords(g, (a, b) => timeRange(a, b, '–', locale))).join('. ') + '. The rest plays as usual.' : r.mediaFileId ? 'In your library. It plays like any episode or film.' : 'Joining your library.',
      ok, hold,
    }
    default: return { status: words, tone: 'muted', mark: 'warn', title: r.title, episode: episode(r), meta, description: r.state === 'failed' ? 'Nothing was saved.' : undefined, ok, hold }
  }
}

/** What Watch opens: a recording still recording plays from its start; a finished one where it was left. */
export function playRequest(r: Recording): PlayRequest | undefined {
  if (r.state === 'recording') return { kind: 'recording', id: r.id, startMs: 0, title: r.title }
  if (r.state === 'done' || r.state === 'partial') return { kind: 'recording', id: r.id, title: r.title }
  return undefined
}

/** "412 GB free · 2 tuners". */
export function spaceWords(freeBytes: number | undefined, tuners: number | undefined, format: (n: number) => string): string | undefined {
  const parts = [freeBytes !== undefined ? `${format(freeBytes)} free` : undefined, tuners ? `${tuners} ${tuners === 1 ? 'tuner' : 'tuners'}` : undefined].filter(Boolean)
  return parts.length ? parts.join(' · ') : undefined
}

// ---------------------------------------------------------------- the tabs

/**
 * Recordings leads with what can be watched: Ready to watch (recording now,
 * then what has recorded, one card a show), Scheduled (a clash on top,
 * then what will record, by day) and Series.
 */
export type RecordingsTab = 'ready' | 'scheduled' | 'series'

const NUMBERS = ['no', 'one', 'two', 'three', 'four', 'five', 'six', 'seven', 'eight', 'nine', 'ten']
const numberWord = (n: number) => NUMBERS[n] ?? String(n)
const capital = (s: string) => s.charAt(0).toUpperCase() + s.slice(1)

/** "Today", "Yesterday", "Tuesday", "Last Tuesday", or the date: when something recorded. */
export function recordedDay(at: number, now: number, locale?: string): string {
  const days = Math.round((localDay(now) - localDay(at)) / 86_400_000)
  if (days <= 0) return 'Today'
  if (days === 1) return 'Yesterday'
  const name = new Intl.DateTimeFormat(locale, { weekday: 'long' }).format(at)
  if (days < 7) return name
  if (days < 14) return `Last ${name}`
  return new Intl.DateTimeFormat(locale, { day: 'numeric', month: 'long' }).format(at)
}

/** "1 hour", "30 minutes", "1 h 52 min": how long something is. */
export function lengthWords(ms_: number): string {
  const minutes = Math.max(1, Math.round(ms_ / 60_000))
  if (minutes < 60) return `${minutes} ${minutes === 1 ? 'minute' : 'minutes'}`
  if (minutes % 60 === 0) return `${minutes / 60} ${minutes === 60 ? 'hour' : 'hours'}`
  return `${Math.floor(minutes / 60)} h ${minutes % 60} min`
}

/** "Season 2 · Episode 4", or nothing. */
function seasonEpisode(r: Pick<Recording, 'season' | 'episode'>): string {
  if (r.season == null || r.episode == null) return ''
  return r.season >= 1000 ? `${r.season} · Episode ${r.episode}` : `Season ${r.season} · Episode ${r.episode}`
}

/** What recorded, in a few words: its length, or why it isn't whole. */
function recordedState(r: Recording): string {
  if (r.state === 'failed') return recordingWords(r.state, r.reason)
  if (r.state === 'partial') return 'Part recorded'
  return lengthWords(ms(r.end) - ms(r.start))
}

/** One show's recordings, on one card: what OK opens. */
export type ShowGroup = {
  key: string
  title: string
  /** Newest first. */
  items: Recording[]
  latest: Recording
  /** "3 episodes · newest yesterday", or one recording's own line. */
  line: string
  /** Something in it plays. */
  playable: boolean
}

/**
 * What has recorded, one card a show (by title), the show with the newest
 * recording first. A show with one recording plays it on OK; with more,
 * OK opens its episodes.
 */
export function recordedShows(items: readonly Recording[], now: number, locale?: string): ShowGroup[] {
  const groups = new Map<string, Recording[]>()
  for (const r of items) {
    if (!['done', 'partial', 'failed'].includes(r.state)) continue
    const key = r.title.trim().toLowerCase()
    groups.set(key, [...(groups.get(key) ?? []), r])
  }
  return [...groups.entries()].map(([key, list]) => {
    const sorted = [...list].sort((a, b) => ms(b.start) - ms(a.start))
    const latest = sorted[0]
    const line = sorted.length > 1
      ? `${sorted.length} ${sorted[0].series || sorted.some((r) => r.season != null) ? 'episodes' : 'recordings'} · newest ${recordedDay(ms(latest.start), now, locale).replace(/^(Today|Yesterday|Last)\b/, (w) => w.toLowerCase())}`
      : [seasonEpisode(latest) || latest.episodeTitle, recordedState(latest)].filter(Boolean).join(' · ')
    return { key: `show:${key}`, title: latest.title, items: sorted, latest, line, playable: sorted.some((r) => r.state === 'done' || r.state === 'partial') }
  }).sort((a, b) => ms(b.latest.start) - ms(a.latest.start))
}

/** A recording in its show's panel: its name, then where it sits, when it recorded and how long it is. */
export function episodeWords(r: Recording, now: number, locale?: string): { title: string; line: string } {
  const title = r.episodeTitle || seasonEpisode(r) || r.title
  const line = [r.episodeTitle ? seasonEpisode(r) : '', recordedDay(ms(r.start), now, locale), recordedState(r)].filter(Boolean).join(' · ')
  return { title, line }
}

/** What is recording now, under its card: "12 min so far · ends at 9:00 PM". */
export function recordingNowWords(r: Recording, now: number, locale?: string): string {
  return `${duration(now - ms(r.startedAt ?? r.start))} so far · ends at ${timeOfDay(ms(r.end), locale)}`
}

// ---------------------------------------------------------------- Scheduled

export type ScheduledRow = {
  recording: Recording
  time: string
  title: string
  /** "2.1 HRBR · new episode · 1 hour". */
  sub: string
  /** The red dot (will record), ringed (a series), or the orange ring (won't record). */
  mark: 'dot' | 'ring' | 'wont'
  /** "Won't record", "No longer listed". */
  end?: string
}

/** "Tonight", "Today", "Tomorrow", "Thursday": a scheduled recording's day. */
export function scheduledDay(at: number, now: number, locale?: string): string {
  const days = Math.round((localDay(at) - localDay(now)) / 86_400_000)
  if (days <= 0) return new Date(at).getHours() >= 17 ? 'Tonight' : 'Today'
  if (days === 1) return 'Tomorrow'
  return new Intl.DateTimeFormat(locale, { weekday: 'long' }).format(at)
}

/** What will record, and what won't, by day, soonest first. */
export function scheduledDays(items: readonly Recording[], rules: readonly RecordingRule[], now: number, locale?: string): { day: string; rows: ScheduledRow[] }[] {
  const days: { day: string; rows: ScheduledRow[] }[] = []
  for (const r of [...items].filter((x) => ['scheduled', 'conflict', 'gone'].includes(x.state)).sort((a, b) => ms(a.start) - ms(b.start))) {
    const start = ms(r.start)
    const rule = rules.find((x) => x.id === r.ruleId)
    const how = rule?.kind === 'manual' ? manualRepeatWords(rule, locale) : r.series ? (rule && !rule.newOnly ? 'every episode' : 'new episode') : episode(r) || 'once'
    const row: ScheduledRow = {
      recording: r, time: timeOfDay(start, locale), title: r.title,
      sub: [`${r.number} ${r.channelName}`, how, lengthWords(ms(r.end) - start)].join(' · '),
      mark: r.state === 'conflict' ? 'wont' : r.series ? 'ring' : 'dot',
      end: r.state === 'conflict' ? 'Won’t record' : r.state === 'gone' ? 'No longer listed' : undefined,
    }
    const day = scheduledDay(start, now, locale)
    const last = days[days.length - 1]
    if (last?.day === day) last.rows.push(row)
    else days.push({ day, rows: [row] })
  }
  return days
}

/** "1 clash", "2 clashes" beside the Scheduled tab. */
export function clashCount(items: readonly Recording[]): string | undefined {
  const n = items.filter((r) => r.state === 'conflict').length
  return n ? `${n} ${n === 1 ? 'clash' : 'clashes'}` : undefined
}

/**
 * The alert over Scheduled for the first recording that won't record, with
 * what it would take: "Three recordings want tonight at 9:00 PM, and only
 * two tuners are free." It names what records as it stands.
 */
export function clashAlert(r: Recording, now: number, locale?: string): { title: string; line: string; canChoose: boolean } {
  const holders = r.conflictsWith ?? []
  const start = ms(r.start)
  const day = scheduledDay(start, now, locale)
  const when = `${/^(Tonight|Today|Tomorrow)$/.test(day) ? day.toLowerCase() : day} at ${timeOfDay(start, locale)}`
  if (r.reason === 'no_tuners' || !holders.length) {
    return { title: `${r.title} won’t record ${when}.`, line: conflictWords(r.reason), canChoose: false }
  }
  const free = holders.length === 1 ? 'only one tuner is free' : `only ${numberWord(holders.length)} tuners are free`
  const names = holders.map((h) => h.title)
  const recordsNow = names.length === 1 ? names[0] : `${names.slice(0, -1).join(', ')} and ${names[names.length - 1]}`
  return {
    title: `${capital(numberWord(holders.length + 1))} recordings want ${when}, and ${free}.`,
    line: `As it stands ${recordsNow} ${names.length === 1 ? 'records' : 'record'} and ${r.title} doesn’t.`,
    canChoose: canChooseClash(r) && r.canCancel,
  }
}

// ---------------------------------------------------------------- room left

/**
 * How much more fits, in hours of recording, judged from what the
 * recordings so far have taken per hour: "Room for about 70 more hours".
 * Without a recording to judge by, the free space itself.
 */
export function roomWords(freeBytes: number | undefined, recorded: readonly Recording[], format: (n: number) => string): string | undefined {
  if (freeBytes === undefined) return undefined
  let bytes = 0, hours = 0
  for (const r of recorded) {
    if ((r.state !== 'done' && r.state !== 'partial') || !(r.bytes > 0)) continue
    const from = ms(r.startedAt ?? r.start), to = ms(r.endedAt ?? r.end)
    if (!(to > from)) continue
    bytes += r.bytes
    hours += (to - from) / 3_600_000
  }
  if (!bytes || !hours) return `${format(freeBytes)} free`
  const left = freeBytes / (bytes / hours)
  if (left < 1) return 'Room for less than an hour more'
  if (left < 2) return 'Room for about an hour more'
  const round = left < 10 ? Math.floor(left) : left < 100 ? Math.round(left / 5) * 5 : Math.round(left / 10) * 10
  return `Room for about ${round.toLocaleString()} more hours`
}
