import type { components } from '../../api/schema'
import { Badge } from '../Badge'

/** The same short origin line on episode and movie pages. */
export function RecordingProvenance({ recording: r }: { recording?: components['schemas']['LiveTVProvenance'] }) {
  if (!r) return null
  const when = new Date(r.startedAt).toLocaleString([], { weekday: 'short', hour: 'numeric', minute: '2-digit' })
  return <p className="flex flex-wrap items-center gap-2 text-xs leading-relaxed text-neutral-400"><Badge>Recorded</Badge><span>From {r.number} {r.channel} · {when}</span>{r.gaps.length > 0 && <span className="text-warn">Part recorded · {r.gaps.length} {r.gaps.length === 1 ? 'gap' : 'gaps'}</span>}{r.adBreaks === 'finding' && <span>Finding ad breaks</span>}</p>
}
