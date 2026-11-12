import type { components } from '../api/schema'
import { formatBytes, relativeTime } from './format'

/**
 * Activity › Downloads (PLAN-DOWNLOADS.md §8, design/mockups/web/ActivityDownloads):
 * how each download and transfer reads. A download is not a play, so these
 * never touch History's wording.
 */
export type OfflineDownload = components['schemas']['OfflineDownload']
export type OfflineTransfer = components['schemas']['OfflineTransfer']
export type OfflineItem = components['schemas']['OfflineItem']

/** Who may download: the three choices on Settings › Users. */
export const WHO_CAN_DOWNLOAD = [
  ['none', 'No one'],
  ['admins', 'Admins only'],
  ['everyone', 'Everyone with an account'],
] as const

const QUALITY_NAMES: Record<string, string> = { original: 'Original', '1080': '1080p', '720': '720p', '480': '480p' }

/** The quality chip: "Original", or "720p · Transcode", in green for what keeps the original video and orange for a transcode. */
export function qualityChip(quality: string, mode: string): { label: string; transcode: boolean } {
  const name = QUALITY_NAMES[quality] ?? (quality ? `${quality}p` : 'Original')
  return mode === 'transcode' ? { label: `${name} · Transcode`, transcode: true } : { label: name, transcode: false }
}

/** "Harbor Lights" and "S03 E07 · The Long Dark"; a movie is "Movie" and its year. */
export function downloadTitle(it: OfflineItem): { title: string; subtitle: string } {
  if (it.type === 'episode') {
    const se = it.seasonNumber != null && it.episodeNumber != null ? `S${String(it.seasonNumber).padStart(2, '0')} E${String(it.episodeNumber).padStart(2, '0')}` : ''
    return { title: it.showTitle || it.title, subtitle: [se, it.title].filter(Boolean).join(' · ') }
  }
  if (it.type === 'movie') return { title: it.title, subtitle: ['Movie', it.year].filter(Boolean).join(' · ') }
  return { title: it.title, subtitle: 'Not matched' }
}

/** "8.1 GB", or "about 1.7 GB" while the size is the server's estimate. */
export function sizeLabel(bytes: number, estimated: boolean): string {
  return `${estimated ? 'about ' : ''}${formatBytes(bytes)}`
}

/** A transfer's progress line: "5.0 of 8.1 GB" or "0.6 of about 1.7 GB". */
export function progressLine(t: Pick<OfflineTransfer, 'progress' | 'sizeBytes' | 'sizeEstimated'>): string {
  // The part done in the unit of the whole, as the whole is shown.
  const i = t.sizeBytes > 0 ? Math.min(Math.floor(Math.log(t.sizeBytes) / Math.log(1024)), 4) : 0
  const done = (t.sizeBytes * Math.min(Math.max(t.progress, 0), 1)) / 1024 ** i
  return `${i === 0 ? Math.round(done) : done.toFixed(1)} of ${sizeLabel(t.sizeBytes, t.sizeEstimated)}`
}

/** "about 11 min left" at the transfer's rate; "" before there is one or once done. */
export function timeLeft(t: Pick<OfflineTransfer, 'progress' | 'sizeBytes' | 'bandwidth'>): string {
  if (t.bandwidth <= 0 || t.progress >= 1) return ''
  const seconds = ((1 - t.progress) * t.sizeBytes * 8) / t.bandwidth
  if (seconds < 60) return 'less than a minute left'
  const min = Math.round(seconds / 60)
  if (min < 90) return `about ${min} min left`
  return `about ${Math.round(min / 60)} h left`
}

/** What the device has, in the "On the device" column: "Downloading 34%" while a transfer runs. */
export function deviceState(d: Pick<OfflineDownload, 'deviceState'>, transfer?: Pick<OfflineTransfer, 'progress'>): string {
  if (transfer && d.deviceState !== 'downloaded') return `Downloading ${Math.floor(transfer.progress * 100)}%`
  switch (d.deviceState) {
    case 'queued':
      return 'Queued'
    case 'downloading':
      return 'Downloading'
    case 'removed':
      return 'Removed'
  }
  return 'Downloaded'
}

