import { lazy, Suspense, type ComponentProps, type ReactNode } from 'react'

// Edit rows loads when someone opens it, keeping it out of Home's first download.
const LazyEditRows = lazy(() => import('./EditRows').then((m) => ({ default: m.EditRows })))

/** Edit rows, loaded on first use. */
export function EditRows(props: ComponentProps<typeof LazyEditRows>) {
  return (
    <Suspense fallback={<p className="text-neutral-400">Loading…</p>}>
      <LazyEditRows {...props} />
    </Suspense>
  )
}

/** The way into Edit rows, under a screen's rows (design/mockups/web/Home, LibraryOverview). */
export function EditRowsBar({ onEdit, children }: { onEdit: () => void; children: ReactNode }) {
  return (
    <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
      <button type="button" onClick={onEdit} className="inline-flex min-h-[40px] items-center gap-2 rounded-md border border-line-2 bg-surface px-4 text-sm font-medium hover:bg-surface-2">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
          <path d="M4 6h10M18 6h2M4 12h4M12 12h8M4 18h12M20 18h0" />
          <circle cx="16" cy="6" r="2" />
          <circle cx="10" cy="12" r="2" />
          <circle cx="18" cy="18" r="2" />
        </svg>
        Edit rows
      </button>
      <span className="text-sm text-neutral-400">{children}</span>
    </div>
  )
}

