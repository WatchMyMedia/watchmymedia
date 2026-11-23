import { useMatchLibrary, useScanLibrary } from '../api/queries'
import { useEventState } from '../api/events'
import { useAuth } from '../authContext'
import type { components } from '../api/schema'

type Library = components['schemas']['Library']

/** Scan and Match for an admin; nothing for anyone else. large is the Files page's pair; the Library status panel's are small. */
export function LibraryActions({ library, tmdbConfigured, large = false }: { library: Library; tmdbConfigured: boolean; large?: boolean }) {
  const scan = useScanLibrary()
  const match = useMatchLibrary()
  const state = useEventState()
  const auth = useAuth()
  const scanning = state.scans[library.id] !== undefined
  const matching = state.matches[library.id] !== undefined
  const needsMatch = library.pendingCount + library.unmatchedCount > 0
  const btn = large ? 'btn' : 'btn btn-sm'
  // Scanning and matching are for admins (the server refuses members).
  if (!(auth.user?.isAdmin ?? false) || !auth.adminAllowed) return null
  return (
    <div className={`flex items-center ${large ? 'gap-2.5' : 'gap-2'}`}>
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
