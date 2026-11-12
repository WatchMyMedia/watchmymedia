import type { components } from '../api/schema'

type Episode = components['schemas']['Episode']
type Season = components['schemas']['Season']
type RequestState = components['schemas']['RequestState']
type MediaRequest = components['schemas']['MediaRequest']
type QueueDownload = components['schemas']['QueueDownload']

export type Tone = 'accent' | 'plain' | 'warn' | 'muted'

/** "12 min left", "1 h 20 min left"; "" without a time. */
export function timeLeft(finishesAt: string | undefined, now = Date.now()): string {
  if (!finishesAt) return ''
  const min = Math.max(1, Math.round((new Date(finishesAt).getTime() - now) / 60_000))
  if (min < 60) return `${min} min left`
  const h = Math.floor(min / 60)
  const m = min % 60
  return m ? `${h} h ${m} min left` : `${h} h left`
}

/** "Thu 4 Nov", the way missing episodes and the apps say when one airs. */
export function airsOn(date: string): string {
  return new Date(`${date}T12:00:00`).toLocaleDateString('en-GB', { weekday: 'short', day: 'numeric', month: 'short' })
}

/** A download's line: "Downloading 45% · 12 min left", "Waiting to import", "Download failed". */
export function downloadLabel(d: QueueDownload, now = Date.now()): { label: string; tone: Tone } {
  switch (d.state) {
    case 'failed':
      return { label: 'Download failed', tone: 'warn' }
    case 'importing':
      return { label: 'Waiting to import', tone: 'plain' }
    case 'queued':
      return { label: 'Waiting to download', tone: 'plain' }
  }
  const left = timeLeft(d.finishesAt, now)
  return { label: `Downloading ${Math.round(d.progress * 100)}%${left ? ` · ${left}` : ''}`, tone: 'accent' }
}

export type MissingStatus = { label: string; tone: Tone; progress?: number; thumb: 'Missing' | 'Not aired yet' }

/** Why an episode without a file is missing, most telling first: its download, a request, its air date, Sonarr's monitoring. */
export function missingStatus(e: Pick<Episode, 'aired' | 'airDate' | 'download' | 'request' | 'monitored'>, now = Date.now()): MissingStatus {
  const thumb = e.aired ? 'Missing' : 'Not aired yet'
  if (e.download) {
    const d = downloadLabel(e.download, now)
    return { ...d, thumb, progress: e.download.state === 'downloading' ? e.download.progress : undefined }
  }
  const r = e.request
  if (r && (r.status === 'pending' || r.status === 'approved') && e.aired) {
    return r.status === 'pending'
      ? { label: `Requested by ${r.mine ? 'you' : r.requestedBy} · Waiting for approval`, tone: 'plain', thumb }
      : { label: 'Approved · Sonarr is searching', tone: 'accent', thumb }
  }
  if (!e.aired) return { label: e.airDate ? `Airs ${airsOn(e.airDate)}` : 'Not aired yet', tone: 'muted', thumb }
  if (e.monitored === true) return { label: 'Missing · Sonarr is looking for it', tone: 'muted', thumb }
  if (e.monitored === false) return { label: 'Not monitored', tone: 'muted', thumb }
  return { label: 'Not in library', tone: 'muted', thumb }
}

/** "8 of 10 episodes · 2 missing", or "10 episodes" when none is. */
export function seasonSummary(s: Pick<Season, 'episodeCount' | 'availableCount'>): string {
  const missing = Math.max(0, s.episodeCount - s.availableCount)
  if (missing === 0) return `${s.episodeCount} ${s.episodeCount === 1 ? 'episode' : 'episodes'}`
  return `${s.availableCount} of ${s.episodeCount} episodes · ${missing} missing`
}

/** A season tab's count: "8 of 10" when part is missing, "10" when whole, "" when none is here. */
export function seasonTabCount(s: Pick<Season, 'episodeCount' | 'availableCount'>): string {
  if (s.availableCount === 0) return ''
  return s.availableCount < s.episodeCount ? `${s.availableCount} of ${s.episodeCount}` : String(s.availableCount)
}

/** An open request: still waiting on someone or on the download. */
export function isOpen(r: RequestState | undefined): boolean {
  return !!r && (r.status === 'pending' || r.status === 'approved')
}

/** Whether a season can be asked for: some of it aired and is missing, and no one asked already. */
export function canRequestSeason(s: Pick<Season, 'episodeCount' | 'availableCount' | 'airDate' | 'request'>, today: string): boolean {
  return s.availableCount < s.episodeCount && !!s.airDate && s.airDate <= today && !isOpen(s.request)
}

/** A request's state in Your requests and the Inbox. */
export function requestStateLabel(r: Pick<MediaRequest, 'status' | 'note' | 'download'>): { label: string; tone: Tone } {
  switch (r.status) {
    case 'pending':
      return { label: 'Waiting for approval', tone: 'plain' }
    case 'declined':
      return { label: r.note ? `Declined · “${r.note}”` : 'Declined', tone: 'warn' }
    case 'failed':
      return { label: r.note ? `Failed · ${r.note}` : 'Failed', tone: 'warn' }
    case 'available':
      return { label: 'In your library', tone: 'accent' }
  }
  if (r.download && r.download.state === 'downloading') return { label: `Downloading ${Math.round(r.download.progress * 100)}%`, tone: 'accent' }
  return { label: 'Approved', tone: 'plain' }
}

/**
 * Whether the person can withdraw their request: while it waits, and for Radarr and Sonarr also after it failed.
 * Seerr only lets people withdraw their own while it is pending.
 */
export function canCancel(r: Pick<MediaRequest, 'mine' | 'status'>, backend: string | undefined): boolean {
  if (!r.mine) return false
  return r.status === 'pending' || (r.status === 'failed' && backend !== 'seerr')
}

/** Where search's Not in your library comes from. */
export function requestSource(backend: string | undefined): string {
  return backend === 'seerr' ? 'Seerr' : 'TMDB'
}

/** "Season 2", "Seasons 1 and 3", "Seasons 1–4", or "Movie". */
export function whatLabel(r: Pick<MediaRequest, 'mediaType' | 'seasons'>): string {
  if (r.mediaType === 'movie') return 'Movie'
  const s = [...r.seasons].sort((a, b) => a - b)
  if (s.length === 1) return s[0] === 0 ? 'Specials' : `Season ${s[0]}`
  const run = s.every((n, i) => i === 0 || n === s[i - 1] + 1)
  if (run && s.length > 2) return `Seasons ${s[0]}–${s[s.length - 1]}`
  return `Seasons ${s.slice(0, -1).join(', ')} and ${s[s.length - 1]}`
}

/** The request sheet's button: "Request movie", "Request season 3", "Request 2 seasons". */
export function requestButtonLabel(mediaType: 'movie' | 'tv', seasons: number[]): string {
  if (mediaType === 'movie') return 'Request movie'
  if (seasons.length === 1) return seasons[0] === 0 ? 'Request specials' : `Request season ${seasons[0]}`
  return `Request ${seasons.length} seasons`
}

/** The text colour for a tone. */
export const toneText: Record<Tone, string> = {
  accent: 'text-accent',
  plain: 'text-neutral-300',
  warn: 'text-warn',
  muted: 'text-neutral-400',
}

/** The dot colour for a tone. */
export const toneDot: Record<Tone, string> = {
  accent: 'bg-accent',
  plain: 'bg-neutral-300',
  warn: 'bg-warn',
  muted: 'bg-neutral-500',
}
