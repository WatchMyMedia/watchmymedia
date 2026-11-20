import type { components } from '../../../src/api/schema'
import { relativeTime } from '../../../src/lib/format'
import { whatLabel } from '../../../src/lib/requests'
import type { IconName } from '../../ui/Icon'

// Your requests and the Inbox on the TV:
// how each request's state is drawn, and the lines the
// Inbox's rows say.

type MediaRequest = components['schemas']['MediaRequest']
type Problem = components['schemas']['Problem']
type Inbox = components['schemas']['Inbox']

export type StatusLook = { tone: 'plain' | 'bright' | 'muted' | 'accent' | 'warn'; icon: IconName; progress?: number }

/** A request's colour and icon by where it stands, with the download's progress (0–1) while it downloads. */
export function statusLook(r: Pick<MediaRequest, 'status' | 'download'>): StatusLook {
  switch (r.status) {
    case 'pending': return { tone: 'plain', icon: 'clock' }
    case 'declined': return { tone: 'muted', icon: 'alert' }
    case 'failed': return { tone: 'warn', icon: 'alert' }
    case 'available': return { tone: 'bright', icon: 'check' }
  }
  if (r.download?.state === 'downloading') return { tone: 'accent', icon: 'down', progress: r.download.progress }
  return { tone: 'plain', icon: 'check' }
}

/** Under a request's title: "Movie · 2 h ago", "Seasons 1 and 3 · yesterday". */
export function requestSub(r: Pick<MediaRequest, 'mediaType' | 'seasons' | 'createdAt'>): string {
  return `${whatLabel(r)} · ${relativeTime(r.createdAt)}`
}

/** An Inbox request's line, in parts to draw: "Sam asked for Season 2 of Harborwick · 2 h ago". */
export function askedLine(r: Pick<MediaRequest, 'requestedBy' | 'mediaType' | 'seasons' | 'title' | 'createdAt'>): { who: string; what?: string; title: string; ago: string } {
  return { who: r.requestedBy, what: r.mediaType === 'tv' && r.seasons.length ? whatLabel(r) : undefined, title: r.title, ago: relativeTime(r.createdAt) }
}

const KINDS: Record<Problem['kind'], string> = { video: 'a video problem', audio: 'an audio problem', subtitles: 'a subtitle problem', other: 'a problem' }

/** A problem's line, in parts: "Sam reported a video problem on Harbor Lights · S3 E2 · yesterday". */
export function reportedLine(p: Pick<Problem, 'reportedBy' | 'kind' | 'title' | 'createdAt'>): { who: string; what: string; title: string; ago: string } {
  return { who: p.reportedBy, what: KINDS[p.kind] ?? 'a problem', title: p.title, ago: relativeTime(p.createdAt) }
}

/** An admin can approve a request waiting for it, and send a failed one again. */
export function decidable(r: Pick<MediaRequest, 'status'>): boolean {
  return r.status === 'pending' || r.status === 'failed'
}

/** The Inbox's line under its title: "2 requests · 1 problem". */
export function inboxCounts(inbox: Pick<Inbox, 'requests' | 'problems'>): string {
  const n = (count: number, word: string) => `${count} ${word}${count === 1 ? '' : 's'}`
  return `${n(inbox.requests.length, 'request')} · ${n(inbox.problems.length, 'problem')}`
}

/** Your requests' line under its title: "6 requests · newest first". */
export function mineCount(count: number): string {
  return `${count} ${count === 1 ? 'request' : 'requests'} · newest first`
}

/** Seerr's page for a QR code, only when it is a web address. */
export function seerrUrl(url: string | undefined): string | undefined {
  if (!url) return undefined
  try {
    const u = new URL(url)
    return u.protocol === 'https:' || u.protocol === 'http:' ? u.href : undefined
  } catch {
    return undefined
  }
}
