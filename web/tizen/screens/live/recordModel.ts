import type { LiveChannel, LiveProgramme } from '../../../src/api/liveTV'
import type { CreateRecording, Recording, RecordingRule, UpdateRule } from '../../../src/api/recordings'
import { duration, episodeLine, isOn, timeOfDay, timeRange } from '../../../src/lib/liveGuide'
import { PADDING_MINUTES, clashHeading, conflictWords, markLook } from '../../../src/lib/liveRecordings'

// The record side panel: the choice for a
// programme, what is set already, the options and the clash. Words and
// rules only; RecordPanel.tsx draws them and sends the requests.

const ms = (iso: string) => Date.parse(iso)
const weekday = (at: number, locale?: string) => new Intl.DateTimeFormat(locale, { weekday: 'short' }).format(at)
const localDay = (at: number) => { const d = new Date(at); return new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime() }

/** "Today at 9:00 PM", "Tomorrow at 8:00 PM", "Wed at 9:00 PM". */
export function atWords(start: number, now: number, locale?: string): string {
  const days = Math.round((localDay(start) - localDay(now)) / 86_400_000)
  return `${days <= 0 ? 'Today' : days === 1 ? 'Tomorrow' : weekday(start, locale)} at ${timeOfDay(start, locale)}`
}

export type RecordOptions = {
  newOnly: boolean
  anyChannel: boolean
  /** 0 keeps every recording; otherwise the latest this many. */
  keep: number
  /** Minutes; undefined uses the server's padding. */
  padBefore?: number
  padAfter?: number
}

export const DEFAULT_OPTIONS: RecordOptions = { newOnly: true, anyChannel: false, keep: 0 }

/** A rule's options, to edit them. */
export function ruleOptions(rule: RecordingRule): RecordOptions {
  return { newOnly: rule.newOnly, anyChannel: rule.anyChannel, keep: rule.keep, padBefore: rule.padBefore, padAfter: rule.padAfter }
}

/** POST /api/live-tv/recordings for a programme. */
export function createBody(p: LiveProgramme, scope: 'once' | 'series', o: RecordOptions): CreateRecording {
  return {
    programmeId: p.id, scope,
    ...(scope === 'series' ? { newOnly: o.newOnly, anyChannel: o.anyChannel, keep: o.keep } : {}),
    ...(o.padBefore !== undefined ? { padBefore: o.padBefore } : {}),
    ...(o.padAfter !== undefined ? { padAfter: o.padAfter } : {}),
  }
}

/** PATCH /api/live-tv/recording-rules/{id}; a manual rule has no padding to change. */
export function ruleBody(rule: Pick<RecordingRule, 'kind'>, o: RecordOptions): UpdateRule {
  const base = { newOnly: o.newOnly, anyChannel: o.anyChannel, keep: o.keep }
  return rule.kind === 'manual' ? base : { ...base, ...(o.padBefore !== undefined ? { padBefore: o.padBefore } : {}), ...(o.padAfter !== undefined ? { padAfter: o.padAfter } : {}) }
}

/** ◀ ▶ on Start or End: the next padding, through the server's default. */
export function stepPadding(value: number | undefined, direction: 1 | -1): number | undefined {
  const steps: (number | undefined)[] = [undefined, ...PADDING_MINUTES]
  const i = Math.max(0, steps.indexOf(value))
  return steps[Math.min(steps.length - 1, Math.max(0, i + direction))]
}

/** ◀ ▶ on Keep: all, then the latest 1 to 10. */
export function stepKeep(keep: number, direction: 1 | -1): number {
  return Math.min(10, Math.max(0, keep + direction))
}

export const paddingWords = (minutes: number | undefined, edge: 'early' | 'late') => minutes === undefined ? 'Server default' : `${minutes} min ${edge}`
export const keepWords = (keep: number) => keep ? `Latest ${keep}` : 'All'

