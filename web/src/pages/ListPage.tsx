import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { ApiError } from '../api/client'
import { librariesQuery, mediaListEntriesQuery, mediaListQuery, useDeleteMediaList, useRefreshMediaList } from '../api/queries'
import { requestOptionsQuery } from '../api/requests'
import { Dialog } from '../components/Dialog'
import { Icon, ListEntryCard } from '../components/ListEntryCard'
import { useListView } from '../components/ListHooks'
import { ListFilters, ListMoreMenu, ListProgress } from '../components/ListParts'
import { RequestDialog } from '../components/RequestDialog'
import { WatchlistAction } from '../components/watchlists/WatchlistButton'
import { relativeTime } from '../lib/format'
import { listTally } from '../lib/listProgress'
import { MAX_ENTRIES, canEditList, canRefresh, emptyListWords, isWatchlist, listMeta, notFoundWords, retryWords, screenName, type MediaList, type MediaListEntry } from '../lib/mediaLists'
import type { ListSearch } from '../lib/routeSearch'
import { screenTarget } from '../lib/screenRows'
import { entryTitle } from '../lib/watchlists'
import { useSettingsAccess } from './settings/shared'
import { WatchlistPage } from './WatchlistPage'


/**
 * A list's page, a list row's See all:
 * how much of it the person has and has watched, chips to narrow it, then
 * every title in the list's order. Those in the library are the usual
 * cards; missing ones are dimmed with TMDB's poster and Request where the
 * person may ask for them; imports TMDB couldn't find are Not found. A list
 * holds 250 titles at most, so the page asks for all of them at once and
 * counts from them.
 */
export function ListPage({ listId, search = {} }: { listId: number; search?: ListSearch }) {
  const list = useQuery(mediaListQuery(listId))
  if (list.data && isWatchlist(list.data)) return <WatchlistPage list={list.data} search={search} />
  return <FetchedListPage listId={listId} />
}

function FetchedListPage({ listId }: { listId: number }) {
  const list = useQuery(mediaListQuery(listId))
  const entries = useQuery({ ...mediaListEntriesQuery(listId, 0, MAX_ENTRIES), enabled: list.isSuccess })
  const [requesting, setRequesting] = useState<MediaListEntry | null>(null)
  const view = useListView(entries.data?.items ?? [])

  if (list.isPending) return <p className="text-neutral-400">Loading…</p>
  if (list.isError) {
    const gone = list.error instanceof ApiError && list.error.status === 404
    return (
      <div className="space-y-3">
        <p className={gone ? 'text-neutral-300' : 'text-red-400'}>{gone ? 'This list was deleted, or isn’t yours to see.' : list.error.message}</p>
        <Link to="/" className="text-sm underline">
          Back to Home
        </Link>
      </div>
    )
  }
  const l = list.data
  const e = entries.data
  const tally = listTally(e?.items ?? [])
  return (
    <div className="flex flex-col gap-[22px] pb-12">
      <ListHeader list={l} />
      {l.error && <Failed list={l} />}
      {e && e.items.length > 0 && <ListProgress tally={tally} />}
      {entries.isPending && <p className="text-neutral-400">Loading titles…</p>}
      {entries.isError && <p className="text-red-400">{entries.error.message}</p>}
      {e && e.items.length === 0 && <p className="text-neutral-400">{emptyListWords(l)}</p>}
      {e && e.items.length > 0 && (
        <div className="flex flex-col gap-3">
          <ListFilters tally={tally} value={view.filter} onChange={view.setFilter}>
            <span className="text-[13px] text-neutral-400 md:ml-auto">{view.showing} · in the list’s order</span>
          </ListFilters>
          <RequestNote list={l} />
        </div>
      )}
      {e && view.shown.length > 0 && (
        <ol aria-label={`${l.name}, in order`} className={`grid grid-cols-[repeat(auto-fill,minmax(104px,1fr))] gap-x-3 gap-y-[18px] md:grid-cols-[repeat(auto-fill,minmax(150px,1fr))] md:gap-x-4 md:gap-y-[22px] ${entries.isPlaceholderData ? 'opacity-60' : ''}`}>
          {view.shown.map((entry) => (
            <li key={entry.rank} className="flex min-w-0 flex-col">
              <ListEntryCard entry={entry} rank={entry.rank} onRequest={() => setRequesting(entry)} actions={entry.found && entryTitle(entry) ? <SaveMissing entry={entry} /> : undefined} />
            </li>
          ))}
        </ol>
      )}
      {e && view.of === 0 && e.items.length > 0 && <p className="text-neutral-400">Nothing here under this choice.</p>}
      {view.more > 0 && (
        <div className="flex justify-center pt-2.5">
          <button type="button" onClick={view.showMore} className="btn">
            Show {view.more.toLocaleString()} more
          </button>
        </div>
      )}
      {requesting?.tmdbId && (requesting.mediaType === 'movie' || requesting.mediaType === 'tv') && (
        <RequestDialog mediaType={requesting.mediaType} tmdbId={requesting.tmdbId} onClose={() => setRequesting(null)} />
      )}
    </div>
  )
}

