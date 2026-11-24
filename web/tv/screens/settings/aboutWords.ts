import { timeOfDay } from '../../../src/lib/liveGuide'
import type { PlaybackErrorRecord } from '../../app/playbackErrors'

// Settings › About's recent playback errors:
// a short code to read out, what happened in our own words, and
// when. Built from the facts app/playbackErrors.ts keeps, never from what a
// player or the server said.

/** "PB-503" for a server's refusal, else by kind: PB-DEC (decoding), PB-NET, PB-AUTH, PB-ERR. */
export function playbackErrorCode(e: Pick<PlaybackErrorRecord, 'kind' | 'status'>): string {
  if (e.status) return `PB-${e.status}`
  return { format: 'PB-DEC', network: 'PB-NET', auth: 'PB-AUTH', other: 'PB-ERR' }[e.kind]
}

function subject(e: Pick<PlaybackErrorRecord, 'media' | 'channel'>): string {
  switch (e.media) {
    case 'movie': return 'a movie'
    case 'episode': return 'an episode'
    case 'recording': return 'a recording'
    case 'trailer': return 'a trailer'
    case 'live': return e.channel ? `Live TV ${e.channel}` : 'Live TV'
  }
}

const capital = (s: string) => s[0].toUpperCase() + s.slice(1)

/** What happened: "The server was too busy to start Live TV 7.1", "This TV couldn’t decode a movie’s video". */
export function playbackErrorWords(e: Pick<PlaybackErrorRecord, 'media' | 'kind' | 'status' | 'channel'>): string {
  const what = subject(e)
  const s = e.status
  if (s === 503 || s === 429) return `The server was too busy to start ${what}`
  if (s === 404) return `${capital(what)} wasn’t on the server any more`
  if (s === 401 || s === 403 || (!s && e.kind === 'auth')) return `The server stopped letting this TV play ${what}`
  if (s && s >= 500) return `The server had a problem playing ${what}`
  if (e.kind === 'format') return e.media === 'live' ? `This TV couldn’t decode the picture on ${what}` : `This TV couldn’t decode ${what}’s video`
  if (e.kind === 'network') return `${capital(what)} stopped loading`
  return `${capital(what)} stopped with an error`
}

const DAY = 86_400_000
const dayStart = (at: number) => { const d = new Date(at); return new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime() }

/** When: "Today 8:02 PM", "Yesterday 9:40 PM", "Mon 7:15 PM" within the week, else "Nov 2 7:15 PM". */
export function playbackErrorWhen(at: number, now: number, locale?: string): string {
  const days = Math.round((dayStart(now) - dayStart(at)) / DAY)
  const day = days <= 0 ? 'Today' : days === 1 ? 'Yesterday'
    : days < 7 ? new Intl.DateTimeFormat(locale, { weekday: 'short' }).format(at)
      : new Intl.DateTimeFormat(locale, { month: 'short', day: 'numeric' }).format(at)
  return `${day} ${timeOfDay(at, locale)}`
}
