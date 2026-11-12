import { useState } from 'react'

/**
 * A chart's frame: title, subtitle, and a toggle to its table view, the
 * accessible twin that shows every value without hovering.
 */
export function ChartCard({
  title,
  subtitle,
  chart,
  table,
  dimmed = false,
}: {
  title: string
  subtitle?: string
  chart: React.ReactNode
  table: React.ReactNode
  dimmed?: boolean
}) {
  const [asTable, setAsTable] = useState(false)
  return (
    <figure className="min-w-0 rounded-lg border border-line bg-surface p-4">
      <div className="mb-3 flex items-start justify-between gap-3">
        <figcaption>
          <h3 className="font-semibold">{title}</h3>
          {subtitle && <p className="text-sm text-neutral-400">{subtitle}</p>}
        </figcaption>
        <button
          type="button"
          onClick={() => setAsTable((t) => !t)}
          aria-pressed={asTable}
          className="shrink-0 rounded-md border border-line-2 px-2 py-1 text-xs text-neutral-400 hover:text-neutral-100"
        >
          {asTable ? 'Chart' : 'Table'}
        </button>
      </div>
      {/* Refetching keeps the last render, dimmed, so nothing jumps. */}
      <div className={`transition-opacity ${dimmed ? 'opacity-50' : ''}`}>{asTable ? <div className="max-h-80 overflow-auto">{table}</div> : chart}</div>
    </figure>
  )
}

/** A plain table for a chart's data. */
export function DataTable({ head, rows }: { head: string[]; rows: (string | number)[][] }) {
  return (
    <table className="w-full text-sm">
      <thead className="sticky top-0 bg-surface">
        <tr className="text-left text-xs uppercase tracking-wider text-neutral-500">
          {head.map((h, i) => (
            <th key={h} className={`py-1.5 pr-3 font-medium ${i > 0 ? 'text-right' : ''}`}>
              {h}
            </th>
          ))}
        </tr>
      </thead>
      <tbody className="divide-y divide-line tabular-nums">
        {rows.map((r) => (
          <tr key={String(r[0])}>
            {r.map((c, i) => (
              <td key={i} className={`py-1 pr-3 ${i > 0 ? 'text-right text-neutral-200' : 'text-neutral-300'}`}>
                {c}
              </td>
            ))}
          </tr>
        ))}
      </tbody>
    </table>
  )
}