/** Under the options: "New episodes on 4.2, all kept. An episode already recorded or in your library is skipped." */
export function optionsSummary(o: RecordOptions, number: string, series: boolean): string {
  const pads = o.padBefore === undefined && o.padAfter === undefined ? 'Starts and ends with the server’s padding.'
    : `Starts ${paddingWords(o.padBefore, 'early').toLowerCase()} and ends ${paddingWords(o.padAfter, 'late').toLowerCase()}.`
  if (!series) return pads
  return `${o.newOnly ? 'New episodes' : 'Every episode'}${o.anyChannel ? ' on any channel' : ` on ${number}`}${o.keep ? `, the latest ${o.keep} kept` : ', all kept'}. An episode already recorded or in your library is skipped.`
}

export type ChoiceId = 'once' | 'series' | 'all' | 'options' | 'cancel' | 'stop' | 'watch' | 'editSeries' | 'deleteSeries' | 'seriesFromOnce' | `instead:${string}`
export type Choice = { id: ChoiceId; label: string; detail?: string; more?: boolean }

export type PanelView = {
  /** "Record", "Will record", "Recording now", "Won’t record". */
  eyebrow: string
  tone: 'rec' | 'warn'
  title: string
  sub: string
  /** The clash's holders: "Inspector Vane on 4.2 · set by Robin". */
  lines?: string[]
  choices: Choice[]
  foot: string
}

function when(p: Pick<LiveProgramme, 'start' | 'end'>, locale?: string) {
  return `${weekday(ms(p.start), locale)} ${timeRange(ms(p.start), ms(p.end), '–', locale)}`
}

/** The choice for a programme with nothing set: this one, every new one, every one, and the options. */
export function recordChoice(p: LiveProgramme, channel: Pick<LiveChannel, 'number' | 'name'>, now: number, o: RecordOptions, locale?: string): PanelView {
  const series = !!p.seriesKey
  const airing = isOn(p, now)
  const ep = episodeLine(p)
  const at = airing ? 'On now' : atWords(ms(p.start), now, locale)
  const pads = o.padBefore === undefined && o.padAfter === undefined ? 'It starts and ends with the server’s padding unless you change it in Options.' : optionsSummary(o, channel.number, false)
  const choices: Choice[] = series ? [
    { id: 'once', label: airing ? 'Record the rest of this episode' : 'Just this episode', detail: [at, ep].filter(Boolean).join(' · ') },
    { id: 'series', label: 'Every new episode', detail: `New episodes on ${o.anyChannel ? 'any channel' : channel.number}` },
    { id: 'all', label: 'Every episode', detail: `New episodes and repeats on ${o.anyChannel ? 'any channel' : channel.number}` },
    { id: 'options', label: 'Options', detail: 'Any channel, keep, start and end', more: true },
  ] : [
    { id: 'once', label: airing ? 'Record the rest' : 'Record this programme', detail: [at, ep].filter(Boolean).join(' · ') },
    { id: 'options', label: 'Options', detail: 'Start and end', more: true },
  ]
  return {
    eyebrow: 'Record', tone: 'rec', title: p.title, sub: `${when(p, locale)} · ${channel.number} ${channel.name}`, choices,
    foot: `A recording has priority over someone watching live. ${pads} Return closes this panel.`,
  }
}

