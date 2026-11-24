import type { components } from '../api/schema'
import { relativeTime } from './format'

type Library = components['schemas']['Library']

/** One part of the Files page's summary: words, and whether it needs attention (orange). */
export type SummaryPart = { text: string; warn?: boolean }

/**
 * The Files page's summary in words: "236 files · 233 matched · 3 need a
 * match · none missing · scanned 12 min ago". Files waiting for their first
 * match count as needing one.
 */
export function filesSummary(library: Pick<Library, 'fileCount' | 'unmatchedCount' | 'pendingCount' | 'missingCount' | 'lastScannedAt'>): SummaryPart[] {
  const n = (x: number) => x.toLocaleString()
  const need = library.unmatchedCount + library.pendingCount
  const matched = Math.max(0, library.fileCount - need)
  const parts: SummaryPart[] = [{ text: `${n(library.fileCount)} ${library.fileCount === 1 ? 'file' : 'files'}` }]
  if (library.fileCount > 0) parts.push({ text: need === 0 ? 'all matched' : `${n(matched)} matched` })
  if (need > 0) parts.push({ text: `${n(need)} ${need === 1 ? 'needs' : 'need'} a match`, warn: true })
  parts.push(library.missingCount > 0 ? { text: `${n(library.missingCount)} missing`, warn: true } : { text: 'none missing' })
  parts.push({ text: library.lastScannedAt ? `scanned ${relativeTime(library.lastScannedAt)}` : 'not scanned yet' })
  return parts
}

/** The Files page's filter chips: one choice at a time, each a status and match the files reply filters by. */
export type FilesFilter = 'all' | 'matched' | 'unmatched' | 'pending' | 'failed' | 'missing' | 'unreadable'

/** unreadable is how many present files ffprobe couldn't read (API level 34): its chip shows only when there are any, or while it is chosen. */
export function filesChips(
  library: Pick<Library, 'fileCount' | 'unmatchedCount' | 'pendingCount' | 'missingCount'>,
  unreadable?: number,
  chosen?: FilesFilter,
): { value: FilesFilter; label: string; count?: number }[] {
  const need = library.unmatchedCount + library.pendingCount
  const chips: { value: FilesFilter; label: string; count?: number }[] = [
    { value: 'all', label: 'All', count: library.fileCount + library.missingCount },
    { value: 'matched', label: 'Matched', count: Math.max(0, library.fileCount - need) },
    { value: 'unmatched', label: 'Needs a match', count: library.unmatchedCount },
  ]
  if (library.pendingCount > 0) chips.push({ value: 'pending', label: 'Waiting to match', count: library.pendingCount })
  chips.push({ value: 'failed', label: 'Match failed' })
  chips.push({ value: 'missing', label: 'Missing', count: library.missingCount })
  if (unreadable || chosen === 'unreadable') chips.push({ value: 'unreadable', label: 'Unreadable', count: unreadable })
  return chips
}

/** A chip's choice as the files reply's status and match filters. */
export function filesQueryOf(filter: FilesFilter): { status?: 'present' | 'missing'; match?: 'matched' | 'unmatched' | 'pending' | 'failed'; probe?: 'failed' } {
  switch (filter) {
    case 'all':
      return {}
    case 'unreadable':
      return { status: 'present', probe: 'failed' }
    case 'missing':
      return { status: 'missing' }
    default:
      return { status: 'present', match: filter }
  }
}

type MediaFile = components['schemas']['MediaFile']

/**
 * A file's row title: the matched title ("Harbor Lights (2019)", "Night
 * Shift Diner · S1 E2" with the episode's own title), else what was read
 * from its name, for a file not matched yet.
 */
export function fileTitle(f: Pick<MediaFile, 'title' | 'year' | 'episode'>): { title: string; episode?: string } | undefined {
  if (!f.title) return undefined
  if (f.episode) return { title: `${f.title} · S${f.episode.seasonNumber} E${f.episode.episodeNumber}`, episode: f.episode.title || undefined }
  return { title: f.year ? `${f.title} (${f.year})` : f.title }
}