function ListHeader({ list }: { list: MediaList }) {
  const refresh = useRefreshMediaList()
  const libraries = useQuery(librariesQuery)
  const [deleting, setDeleting] = useState(false)
  const libs = libraries.data?.items ?? []
  const meta = listMeta(list)
  const target = screenTarget(list.screen, libs)
  const refreshError = refresh.error instanceof ApiError && refresh.error.status === 409 ? 'It’s being fetched already.' : refresh.error?.message
  const fetched = list.source.kind === 'tmdb' || list.source.kind === 'mdblist'
  return (
    <div>
      <Link
        {...(target.to === '/' ? { to: '/' as const } : { to: target.to, search: { library: target.library } })}
        className="-ml-1 inline-flex min-h-10 items-center gap-1 pr-2 text-[13.5px] text-neutral-400 hover:text-neutral-100"
      >
        <Icon d="m15 18-6-6 6-6" size={15} />
        {screenName(list.screen, libs)}
      </Link>
      <div className="mt-1 flex flex-wrap items-end gap-x-6 gap-y-4">
        <div className="flex min-w-0 flex-[1_1_420px] flex-col gap-2">
          <p className="eyebrow">List · {list.owner === 'everyone' ? 'For everyone' : 'Yours'}</p>
          <h1 className="text-[28px] font-bold leading-[1.1] tracking-[-.02em] md:text-[34px]">{list.name}</h1>
          <p className="meta">
            {meta.href ? (
              <a href={meta.href} target="_blank" rel="noreferrer" className="inline-flex items-center gap-1 text-neutral-300 underline-offset-[3px] hover:text-accent hover:underline">
                {meta.from} ↗<span className="sr-only"> (opens {new URL(meta.href).hostname.replace(/^www\./, '')})</span>
              </a>
            ) : (
              <span>{meta.from}</span>
            )}
            <span>{meta.updated}</span>
            <span>{meta.type}</span>
            {!!list.notFound && <span>{notFoundWords(list.notFound)}</span>}
          </p>
          {refreshError && (
            <p role="alert" className="text-sm text-warn">
              {refreshError}
            </p>
          )}
        </div>
        {list.canEdit && (
          <div className="flex flex-wrap items-center gap-2">
            {fetched && (
              <button type="button" disabled={!canRefresh(list) || refresh.isPending} onClick={() => refresh.mutate(list.id)} className="btn">
                <Icon d="M4 12a8 8 0 0 1 14-5.3L20 9M20 4v5h-5M20 12a8 8 0 0 1-14 5.3L4 15M4 20v-5h5" />
                {list.fetching || refresh.isPending ? 'Refreshing…' : 'Refresh'}
              </button>
            )}
            {canEditList(list) && (
              <Link to="/lists/$listId/edit" params={{ listId: String(list.id) }} className="btn">
                Edit
              </Link>
            )}
            <ListMoreMenu label={`More for ${list.name}`}>
              {(close) => (
                <button
                  type="button"
                  role="menuitem"
                  onClick={() => {
                    close()
                    setDeleting(true)
                  }}
                  className="menu-item text-red-400 hover:text-red-300"
                >
                  Delete list…
                </button>
              )}
            </ListMoreMenu>
          </div>
        )}
      </div>
      {deleting && <DeleteListDialog list={list} onClose={() => setDeleting(false)} />}
    </div>
  )
}

