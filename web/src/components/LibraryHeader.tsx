import { Link } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import { healthQuery } from '../api/queries'
import { ActivityBar } from './ActivityBar'
import { LibraryActions } from './LibraryActions'
import type { components } from '../api/schema'

type Library = components['schemas']['Library']

export function LibraryHeader({ library }: { library: Library }) {
  const health = useQuery(healthQuery)
  const tmdb = health.data?.tmdbConfigured ?? false
  return (
    <div className="space-y-4">
      <nav className="text-sm text-neutral-500">
        <Link to="/settings/libraries" className="hover:text-neutral-300">
          Settings
        </Link>{' '}
        / {library.name} / Files
      </nav>
      <header className="rounded-lg border border-line bg-surface p-4">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <h1 className="text-2xl font-semibold">{library.name}</h1>
            <p className="break-all text-sm text-neutral-400">{library.path}</p>
            <p className="mt-1 text-sm text-neutral-400">
              {library.fileCount} files
              {library.missingCount > 0 && <span className="text-warn"> · {library.missingCount} missing</span>}
              {library.pendingCount > 0 && <span className="text-neutral-500"> · {library.pendingCount} awaiting match</span>}
              {library.unmatchedCount > 0 && <span className="text-warn"> · {library.unmatchedCount} unmatched</span>}
            </p>
          </div>
          <div className="flex items-center gap-3">
            <Link to={library.type === 'tv' ? '/tv' : '/movies'} className="text-sm text-neutral-400 hover:text-neutral-100">
              Browse ›
            </Link>
            <LibraryActions library={library} tmdbConfigured={tmdb} />
          </div>
        </div>
        <ActivityBar library={library} />
        {health.data && !tmdb && (
          <p className="mt-3 rounded-md border border-warn/40 bg-warn/10 px-3 py-2 text-xs text-warn">
            Posters and title details are off. Add a TMDB key in <Link to="/settings/general" className="underline">Settings › General</Link>.
          </p>
        )}
      </header>
    </div>
  )
}
