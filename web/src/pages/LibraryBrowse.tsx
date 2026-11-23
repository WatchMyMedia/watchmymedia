import { useRef, useState, type KeyboardEvent } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { itemsQuery, librariesQuery, rowsQuery, useLibraryByType } from '../api/queries'
import { BrowseGenres, BrowseGrid } from '../components/BrowseGrid'
import { EditRows, EditRowsBar } from '../components/rows/EditRowsBar'
import { ScreenRows } from '../components/rows/ScreenRows'
import { UnmatchedView } from '../components/UnmatchedView'
import { useBrowsePrefs } from '../components/useBrowsePrefs'
import { ArrowLeftIcon } from '../components/icons'
import { useAuth } from '../authContext'
import { libraryCountsLine, needsMatchWords, type BrowseTab } from '../lib/browsePrefs'
import type { BrowseSearch } from '../lib/routeSearch'
import { libraryRows, libraryScreen } from '../lib/screenRows'

const TAB_PAGE = 120

/**
 * The TV Shows and Movies screens: what the library holds in words, then
 * Overview (the rows, then every title), every title, or the genres. The tab,
 * filters, sort and view are remembered on this browser for each library; a
 * row's See all opens every title with the row's sort and filter.
 */
export function LibraryBrowse({ type, search }: { type: 'movie' | 'tv'; search: BrowseSearch }) {
  const { library: libraryId } = search
  const { library, isPending, isError, error } = useLibraryByType(type, libraryId)
  const auth = useAuth()
  // Matching unmatched files is for admins (the server refuses members).
  const admin = (auth.user?.isAdmin ?? false) && auth.adminAllowed
  if (isPending) return <p className="text-neutral-400">Loading…</p>
  if (isError) return <p className="text-red-400">{error.message}</p>
  if (!library)
    return (
      <p className="text-neutral-400">
        No {type === 'tv' ? 'TV' : 'movie'} library is set up yet.{' '}
        {admin ? (
          <Link to="/settings/libraries" className="underline">
            Add one in Settings › Libraries
          </Link>
        ) : (
          'An admin can add one in Settings.'
        )}
      </p>
    )
  if (search.edit) return <LibraryEditRows library={library} type={type} search={search} />
  return <LibraryPage key={library.id} library={library} type={type} search={search} admin={admin} />
}

type Library = NonNullable<ReturnType<typeof useLibraryByType>['library']>

