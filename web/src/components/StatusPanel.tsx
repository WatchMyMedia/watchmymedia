import { useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { healthQuery, librariesQuery } from '../api/queries'
import { ActivityBar } from './ActivityBar'
import { LibraryActions } from './LibraryActions'

/** Popover behind the top-bar scan-status button: every library's activity. */
export function StatusPanel({ onClose }: { onClose: () => void }) {
  const libs = useQuery(librariesQuery)
  const health = useQuery(healthQuery)
  const tmdb = health.data?.tmdbConfigured ?? false
  return (
    <div className="absolute right-0 top-12 z-40 w-[min(92vw,440px)] rounded-lg border border-line-2 bg-surface p-4 shadow-xl">
      <div className="flex items-center justify-between">
        <h2 className="text-sm font-semibold">Library status</h2>
        <button type="button" onClick={onClose} className="text-neutral-400 hover:text-neutral-100">
          ✕
        </button>
      </div>
      {health.data && !tmdb && <p className="mt-2 text-xs text-warn">TMDB API key not configured; matching is off.</p>}
      <ul className="mt-3 space-y-3">
        {libs.data?.items.map((lib) => (
          <li key={lib.id} className="rounded-md border border-line p-3">
            <div className="flex items-center justify-between gap-2">
              <Link to="/libraries/$libraryId/files" params={{ libraryId: String(lib.id) }} onClick={onClose} className="text-sm font-medium hover:underline">
                {lib.name}
              </Link>
              <LibraryActions library={lib} tmdbConfigured={tmdb} />
            </div>
            <p className="mt-1 text-xs text-neutral-500">
              {lib.fileCount} files
              {lib.missingCount > 0 && <span className="text-warn"> · {lib.missingCount} missing</span>}
              {lib.pendingCount > 0 && ` · ${lib.pendingCount} awaiting match`}
              {lib.unmatchedCount > 0 && <span className="text-warn"> · {lib.unmatchedCount} unmatched</span>}
            </p>
            <ActivityBar library={lib} />
          </li>
        ))}
      </ul>
    </div>
  )
}
