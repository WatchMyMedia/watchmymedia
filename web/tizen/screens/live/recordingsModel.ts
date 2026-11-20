import type { Recording, RecordingRule } from '../../../src/api/recordings'
import { duration, timeOfDay, timeRange } from '../../../src/lib/liveGuide'
import {
  RECORDING_HEADINGS, canChooseClash, gapWords, manualRepeatWords, recordingProgress, recordingWords, type RecordingSection,
} from '../../../src/lib/liveRecordings'
import type { PlayRequest } from '../../app/routes'
import type { MenuItem } from './guideModel'
import { seriesWords } from './recordModel'

// Recordings (LiveRecordings on the canvas): the focused card's details
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