/** A programme already set to record, recording or won't record: what can change. `recording` is the full recording, once loaded. */
export function recordSet(p: LiveProgramme, channel: Pick<LiveChannel, 'number' | 'name'>, recording: Recording | undefined, rule: RecordingRule | undefined, now: number, locale?: string): PanelView {
  const mark = p.recording
  const look = markLook(mark)
  const sub = `${when(p, locale)} · ${channel.number} ${channel.name}`
  const by = recording?.userName ? ` · set by ${recording.userName}` : ''
  const canChange = recording?.canCancel ?? mark?.canCancel ?? false
  if (look === 'rec') return {
    eyebrow: 'Recording now', tone: 'rec', title: p.title, sub: `${duration(now - ms(recording?.startedAt ?? p.start))} so far · ends ${timeOfDay(ms(p.end), locale)}${by}`,
    choices: [
      { id: 'watch', label: 'Watch from the start', detail: 'Plays what has recorded so far. Needs no tuner.' },
      ...(canChange ? [{ id: 'stop' as const, label: 'Stop recording', detail: 'Keeps what has recorded' }] : []),
      ...(canChange && rule ? [{ id: 'editSeries' as const, label: 'Edit series', detail: seriesWords(rule), more: true }] : []),
    ],
    foot: `${p.title} records on its own tuner until ${timeOfDay(ms(p.end), locale)}. Return closes this panel.`,
  }
  if (look === 'hollow') {
    const holders = recording?.conflictsWith ?? []
    const choosable = canChange && recording?.reason !== 'no_tuners'
    return {
      eyebrow: 'Won’t record', tone: 'warn', title: p.title, sub: `${sub}${by}`,
      lines: holders.map((h) => `${h.title} on ${h.number} · set by ${h.userName}${h.running ? ' · recording now' : ''}`),
      choices: [
        ...(choosable ? holders.filter((h) => !h.running).map((h) => ({ id: `instead:${h.id}` as const, label: `Record this instead of ${h.title}`, detail: `${h.title} won’t record` })) : []),
        ...(canChange ? [{ id: 'cancel' as const, label: 'Don’t record', detail: holders.length ? `Leaves ${holders.length === 1 ? 'the one' : `the ${holders.length}`} that record` : 'Nothing is recorded' }] : []),
      ],
      foot: `${holders.length && recording ? clashHeading(holders, recording.start, (at) => timeOfDay(at, locale)) : conflictWords(mark?.reason)}. Return closes this panel.`,
    }
  }
  const series = look === 'ring'
  return {
    eyebrow: 'Will record', tone: 'rec', title: p.title, sub: series ? `${rule ? seriesWords(rule) : 'Every new episode'}${by}` : `Just this episode${by}`,
    choices: series ? [
      ...(canChange && rule ? [{ id: 'editSeries' as const, label: 'Edit series', detail: 'Episodes, channel, keep, start and end', more: true }] : []),
      ...(canChange ? [{ id: 'cancel' as const, label: 'Don’t record this one', detail: 'The series still records the next new one' }] : []),
      ...(canChange && rule ? [{ id: 'deleteSeries' as const, label: 'Delete series', detail: 'Stops recording it. What it recorded stays.' }] : []),
    ] : [
      ...(p.seriesKey ? [{ id: 'seriesFromOnce' as const, label: 'Record every new episode', detail: `New episodes of ${p.title} on ${channel.number}` }] : []),
      ...(canChange ? [{ id: 'cancel' as const, label: 'Don’t record', detail: 'Nothing is recorded' }] : []),
    ],
    foot: canChange ? 'Return closes this panel.' : 'Whoever set it, or an admin, can change it. Return closes this panel.',
  }
}

/** A series rule in a line: "Every new episode on 4.2 · keep all". */
export function seriesWords(rule: Pick<RecordingRule, 'newOnly' | 'anyChannel' | 'keep' | 'number'>): string {
  return `${rule.newOnly ? 'Every new episode' : 'Every episode'} on ${rule.anyChannel ? 'any channel' : rule.number ?? 'this channel'} · ${rule.keep ? `keep the latest ${rule.keep}` : 'keep all'}`
}

/** What the guide says once a recording is set: "Will record Inspector Vane tonight at 9:00 PM". */
export function recordedNote(r: Pick<Recording, 'title' | 'state' | 'start'>, scope: 'once' | 'series', newOnly: boolean, now: number, locale?: string): string {
  if (r.state === 'recording') return `Recording ${r.title}`
  if (scope === 'series') return `Will record ${newOnly ? 'every new episode' : 'every episode'} of ${r.title}`
  const at = atWords(ms(r.start), now, locale)
  return `Will record ${r.title} ${at.replace(/^(Today|Tomorrow)/, (w) => w.toLowerCase())}`
}