/** A list whose last refresh failed keeps its titles and says why. */
function Failed({ list }: { list: MediaList }) {
  const { admin } = useSettingsAccess()
  const key = list.source.kind === 'mdblist' ? ({ to: '/settings/lists', label: 'Settings › Lists' } as const) : ({ to: '/settings/general', label: 'Settings › General' } as const)
  return (
    <div role="status" className="flex flex-wrap items-start gap-3 rounded-[10px] border border-orange-900 bg-orange-950/25 px-4 py-3.5 text-sm leading-normal">
      <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" className="mt-0.5 shrink-0 text-warn">
        <path d="M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z" />
        <path d="M12 9v4" />
        <path d="M12 17h.01" />
      </svg>
      <span className="min-w-64 flex-1">
        <span className="block font-semibold text-neutral-100">
          Couldn’t refresh: {list.error}
          {list.fetchedAt && ` · last worked ${relativeTime(list.fetchedAt)}`}
        </span>
        <span className="text-neutral-300">
          {list.fetchedAt ? 'These are the titles from the last refresh that worked.' : 'It hasn’t worked yet.'} {retryWords(list.nextFetchAt)}
        </span>
        {admin && (
          <>
            {' '}
            <Link to={key.to} className="text-neutral-100 underline">
              Check the key in {key.label}
            </Link>
          </>
        )}
      </span>
    </div>
  )
}

/** What Request does on this list, or why it isn't offered. */
function RequestNote({ list }: { list: MediaList }) {
  const options = useQuery(requestOptionsQuery)
  const { admin } = useSettingsAccess()
  if (list.source.kind === 'library' || !options.data) return null
  const requests = !!(options.data.movies || options.data.shows)
  const imported = list.source.kind === 'import'
  return (
    <p className="max-w-[76ch] text-[13px] text-neutral-400">
      {imported
        ? 'Titles TMDB couldn’t find stay on the list as Not found.'
        : requests
          ? 'Request sends a missing title to the household’s requests, as Search does. It joins this list’s row when it arrives; until then the row shows only what your library has.'
          : 'Requests aren’t set up on this server.'}
      {!imported && !requests && admin && (
        <>
          {' '}
          <Link to="/settings/integrations" className="text-neutral-300 underline">
            Connect Radarr or Seerr in Settings › Integrations
          </Link>
        </>
      )}
    </p>
  )
}

function DeleteListDialog({ list, onClose }: { list: MediaList; onClose: () => void }) {
  const del = useDeleteMediaList()
  const navigate = useNavigate()
  const libraries = useQuery(librariesQuery)
  const back = () => {
    const t = screenTarget(list.screen, libraries.data?.items ?? [])
    void navigate(t.to === '/' ? { to: '/' } : { to: t.to, search: { library: t.library } })
  }
  return (
    <Dialog title={`Delete ${list.name}?`} onClose={onClose} width="max-w-md">
      <div className="px-6 pb-6 pt-2">
        <p className="text-sm leading-normal text-neutral-400">
          {list.owner === 'everyone'
            ? 'Its row goes from Home and every other screen, for everyone in the household. This can’t be undone.'
            : 'Its row goes from Home and every other screen. This can’t be undone.'}
        </p>
        {del.isError && (
          <p role="alert" className="mt-3 text-sm text-warn">
            {del.error.message}
          </p>
        )}
        <div className="mt-6 flex flex-wrap justify-end gap-3">
          <button type="button" onClick={onClose} className="btn">
            Cancel
          </button>
          <button type="button" disabled={del.isPending} onClick={() => del.mutate(list.id, { onSuccess: back })} className="btn btn-primary">
            {del.isPending ? 'Deleting…' : 'Delete list'}
          </button>
        </div>
      </div>
    </Dialog>
  )
}

/** Add to Watchlist on a title the library doesn't have, with what the list knows of it. */
function SaveMissing({ entry }: { entry: MediaListEntry }) {
  const t = entryTitle(entry)
  if (!t || !entry.found) return null
  return <WatchlistAction target={{ ...t, title: entry.title, year: entry.year, posterPath: entry.posterPath }} title={entry.title} watchlisted={entry.watchlisted} />
}
