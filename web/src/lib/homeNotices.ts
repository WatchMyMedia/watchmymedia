import type { components } from '../api/schema'
import { timeOfDay } from './liveGuide'

type Recording = components['schemas']['LiveTVRecording']
type MediaRequest = components['schemas']['MediaRequest']

/** Where a notice leads. */
export type NoticeTarget =
  | { kind: 'recordings' }
  | { kind: 'inbox' }
  | { kind: 'requests' }
  | { kind: 'movie'; id: number }
  | { kind: 'show'; id: number }

export type HomeNotice = {
  key: string
  icon?: 'rec' | 'clash' | 'arrived'
  /** The words in bold: what it is about. */
  strong: string
  /** The rest of the line. */
  rest: string
  /** A gold word at the end: what to do. */
  action?: string
  to: NoticeTarget
  /** The arrived requests the notice covers, seen once it is followed. */
  seen?: number[]
}

/** How far ahead a clash is worth saying on Home. */
export const CLASH_AHEAD_MS = 24 * 60 * 60_000
const SHOWN_ARRIVALS = 2

/**
 * Home's notices, in the order they matter: what is recording now, what
 * won't record in the next day, requests that arrived, and for admins what
 * waits in the Inbox.
 */
export function homeNotices(input: { recording: Recording[]; clashes: Recording[]; arrived: MediaRequest[]; inbox: number; now: number; locale?: string }): HomeNotice[] {
  const { now, locale } = input
  const out: HomeNotice[] = []
  for (const r of input.recording.filter((r) => r.state === 'recording').slice(0, 2)) {
    out.push({ key: `rec-${r.id}`, icon: 'rec', strong: 'Recording', rest: ` ${r.title} · until ${timeOfDay(Date.parse(r.end), locale)}`, to: { kind: 'recordings' } })
  }
  const soon = input.clashes.filter((r) => {
    const start = Date.parse(r.start)
    return start >= now - 60_000 && start - now <= CLASH_AHEAD_MS
  })
  for (const r of soon.slice(0, 2)) {
    const start = Date.parse(r.start)
    const when = sameDay(start, now) ? (new Date(start).getHours() >= 17 ? 'tonight' : 'today') : 'tomorrow'
    const holders = r.conflictsWith ?? []
    const why = holders.length === 1 ? `${holders[0].title} has the tuner` : 'the tuners are busy'
    out.push({ key: `clash-${r.id}`, icon: 'clash', strong: `Won’t record ${when}`, rest: ` ${r.title} · ${why} at ${timeOfDay(start, locale)} `, action: 'Fix', to: { kind: 'recordings' } })
  }
  const arrived = input.arrived
  if (arrived.length > SHOWN_ARRIVALS) {
    out.push({ key: 'arrived', icon: 'arrived', strong: `${arrived.length} of your requests`, rest: ' are here', to: { kind: 'requests' }, seen: arrived.map((r) => r.id) })
  } else {
    for (const r of arrived) {
      const to: NoticeTarget = r.mediaType === 'movie' && r.movieId ? { kind: 'movie', id: r.movieId } : r.mediaType === 'tv' && r.showId ? { kind: 'show', id: r.showId } : { kind: 'requests' }
      out.push({ key: `arrived-${r.id}`, icon: 'arrived', strong: r.title, rest: ' is here · you asked for it', to, seen: [r.id] })
    }
  }
  if (input.inbox > 0) out.push({ key: 'inbox', strong: `${input.inbox} waiting`, rest: ' in your Inbox', to: { kind: 'inbox' } })
  return out
}

function sameDay(a: number, b: number): boolean {
  const x = new Date(a)
  const y = new Date(b)
  return x.getFullYear() === y.getFullYear() && x.getMonth() === y.getMonth() && x.getDate() === y.getDate()
}