/** The mark under a download's state, and its tone: warn (orange), accent (gold) or muted. */
export type Mark = { text: string; tone: 'warn' | 'accent' | 'muted' }

/**
 * What the server has decided that the device learns at its next sync
 * (PLAN-DOWNLOADS.md §5): revoked, the file gone or replaced, or a
 * download starting over. viewer is the signed-in admin's name, for "Revoked by you".
 */
export function downloadMark(d: OfflineDownload, viewer?: string): Mark | null {
  switch (d.state) {
    case 'ready':
      return null
    case 'gone':
      return { text: 'Removed from the server', tone: 'warn' }
    case 'replaced':
      return { text: 'A new version is available', tone: 'accent' }
    case 'restart':
      return { text: 'Starting over: the server made it again differently', tone: 'muted' }
  }
  const when = d.revokedAt ? `, ${shortDate(d.revokedAt)}` : ''
  if (d.revokedBy === 'access') return { text: 'Can no longer download · removed at its next sync', tone: 'muted' }
  const by = d.revokedBy === 'admin' && d.revokedByName ? ` by ${d.revokedByName === viewer ? 'you' : d.revokedByName}` : ''
  return { text: `Revoked${by}${when} · removed at its next sync`, tone: 'muted' }
}

/** "Oct 28", or "just now" within the minute. */
function shortDate(iso: string): string {
  const d = new Date(iso)
  if (Date.now() - d.getTime() < 60_000) return 'just now'
  return d.toLocaleDateString(undefined, { month: 'short', day: 'numeric' })
}

/** Whether Revoke is offered: anything not revoked already. */
export const canRevoke = (d: Pick<OfflineDownload, 'state'>) => d.state !== 'revoked'

/** "Last sync": now while it is downloading, else how long ago. */
export function lastSync(d: Pick<OfflineDownload, 'syncedAt'>, active: boolean): string {
  if (active) return 'Now'
  const r = relativeTime(d.syncedAt)
  return r === 'just now' ? 'Now' : r.replace(/^(.)/, (c) => c.toUpperCase())
}

/** The summary over every download: "9 downloads on 5 devices · 26.5 GB". */
export function downloadsSummary(items: OfflineDownload[]): string {
  const devices = new Set(items.map((d) => `${d.user?.id ?? 0}/${d.deviceName ?? ''}`)).size
  const bytes = items.reduce((n, d) => n + d.sizeBytes, 0)
  const count = `${items.length} ${items.length === 1 ? 'download' : 'downloads'}`
  return `${count} on ${devices} ${devices === 1 ? 'device' : 'devices'} · ${formatBytes(bytes)} · newest first`
}

/** The transfers now: "2 · 1 original, 1 transcode", and the bandwidth home and away. */
export function transfersSummary(transfers: OfflineTransfer[]): { downloading: string; home: number; away: number } {
  if (transfers.length === 0) return { downloading: 'None', home: 0, away: 0 }
  const tx = transfers.filter((t) => t.mode === 'transcode').length
  const kinds = [transfers.length - tx > 0 ? `${transfers.length - tx} original` : '', tx > 0 ? `${tx} transcode` : ''].filter(Boolean)
  const home = transfers.filter((t) => t.network === 'local').reduce((n, t) => n + t.bandwidth, 0)
  const away = transfers.filter((t) => t.network !== 'local').reduce((n, t) => n + t.bandwidth, 0)
  return { downloading: `${transfers.length} · ${kinds.join(', ')}`, home, away }
}

/** How often the downloads list refetches: often while something downloads. */
export const downloadsPoll = (transfers: number) => (transfers > 0 ? 3_000 : 30_000)
