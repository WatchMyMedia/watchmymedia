/** "1–100 of 236" with Previous and Next, when there is more than one page. */
export function Pager({ offset, limit, total, onChange }: { offset: number; limit: number; total: number; onChange: (offset: number) => void }) {
  if (total <= limit) return null
  return (
    <nav aria-label="Pages" className="flex flex-wrap items-center gap-3">
      <span className="text-[13px] text-neutral-400">
        {(offset + 1).toLocaleString()}–{Math.min(offset + limit, total).toLocaleString()} of {total.toLocaleString()}
      </span>
      <span className="ml-auto flex gap-2">
        <button type="button" disabled={offset === 0} onClick={() => onChange(Math.max(0, offset - limit))} className="btn btn-sm">
          ‹ Previous
        </button>
        <button type="button" disabled={offset + limit >= total} onClick={() => onChange(offset + limit)} className="btn btn-sm">
          Next ›
        </button>
      </span>
    </nav>
  )
}
