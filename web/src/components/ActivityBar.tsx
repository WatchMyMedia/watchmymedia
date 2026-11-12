import { useEventState } from '../api/events'
import type { components } from '../api/schema'
import { savedScanError } from '../lib/setupRules'

type Library = components['schemas']['Library']

/** Live progress of a scan or metadata match for one library, else the last result. */
export function ActivityBar({ library }: { library: Library }) {
  const state = useEventState()
  const scan = state.scans[library.id] ?? library.scan
  const match = state.matches[library.id] ?? library.match
  const lastScan = state.lastScan[library.id]
  const lastMatch = state.lastMatch[library.id]

  if (scan) {
    const pct = scan.total ? Math.round((scan.scanned / scan.total) * 100) : 0
    return <Bar label="Scanning" current={scan.current ?? 'Collecting files…'} pct={pct} done={scan.scanned} total={scan.total} />
  }
  if (match) {
    const pct = match.total ? Math.round((match.done / match.total) * 100) : 0
    return <Bar label="Matching" current={match.current ?? ''} pct={pct} done={match.done} total={match.total} color="bg-accent" />
  }
  const lines: string[] = []
  const scanError = lastScan?.failed?.error ?? savedScanError(library, false, lastScan)
  if (lastScan?.finished) {
    const f = lastScan.finished
    lines.push(
      `Scan: ${f.added} added, ${f.updated} updated, ${f.missing} missing` +
        (f.failed ? `, ${f.failed} unreadable` : '') +
        (f.skipped ? `, ${f.skipped} still copying` : ''),
    )
  }
  if (scanError) lines.push(scanError)
  if (lastMatch) {
    const m = lastMatch.finished
    lines.push(`Match: ${m.matched} matched, ${m.unmatched} unmatched` + (m.failed ? `, ${m.failed} lookup errors` : ''))
  }
  if (lines.length === 0) return null
  return (
    <div className="mt-3 space-y-0.5 text-xs text-neutral-500">
      {lines.map((l) => (
        <p key={l} className={l === scanError ? 'text-warn' : ''}>
          {l}
        </p>
      ))}
      {scanError && <p className="text-neutral-300">Check that the drive is connected and this server can read the folder, then choose Scan now.</p>}
    </div>
  )
}

function Bar({
  label,
  current,
  pct,
  done,
  total,
  color = 'bg-emerald-500',
}: {
  label: string
  current: string
  pct: number
  done: number
  total: number
  color?: string
}) {
  return (
    <div className="mt-3 text-xs text-neutral-400">
      <div className="h-1.5 w-full overflow-hidden rounded bg-neutral-800">
        <div className={`h-full ${color} transition-[width]`} style={{ width: `${pct}%` }} />
      </div>
      <div className="mt-1 flex justify-between gap-2">
        <span className="truncate">
          {label}: {current}
        </span>
        <span className="shrink-0">
          {done} / {total}
        </span>
      </div>
    </div>
  )
}
