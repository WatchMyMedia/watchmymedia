/** DVR presentation rules shared by the guide, lists and both players. */
export const RECORDING_API_LEVEL = 19
export const RECORDING_SECTIONS = ['now', 'conflicts', 'upcoming', 'series', 'recorded'] as const
export type RecordingSection = typeof RECORDING_SECTIONS[number]
export const RECORDING_HEADINGS: Record<RecordingSection, string> = { now: 'Recording now', conflicts: 'Won’t record', upcoming: 'Upcoming', series: 'Series and repeating', recorded: 'Recorded' }
export const PADDING_MINUTES = [0, 1, 2, 5, 15, 30, 60]

export function canRecord(level?: number, caps?: { recording?: boolean; canRecord?: boolean }): boolean {
  return (level ?? 0) >= RECORDING_API_LEVEL && !!caps?.recording && !!caps.canRecord
}
/** The server records (whoever may set recordings): only then can a recording take a tuner someone is watching on. */
export function serverRecords(level?: number, caps?: { recording?: boolean }): boolean {
  return (level ?? 0) >= RECORDING_API_LEVEL && !!caps?.recording
}

// The words every client uses for a reason code (Android and Apple say the
// same). A code this version doesn't know never shows as it is.
const REASONS: Record<string, string> = {
  already_recorded: 'Already recorded',
  already_scheduled: 'Already set to record',
  in_library: 'Already in your library',
  not_new: 'Not a new episode',
  rule_filtered: 'Left out by the series options',
  listing_gone: 'No longer in the listings',
  tuners_busy: 'Won’t record: the tuners are busy',
  no_tuners: 'Won’t record: no tuner is free for recordings',
  missed: 'Missed: the server wasn’t running',
  disk_full: 'Stopped: the disk is nearly full',
  folder_unavailable: 'The recordings folder isn’t available',
  output_changed: 'Something else changed the recording’s file',
  output_missing: 'The recording’s file is missing',
  server_restarted: 'The server restarted during it',
  signal_lost: 'The signal dropped',
  stopped: 'Stopped early',
  stop_requested: 'Stopping…',
  finish_pending: 'Finishing…',
  started_late: 'Started late',
}
// What already happened: a part lost to busy tuners did record, the rest of it.
const PAST: Record<string, string> = { tuners_busy: 'The tuners were busy', disk_full: 'Not enough free space' }

/** The shared words for a reason code; `past` for a failed or part recording, which says what happened. */
export function reasonWords(reason?: string, past = false): string | undefined {
  if (!reason) return undefined
  return (past ? PAST[reason] : undefined) ?? REASONS[reason]
}

/** "A", "A and B", "A, B and C". */
export function joinNames(names: string[]): string {
  return names.length < 2 ? names[0] ?? '' : `${names.slice(0, -1).join(', ')} and ${names.at(-1)}`
}

/** Why a recording in state conflict won't record, naming what holds the tuners when the server says. */
export function conflictWords(reason?: string, holders?: { title: string }[]): string {
  if (reason === 'no_tuners') return REASONS.no_tuners
  if (holders?.length) return `Won’t record: ${joinNames(holders.map((h) => h.title))} ${holders.length === 1 ? 'has the tuner' : 'have the tuners'}`
  return REASONS.tuners_busy
}

/** A recording's state in words, for its row and the programme's details. */
export function recordingWords(state: string, reason?: string, holders?: { title: string }[]): string {
  switch (state) {
    case 'recording': return reason === 'stop_requested' ? REASONS.stop_requested : 'Recording now'
    case 'scheduled': return 'Will record'
    case 'conflict': return conflictWords(reason, holders)
    case 'gone': return REASONS.listing_gone
    case 'done': return reason === 'finish_pending' ? REASONS.finish_pending : 'Recorded'
    case 'partial': {
      if (reason === 'finish_pending') return REASONS.finish_pending
      const why = reasonWords(reason, true)
      return why ? `Part recorded · ${why}` : 'Part recorded'
    }
    case 'failed': return reasonWords(reason, true) ?? 'Didn’t record'
    case 'cancelled': return 'Cancelled'
    case 'deleted': return 'Deleted'
    case 'skipped': return reasonWords(reason) ?? 'Won’t record'
    default: return reasonWords(reason) ?? ''
  }
}

