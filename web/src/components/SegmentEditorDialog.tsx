import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { fileSegmentsQuery, useSaveSegments } from '../api/queries'
import type { components } from '../api/schema'
import { clock, parseClock } from '../lib/clock'

type Stored = components['schemas']['StoredSegment']
type FileSegments = components['schemas']['FileSegments']
type Manual = components['schemas']['ManualSegment']

const KINDS = ['intro', 'recap', 'credits', 'preview', 'commercial'] as const
const KIND_LABEL: Record<string, string> = { intro: 'Intro', recap: 'Recap', credits: 'Credits', preview: 'Preview', commercial: 'Ad' }
const SOURCE_LABEL: Record<string, string> = { chapter: 'chapter mark', fingerprint: 'audio match', blackframe: 'black frames', subtitle: 'subtitle', external: 'community data', manual: 'drawn by hand' }

type Row = { key: number; kind: string; start: string; end: string; final: boolean }

/** Admin editor for one file's skip segments: hide or restore detected ones, and draw manual ones. */
export function SegmentEditorDialog({ fileId, title, onClose }: { fileId: number; title: string; onClose: () => void }) {
  const data = useQuery(fileSegmentsQuery(fileId))
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])
  return (
    <div className="fixed inset-0 z-50 flex items-start justify-center overflow-y-auto bg-black/80 p-4 sm:p-10" onClick={onClose}>
      <div className="w-full max-w-2xl rounded-xl border border-line-2 bg-surface shadow-xl" onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true" aria-labelledby="seg-title">
        <div className="flex items-start justify-between gap-4 px-6 pt-5">
          <div className="min-w-0">
            <h2 id="seg-title" className="text-xl font-bold">
              Skip segments
            </h2>
            <p className="mt-1 break-all text-sm text-neutral-400">
              {title}
              {data.data?.durationMs ? ` · ${clock(data.data.durationMs)}` : ''}
            </p>
          </div>
          <button type="button" onClick={onClose} aria-label="Close" className="min-h-[44px] min-w-[44px] rounded-md text-neutral-400 hover:bg-surface-2 hover:text-neutral-100">
            ✕
          </button>
        </div>
        {data.isPending && <p className="px-6 py-5 text-sm text-neutral-400">Loading…</p>}
        {data.isError && <p className="px-6 py-5 text-sm text-red-400">{data.error.message}</p>}
        {data.data && <SegmentForm fileId={fileId} d={data.data} onClose={onClose} />}
      </div>
    </div>
  )
}