function LibraryPage({ library, type, search, admin }: { library: Library; type: 'movie' | 'tv'; search: BrowseSearch; admin: boolean }) {
  const { q } = search
  const [prefs, save] = useBrowsePrefs(library.id)
  const navigate = useNavigate()
  const to = type === 'tv' ? '/tv' : '/movies'
  // A See all names its view; otherwise the tab last chosen here.
  const [tab, setTab] = useState<BrowseTab>(() => (search.view === 'all' ? 'all' : prefs.tab ?? 'overview'))
  // The header's counts: every title, and those left to watch. The first is the grid's own first page when nothing is filtered.
  const everything = useQuery(itemsQuery(library.id, { sort: 'title', filter: '', limit: TAB_PAGE, offset: 0 }))
  const unwatched = useQuery(itemsQuery(library.id, { sort: 'title', filter: 'unwatched', limit: TAB_PAGE, offset: 0 }))
  const totals = everything.data?.totals
  const genres = everything.data?.facets.genres.length ?? 0
  const counts = {
    unwatched: unwatched.data?.total,
    continuing: totals?.continuing,
    ended: totals && type === 'tv' ? Math.max(0, totals.items - totals.continuing) : undefined,
  }
  const needs = admin ? needsMatchWords(library) : undefined
  const title = type === 'tv' ? 'TV Shows' : 'Movies'
  const all = type === 'tv' ? 'All shows' : 'All movies'
  const unmatchedView = admin && search.view === 'unmatched' && !q
  const shown: BrowseTab = q ? 'all' : tab
  const choose = (t: BrowseTab) => {
    setTab(t)
    save({ tab: t })
  }
  const tabs: { id: BrowseTab; label: string; n?: number }[] = [
    { id: 'overview', label: 'Overview' },
    { id: 'all', label: all },
    { id: 'genres', label: 'Genres', n: genres || undefined },
  ]
  const tabRefs = useRef<(HTMLButtonElement | null)[]>([])
  const onTabKey = (e: KeyboardEvent, i: number) => {
    const move = e.key === 'ArrowRight' ? 1 : e.key === 'ArrowLeft' ? -1 : e.key === 'Home' ? -i : e.key === 'End' ? tabs.length - 1 - i : 0
    if (!move) return
    e.preventDefault()
    const next = (i + move + tabs.length) % tabs.length
    choose(tabs[next].id)
    tabRefs.current[next]?.focus()
  }

  return (
    <div>
      <header className="flex flex-wrap items-end gap-x-5 gap-y-2.5 pt-1.5">
        <div className="min-w-0">
          <h1 className="text-[26px] font-bold tracking-[-.02em] md:text-[30px]">{library.name && library.name !== title ? library.name : title}</h1>
          {totals && <p className="mt-1 text-sm text-neutral-300">{libraryCountsLine(type, totals, counts.unwatched)}</p>}
        </div>
        {needs && !unmatchedView && (
          <Link to={to} search={{ library: search.library, view: 'unmatched' }} className="pill pill-warn min-h-8 px-3 text-[13px] md:ml-auto">
            {needs} ›
          </Link>
        )}
      </header>

      {unmatchedView ? (
        <div className="mt-5 space-y-5">
          <Link to={to} search={{ library: search.library }} className="inline-flex items-center gap-1.5 text-sm text-neutral-300 hover:text-accent">
            <ArrowLeftIcon size={15} />
            Back to {title}
          </Link>
          <UnmatchedView libraryId={library.id} type={type} />
        </div>
      ) : (
        <>
          {q ? (
            <p className="mt-4 text-sm text-neutral-300">
              Titles with “{q}” in them.{' '}
              <Link to={to} search={{ library: search.library }} className="text-accent underline-offset-2 hover:underline">
                Show every title
              </Link>
            </p>
          ) : (
            <div role="tablist" aria-label="Views" className="tabs mt-[18px]">
              {tabs.map((t, i) => (
                <button
                  key={t.id}
                  ref={(el) => {
                    tabRefs.current[i] = el
                  }}
                  type="button"
                  role="tab"
                  id={`library-tab-${t.id}`}
                  aria-selected={shown === t.id}
                  aria-controls="library-panel"
                  tabIndex={shown === t.id ? 0 : -1}
                  onClick={() => choose(t.id)}
                  onKeyDown={(e) => onTabKey(e, i)}
                  className="tab"
                >
                  {t.label}
                  {t.n !== undefined && <span className="n">{t.n}</span>}
                </button>
              ))}
            </div>
          )}

          <div id="library-panel" role={q ? undefined : 'tabpanel'} aria-labelledby={q ? undefined : `library-tab-${shown}`} className="mt-4">
            {shown === 'overview' && <Rows libraryId={library.id} type={type} search={search} />}
            {(shown === 'overview' || shown === 'all') && (
              <section className={shown === 'overview' ? 'mt-10' : ''}>
                {shown === 'overview' && (
                  <div className="rowh mb-3.5">
                    <h2>{all}</h2>
                  </div>
                )}
                <BrowseGrid
                  libraryId={library.id}
                  type={type}
                  q={q}
                  initial={{ sort: search.sort, filter: search.filter, genre: search.genre, decade: search.decade }}
                  prefs={prefs}
                  save={save}
                  counts={counts}
                />
              </section>
            )}
            {shown === 'genres' && (
              <BrowseGenres
                libraryId={library.id}
                type={type}
                onGenre={(genre) => {
                  save({ tab: 'all', genre, filter: '', decade: 0 })
                  void navigate({ to, search: { library: search.library, view: 'all', genre } })
                }}
              />
            )}
          </div>
        </>
      )}
    </div>
  )
}

/** The Overview's rows in the person's order, and Edit rows after them, above every title. */
function Rows({ libraryId, type, search }: { libraryId: number; type: 'movie' | 'tv'; search: BrowseSearch }) {
  const rows = useQuery(rowsQuery(libraryId))
  const libraries = useQuery(librariesQuery)
  const navigate = useNavigate()
  if (!rows.data) return null
  return (
    <div className="space-y-8 md:space-y-10">
      <ScreenRows rows={libraryRows(rows.data, type)} libraries={libraries.data?.items ?? []} screenType={type} />
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