const lower = (text: string) => text.charAt(0).toLowerCase() + text.slice(1)

/** "Missing 9:12–9:14 PM: the signal dropped". */
export function gapWords(gap: { start: string; end: string; reason?: string }, range: (start: number, end: number) => string): string {
  const when = range(Date.parse(gap.start), Date.parse(gap.end))
  const why = reasonWords(gap.reason, true)
  return why ? `Missing ${when}: ${lower(why)}` : `Missing part: ${when}`
}

/** How the guide marks a programme: only one that will record, is recording, or won't record for a clash. */
export type MarkLook = 'dot' | 'ring' | 'rec' | 'hollow'
export function markLook(mark?: { state: string; series?: boolean }): MarkLook | undefined {
  switch (mark?.state) {
    case 'scheduled': return mark.series ? 'ring' : 'dot'
    case 'recording': return 'rec'
    case 'conflict': return 'hollow'
    default: return undefined
  }
}
/** A mark that stands for a recording still to come or under way; any other leaves Record on offer. */
export function markHoldsRecord(mark?: { state: string }): boolean {
  return markLook(mark) !== undefined
}
/** The details' words for a programme's mark. */
export function recordingMarkWords(mark?: { state: string; series?: boolean; reason?: string }): string {
  if (!mark) return ''
  return `${recordingWords(mark.state, mark.reason)}${mark.state === 'scheduled' && mark.series ? ' · Series' : ''}`
}
/** What a guide cell's accessible name adds for its mark: ", will record", ", recording", ", won't record: …". */
export function recordingMarkLabel(mark?: { state: string; series?: boolean; reason?: string }): string {
  switch (markLook(mark)) {
    case 'dot': return ', will record'
    case 'ring': return ', will record, series'
    case 'rec': return ', recording'
    case 'hollow': return `, ${lower(conflictWords(mark?.reason))}`
    default: return ''
  }
}

/** The recording's planned length, from the server's two times (so this device's clock doesn't matter). */
export function plannedRecordingMs(growing?: { startedAt?: string; endsAt?: string }): number | undefined {
  const start = Date.parse(growing?.startedAt ?? '')
  const end = Date.parse(growing?.endsAt ?? '')
  return Number.isFinite(start) && Number.isFinite(end) && end > start ? end - start : undefined
}
/**
 * How much of a growing recording is written: the decision's length, plus the
 * time since it arrived on this device's monotonic clock, up to the planned
 * end. Once the stream has ended (`endedMs`) it stays at that.
 */
export function growingDurationMs(growing: { startedAt?: string; endsAt?: string } | undefined, durationMs: number, sinceMs: number, endedMs?: number): number {
  const known = Math.max(0, durationMs)
  if (!growing) return known
  if (endedMs !== undefined && Number.isFinite(endedMs)) return Math.max(0, endedMs)
  const grown = known + Math.max(0, sinceMs)
  const planned = plannedRecordingMs(growing)
  return planned === undefined ? grown : Math.min(grown, Math.max(planned, known))
}
/** Still recording: until the planned end passes or the stream ends early. */
export function stillRecording(growing: { startedAt?: string; endsAt?: string } | undefined, durationMs: number, sinceMs: number, endedMs?: number): boolean {
  if (!growing || endedMs !== undefined) return false
  const planned = plannedRecordingMs(growing)
  return planned === undefined || Math.max(0, durationMs) + Math.max(0, sinceMs) < planned
}
/** Go to now shows only when the viewer is more than this far behind what is written. */
export const GO_TO_NOW_BEHIND_MS = 10_000
export function showGoToNow(recording: boolean, positionMs: number, availableMs: number): boolean {
  return recording && availableMs - positionMs > GO_TO_NOW_BEHIND_MS
}
export function growingSeekMs(targetMs: number, availableMs: number): number {
  // Stay one complete HLS segment behind the writer, where there is one.
  return Math.max(0, Math.min(Number.isFinite(targetMs) ? targetMs : 0, Math.max(0, availableMs - 4000)))
}