/** The editable part, seeded from the stored segments it is given. */
function SegmentForm({ fileId, d, onClose }: { fileId: number; d: FileSegments; onClose: () => void }) {
  const save = useSaveSegments()
  const [rows, setRows] = useState<Row[]>(() =>
    d.segments.filter((s) => s.source === 'manual').map((s, i) => ({ key: i, kind: s.kind, start: clock(s.startMs), end: s.endMs ? clock(s.endMs) : '', final: s.final })),
  )
  const [hidden, setHidden] = useState<Set<number>>(() => new Set(d.segments.filter((s) => s.source !== 'manual' && s.state === 'suppressed').map((s) => s.id)))
  const [error, setError] = useState<string | null>(null)
  const detected: Stored[] = d.segments.filter((s) => s.source !== 'manual')

  const submit = () => {
    const manual: Manual[] = []
    for (const r of rows) {
      const start = parseClock(r.start)
      const end = r.end.trim() ? parseClock(r.end) : 0
      if (start == null || end == null) return setError(`Times must look like 1:23 (${KIND_LABEL[r.kind]} segment)`)
      if (end !== 0 && end <= start) return setError(`The ${KIND_LABEL[r.kind]} segment ends before it starts`)
      manual.push({ kind: r.kind as Manual['kind'], startMs: start, endMs: end, final: r.kind === 'credits' && r.final })
    }
    const was = new Set(detected.filter((s) => s.state === 'suppressed').map((s) => s.id))
    const suppressed = [...hidden].filter((id) => !was.has(id))
    const restored = [...was].filter((id) => !hidden.has(id))
    setError(null)
    save.mutate({ fileId, manual, suppressed, restored }, { onSuccess: onClose, onError: (e) => setError(e.message) })
  }

  return (
    <>
          <div className="space-y-6 px-6 pt-5">
            <section className="space-y-2">
              <h3 className="text-xs font-semibold uppercase tracking-wider text-neutral-400">Detected</h3>
              {detected.length === 0 && <p className="text-sm text-neutral-400">Nothing has been detected for this file yet. Chapters are read on first play; audio matching runs in the background.</p>}
              <ul className="divide-y divide-line rounded-lg border border-line-2">
                {detected.map((s) => (
                  <li key={s.id} className={`flex flex-wrap items-center gap-3 px-3 py-2.5 text-sm ${hidden.has(s.id) ? 'opacity-60' : ''}`}>
                    <span className="w-16 font-medium">{KIND_LABEL[s.kind]}</span>
                    <span className="tabular-nums text-neutral-200">
                      {clock(s.startMs)} – {s.endMs ? clock(s.endMs) : 'end'}
                    </span>
                    <span className="text-xs text-neutral-400">
                      {SOURCE_LABEL[s.source] ?? s.source}
                      {s.confidence < 1 ? ` · ${Math.round(s.confidence * 100)}%` : ''}
                      {s.final ? ' · final credits' : ''}
                    </span>
                    <label className="ml-auto flex items-center gap-2 text-xs text-neutral-300">
                      <input
                        type="checkbox"
                        checked={hidden.has(s.id)}
                        onChange={(e) => {
                          const next = new Set(hidden)
                          if (e.target.checked) next.add(s.id)
                          else next.delete(s.id)
                          setHidden(next)
                        }}
                        className="h-4 w-4 accent-accent"
                      />
                      Hide
                    </label>
                  </li>
                ))}
              </ul>
              {detected.length > 0 && <p className="text-xs text-neutral-500">A hidden segment stays hidden when the file is analysed again.</p>}
            </section>

            <section className="space-y-2">
              <h3 className="text-xs font-semibold uppercase tracking-wider text-neutral-400">Drawn by hand</h3>
              <p className="text-xs text-neutral-500">These win over detected segments of the same kind. Times are m:ss from the start; leave the end blank to run to the end of the file.</p>
              <ul className="space-y-2">
                {rows.map((r, i) => (
                  <li key={r.key} className="flex flex-wrap items-center gap-2 rounded-lg border border-line-2 px-3 py-2">
                    <select value={r.kind} onChange={(e) => setRows(rows.map((x, j) => (j === i ? { ...x, kind: e.target.value } : x)))} className="min-h-[40px] rounded-md border border-line-2 bg-ground px-2 text-sm" aria-label="Kind">
                      {KINDS.map((k) => (
                        <option key={k} value={k}>
                          {KIND_LABEL[k]}
                        </option>
                      ))}
                    </select>
                    <input value={r.start} onChange={(e) => setRows(rows.map((x, j) => (j === i ? { ...x, start: e.target.value } : x)))} placeholder="0:00" aria-label="Start" className="min-h-[40px] w-24 rounded-md border border-line-2 bg-ground px-2 text-sm tabular-nums" />
                    <span className="text-neutral-500">–</span>
                    <input value={r.end} onChange={(e) => setRows(rows.map((x, j) => (j === i ? { ...x, end: e.target.value } : x)))} placeholder="end" aria-label="End" className="min-h-[40px] w-24 rounded-md border border-line-2 bg-ground px-2 text-sm tabular-nums" />
                    {r.kind === 'credits' && (
                      <label className="flex items-center gap-1.5 text-xs text-neutral-300">
                        <input type="checkbox" checked={r.final} onChange={(e) => setRows(rows.map((x, j) => (j === i ? { ...x, final: e.target.checked } : x)))} className="h-4 w-4 accent-accent" />
                        Final
                      </label>
                    )}
                    <button type="button" onClick={() => setRows(rows.filter((_, j) => j !== i))} className="ml-auto min-h-[36px] rounded-md px-2 text-sm text-neutral-400 hover:text-neutral-100" aria-label="Remove segment">
                      Remove
                    </button>
                  </li>
                ))}
              </ul>
              <button type="button" onClick={() => setRows([...rows, { key: Date.now(), kind: 'intro', start: '', end: '', final: false }])} className="min-h-[36px] rounded-md border border-line-2 bg-surface-2 px-3 text-sm hover:bg-ground">
                Add segment
              </button>
            </section>
          </div>

        {error && <p className="px-6 pt-3 text-sm text-red-400">{error}</p>}
        <div className="mt-5 flex justify-end gap-2 border-t border-line px-6 py-4">
          <button type="button" onClick={onClose} className="min-h-[44px] rounded-md px-4 text-sm text-neutral-400 hover:bg-surface-2 hover:text-neutral-100">
            Cancel
          </button>
          <button type="button" disabled={save.isPending} onClick={submit} className="min-h-[44px] rounded-md btn-primary px-5 text-sm font-semibold disabled:opacity-50">
            {save.isPending ? 'Saving…' : 'Save'}
          </button>
        </div>
    </>
  )
}
