import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { librariesQuery, rowsQuery, useLibraryByType } from '../api/queries'
import { BrowseGrid } from '../components/BrowseGrid'
import { SegmentedControl } from '../components/Controls'
import { EditRows, EditRowsBar } from '../components/rows/EditRowsBar'
import { ScreenRows } from '../components/rows/ScreenRows'
import { UnmatchedView } from '../components/UnmatchedView'
import { relativeTime } from '../lib/format'
import type { BrowseSearch } from '../lib/routeSearch'
import { libraryRows, libraryScreen } from '../lib/screenRows'

type View = 'overview' | 'all' | 'genres' | 'unmatched'

/**
 * The TV Shows and Movies screens: header, rows, then the full grid. A
 * row's See all opens it on All with the row's sort and filter (search).
 */
export function LibraryBrowse({ type, search }: { type: 'movie' | 'tv'; search: BrowseSearch }) {
  const { q, view: initialView, library: libraryId } = search
  const { library, isPending, isError, error } = useLibraryByType(type, libraryId)
  const [view, setView] = useState<View>(initialView ?? 'overview')
  const initial = { sort: search.sort, filter: search.filter, genre: search.genre, decade: search.decade }
  if (isPending) return <p className="text-neutral-400">Loading…</p>
  if (isError) return <p className="text-red-400">{error.message}</p>
  if (!library)
    return (
      <p className="text-neutral-400">
        No {type === 'tv' ? 'TV' : 'movie'} library is configured. Set <code>{type === 'tv' ? 'WATCHMYMEDIA_TV_PATH' : 'WATCHMYMEDIA_MOVIES_PATH'}</code> and restart.
      </p>
    )
  if (search.edit) return <LibraryEditRows library={library} type={type} search={search} />
  const unmatched = library.unmatchedCount + library.pendingCount
  const title = type === 'tv' ? 'TV Shows' : 'Movies'
  const effectiveView: View = q ? 'all' : view
  return (
    <div className="space-y-8">
      <header className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="text-3xl font-bold">{title}</h1>
          <p className="mt-1 text-sm text-neutral-400">
            {library.fileCount} files
            {library.missingCount > 0 && <span className="text-warn"> · {library.missingCount} missing</span>} · scanned {relativeTime(library.lastScannedAt)} ·{' '}
            <span className="break-all">{library.path}</span>
          </p>
        </div>
        <SegmentedControl<View>
          options={[
            { value: 'overview', label: 'Overview' },
            { value: 'all', label: type === 'tv' ? 'All shows' : 'All movies' },
            { value: 'genres', label: 'Genres' },
            { value: 'unmatched', label: unmatched ? `Unmatched ${unmatched}` : 'Unmatched' },
          ]}
          value={effectiveView}
          onChange={setView}
        />
      </header>

      {q && (
        <p className="text-sm text-neutral-400">
          Results for “{q}”.{' '}
          <Link to={type === 'tv' ? '/tv' : '/movies'} search={{ library: libraryId }} className="underline hover:text-neutral-100">
            Clear
          </Link>
        </p>
      )}

      {effectiveView === 'overview' && <Rows libraryId={library.id} type={type} search={search} />}
      {(effectiveView === 'overview' || effectiveView === 'all') && (
        <section className="space-y-3">
          <h2 className="text-lg font-semibold">{type === 'tv' ? 'All shows' : 'All movies'}</h2>
          <BrowseGrid libraryId={library.id} type={type} q={q} initial={initial} />
        </section>
      )}
      {effectiveView === 'genres' && <BrowseGrid libraryId={library.id} type={type} q={q} groupByGenre />}
      {effectiveView === 'unmatched' && <UnmatchedView libraryId={library.id} type={type} />}
    </div>
  )
}

/** The Overview's rows in the person's order, and Edit rows after them, above the grid (PLAN-ROWS.md §5). */
function Rows({ libraryId, type, search }: { libraryId: number; type: 'movie' | 'tv'; search: BrowseSearch }) {
  const rows = useQuery(rowsQuery(libraryId))
  const libraries = useQuery(librariesQuery)
  const navigate = useNavigate()
  if (!rows.data) return null
  return (
    <div className="space-y-8">
      <ScreenRows rows={libraryRows(rows.data, type)} libraries={libraries.data?.items ?? []} />
      <EditRowsBar onEdit={() => void navigate({ to: type === 'tv' ? '/tv' : '/movies', search: { library: search.library, edit: true } })}>
        Move, hide and add the rows above {type === 'tv' ? 'All shows' : 'All movies'}.
      </EditRowsBar>
    </div>
  )
}

/** Edit rows for a library's screen, in place of the page. */
function LibraryEditRows({ library, type, search }: { library: { id: number; name: string }; type: 'movie' | 'tv'; search: BrowseSearch }) {
  const navigate = useNavigate()
  const to = type === 'tv' ? '/tv' : '/movies'
  const screen = libraryScreen(library.id)
  const all = type === 'tv' ? 'All shows' : 'All movies'
  return (
    <EditRows
      screen={screen}
      screenName={library.name || (type === 'tv' ? 'TV Shows' : 'Movies')}
      adding={!!search.add}
      onAdding={(open) => void navigate({ to, search: { library: search.library, edit: true, add: open || undefined }, replace: true })}
      onMakeList={() => void navigate({ to: '/lists/new', search: { screen } })}
      onClose={() => void navigate({ to, search: { library: search.library } })}
      below={[`${all} · the whole library, always below the rows`, 'always below the rows']}
    />
  )
}