/** A recording that is just starting answers 409 for a few seconds: how long to wait before asking again, or undefined to stop and show the error. */
export const START_RETRIES = 8
export function startRetryMs(attempt: number, retryAfter?: string | null, now = Date.now()): number | undefined {
  if (attempt >= START_RETRIES) return undefined
  let wait = Math.min(1000 * 2 ** attempt, 5000)
  const header = retryAfter?.trim()
  if (header) {
    const seconds = /^\d+$/.test(header) ? Number(header) * 1000 : Date.parse(header) - now
    if (Number.isFinite(seconds)) wait = seconds
  }
  return Math.min(Math.max(wait, 500), 15_000)
}

export function recordingProgress(start: string, end: string, now: number): number {
  const a = Date.parse(start), b = Date.parse(end)
  return b > a ? Math.max(0, Math.min(100, (now - a) / (b - a) * 100)) : 0
}
export function takeoverWords(at: string, title: string, now: number): string {
  const remaining = Math.max(0, Math.ceil((Date.parse(at) - now) / 60_000))
  return `${remaining ? `In ${remaining} min` : 'Now'}, this tuner is needed to record ${title}.`
}

/** The clash dialog's heading: when the tuners the new recording needs are all recording. */
export function clashHeading(holders: { start: string }[], start: string, time: (at: number) => string): string {
  const at = time(Math.max(Date.parse(start), ...holders.map((h) => Date.parse(h.start)).filter(Number.isFinite)))
  if (holders.length === 1) return `The tuner is recording at ${at}`
  if (holders.length === 2) return `Both tuners are recording at ${at}`
  return `All ${holders.length} tuners are recording at ${at}`
}
/** The holder chosen first: the first one that can still give way. */
export function firstGivesWay(holders: { id: string; running: boolean }[]): string | undefined {
  return holders.find((h) => !h.running)?.id
}

/** A recording that won't record has a choice when one of the recordings holding the tuners can still give way. */
export function canChooseClash(r: { state: string; reason?: string; conflictsWith?: { running: boolean }[] }): boolean {
  return r.state === 'conflict' && r.reason !== 'no_tuners' && (r.conflictsWith ?? []).some((h) => !h.running)
}

/** A repeating manual recording: "Every day at 6:00 AM", "Weekdays at 6:00 AM", "Every Tuesday at 6:00 AM". */
export function manualRepeatWords(rule: { repeat?: string; start?: string; zone?: string }, locale?: string, here?: string): string {
  const start = Date.parse(rule.start ?? '')
  const format = (options: Intl.DateTimeFormatOptions) => {
    try { return new Intl.DateTimeFormat(locale, { ...options, timeZone: rule.zone || undefined }).format(start) } catch { return new Intl.DateTimeFormat(locale, options).format(start) }
  }
  const at = Number.isFinite(start) ? ` at ${format({ hour: 'numeric', minute: '2-digit' }).replace(/\u202f/g, ' ')}` : ''
  const zone = at && rule.zone && rule.zone !== (here ?? Intl.DateTimeFormat().resolvedOptions().timeZone) ? ` (${rule.zone.replaceAll('_', ' ')})` : ''
  switch (rule.repeat) {
    case 'daily': return `Every day${at}${zone}`
    case 'weekdays': return `Weekdays${at}${zone}`
    case 'weekly': return Number.isFinite(start) ? `Every ${format({ weekday: 'long' })}${at}${zone}` : 'Every week'
    default: return at ? `Once${at}${zone}` : 'Once'
  }
}

export function validateManual(start: string, end: string, name: string, now: number): string | undefined {
  const a = Date.parse(start), b = Date.parse(end)
  if (!name.trim()) return 'Give this recording a name.'
  if (!Number.isFinite(a) || !Number.isFinite(b)) return 'Choose a start and end time.'
  if (b <= a) return 'The end must be after the start.'
  if (b <= now) return 'Choose a time that has not ended.'
  if (b - a > 12 * 60 * 60_000) return 'A recording can last up to 12 hours.'
}
export function localDateTime(date: Date): string {
  const p = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${p(date.getMonth() + 1)}-${p(date.getDate())}T${p(date.getHours())}:${p(date.getMinutes())}`
}
