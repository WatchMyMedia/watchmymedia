import { useState } from 'react'

/**
 * A chart's frame: its heading, a note and a key, and its table view, the
 * accessible twin that shows every value without hovering. asTable, from
 * the page, turns every chart into its table at once; without it the card
 * has its own Table button.
 */
export function ChartCard({
  title,
  subtitle,
  chart,
  table,
  dimmed = false,
  asTable: pageTable,
  keys,
  className = '',
}: {
  title: string
  subtitle?: string
  chart: React.ReactNode
  table: React.ReactNode
  dimmed?: boolean
  asTable?: boolean
  keys?: React.ReactNode
  className?: string
}) {
  const [ownTable, setOwnTable] = useState(false)
  const asTable = pageTable ?? ownTable
  return (
    <figure className={`panel m-0 min-w-0 px-5 pb-4 pt-[18px] ${className}`}>
      <figcaption className="rowh mb-4">
        <h2>{title}</h2>
        {subtitle && <span className="note">{subtitle}</span>}
        {keys && <span className="ml-auto flex flex-wrap gap-4">{keys}</span>}
        {pageTable === undefined && (
          <button type="button" onClick={() => setOwnTable((t) => !t)} aria-pressed={asTable} className={`btn btn-sm btn-ghost ${keys ? '' : 'ml-auto'}`}>
            {asTable ? 'Chart' : 'Table'}
          </button>
        )}
      </figcaption>
      {/* Refetching keeps the last render, dimmed, so nothing jumps. */}
      <div className={`transition-opacity ${dimmed ? 'opacity-50' : ''}`}>{asTable ? <div className="max-h-80 overflow-auto">{table}</div> : chart}</div>
    </figure>
  )
}

/** A series' key: its colour and its name. */
export function Key({ color, children }: { color: string; children: React.ReactNode }) {
  return (
    <span className="inline-flex items-center gap-[7px] text-[13px] text-neutral-300">
      <i aria-hidden="true" className="block size-3 rounded-[3px]" style={{ background: color }} />
      {children}
    </span>
  )
}

/** A plain table for a chart's data. */
export function DataTable({ head, rows }: { head: string[]; rows: (string | number)[][] }) {
  return (
    <table className="w-full text-sm">
      <thead className="sticky top-0 bg-surface">
        <tr className="text-left text-xs uppercase tracking-wider text-dim">
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
