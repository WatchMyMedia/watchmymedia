import { useMatchLibrary, useScanLibrary } from '../api/queries'
import { useEventState } from '../api/events'
import type { components } from '../api/schema'

type Library = components['schemas']['Library']

export function LibraryActions({ library, tmdbConfigured }: { library: Library; tmdbConfigured: boolean }) {
  const scan = useScanLibrary()
  const match = useMatchLibrary()
  const state = useEventState()
  const scanning = state.scans[library.id] !== undefined
  const matching = state.matches[library.id] !== undefined
  const needsMatch = library.pendingCount + library.unmatchedCount > 0
  const btn = 'rounded-md border border-neutral-700 px-3 py-1 text-sm hover:bg-neutral-800 disabled:opacity-50'
  return (
    <div className="flex items-center gap-2">
      <button
        type="button"
        disabled={scanning || scan.isPending}
        onClick={(e) => {
          e.preventDefault()
          e.stopPropagation()
          scan.mutate(library.id)
        }}
        className={btn}
      >
        {scanning ? 'Scanning…' : 'Scan'}
      </button>
      <button
        type="button"
        disabled={!tmdbConfigured || matching || match.isPending || library.fileCount === 0}
        title={tmdbConfigured ? (needsMatch ? 'Match pending and unmatched files on TMDB' : 'Re-check unmatched files') : 'Set a TMDB API key first'}
        onClick={(e) => {
          e.preventDefault()
          e.stopPropagation()
          match.mutate({ id: library.id, rematch: true })
        }}
        className={btn}
      >
        {matching ? 'Matching…' : 'Match'}
      </button>
    </div>
  )
}
