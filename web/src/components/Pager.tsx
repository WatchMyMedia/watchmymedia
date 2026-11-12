export function Pager({ offset, limit, total, onChange }: { offset: number; limit: number; total: number; onChange: (offset: number) => void }) {
  if (total <= limit) return null
  return (
    <div className="flex items-center justify-between text-sm text-neutral-400">
      <span>
        {offset + 1}–{Math.min(offset + limit, total)} of {total}
      </span>
      <div className="flex gap-2">
        <button
          type="button"
          disabled={offset === 0}
          onClick={() => onChange(Math.max(0, offset - limit))}
          className="rounded-md border border-neutral-700 px-3 py-1 disabled:opacity-40"
        >
          Previous
        </button>
        <button
          type="button"
          disabled={offset + limit >= total}
          onClick={() => onChange(offset + limit)}
          className="rounded-md border border-neutral-700 px-3 py-1 disabled:opacity-40"
        >
          Next
        </button>
      </div>
    </div>
  )
}
