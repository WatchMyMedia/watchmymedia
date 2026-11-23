import type { components } from '../api/schema'
import { relativeTime } from './format'

type Library = components['schemas']['Library']
type ScanProgress = components['schemas']['ScanProgress']
type MatchProgress = components['schemas']['MatchProgress']

/**
 * The account menu's line under Library status: what the libraries are doing
 * now, in words, with a share done when something is under way.
 */
export function libraryStatusLine(
  libraries: readonly Pick<Library, 'id' | 'name' | 'lastScannedAt' | 'unmatchedCount' | 'missingCount'>[],
  scans: Record<number, Pick<ScanProgress, 'scanned' | 'total'>>,
  matches: Record<number, Pick<MatchProgress, 'done' | 'total'>>,
): { text: string; progress?: number } {
  const name = (id: number) => libraries.find((l) => l.id === id)?.name ?? 'a library'
  const share = (done: number, total: number) => (total > 0 ? Math.min(100, Math.round((done / total) * 100)) : undefined)
  const scan = Object.entries(scans)[0]
  if (scan) {
    const [id, p] = scan
    const of = p.total > 0 ? ` · ${p.scanned.toLocaleString()} of ${p.total.toLocaleString()} files` : ''
    return { text: `Scanning ${name(Number(id))}${of}`, progress: share(p.scanned, p.total) }
  }
  const match = Object.entries(matches)[0]
  if (match) {
    const [id, p] = match
    const of = p.total > 0 ? ` · ${p.done.toLocaleString()} of ${p.total.toLocaleString()}` : ''
    return { text: `Matching ${name(Number(id))}${of}`, progress: share(p.done, p.total) }
  }
  const unmatched = libraries.reduce((n, l) => n + l.unmatchedCount, 0)
  const missing = libraries.reduce((n, l) => n + l.missingCount, 0)
  const extra = [unmatched ? `${unmatched.toLocaleString()} unmatched` : '', missing ? `${missing.toLocaleString()} missing` : ''].filter(Boolean)
  if (extra.length) return { text: `${extra.join(' · ')} ${unmatched + missing === 1 ? 'file' : 'files'}` }
  const last = libraries.map((l) => l.lastScannedAt).filter((d): d is string => !!d).sort().at(-1)
  return { text: last ? `Up to date · scanned ${relativeTime(last)}` : 'Not scanned yet' }
}
