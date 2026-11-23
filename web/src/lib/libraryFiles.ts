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
export type FilesFilter = 'all' | 'matched' | 'unmatched' | 'pending' | 'failed' | 'missing'

export function filesChips(library: Pick<Library, 'fileCount' | 'unmatchedCount' | 'pendingCount' | 'missingCount'>): { value: FilesFilter; label: string; count?: number }[] {
  const need = library.unmatchedCount + library.pendingCount
  const chips: { value: FilesFilter; label: string; count?: number }[] = [
    { value: 'all', label: 'All', count: library.fileCount + library.missingCount },
    { value: 'matched', label: 'Matched', count: Math.max(0, library.fileCount - need) },
    { value: 'unmatched', label: 'Needs a match', count: library.unmatchedCount },
  ]
  if (library.pendingCount > 0) chips.push({ value: 'pending', label: 'Waiting to match', count: library.pendingCount })
  chips.push({ value: 'failed', label: 'Match failed' })
  chips.push({ value: 'missing', label: 'Missing', count: library.missingCount })
  return chips
}

/** A chip's choice as the files reply's status and match filters. */
export function filesQueryOf(filter: FilesFilter): { status?: 'present' | 'missing'; match?: 'matched' | 'unmatched' | 'pending' | 'failed' } {
  switch (filter) {
    case 'all':
      return {}
    case 'missing':
      return { status: 'missing' }
    default:
      return { status: 'present', match: filter }
  }
}
