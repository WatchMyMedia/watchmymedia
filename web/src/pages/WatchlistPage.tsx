import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { mediaListEntriesQuery } from '../api/queries'
import { holdRemoval, isHeld, useDeleteWatchlist, useHeldRemovals, useUpdateWatchlist, watchlistsQuery } from '../api/watchlists'
import { Dialog } from '../components/Dialog'
import { Icon, ListEntryCard } from '../components/ListEntryCard'
import { Pager } from '../components/Pager'
import { RequestDialog } from '../components/RequestDialog'
import { BookmarkIcon } from '../components/watchlists/WatchlistPicker'
import { WatchlistNameDialog } from '../components/watchlists/WatchlistNameDialog'
import { showWatchlistFailure } from '../components/watchlists/useWatchlistToggle'
import type { MediaList, MediaListEntry } from '../lib/mediaLists'
import type { ListSearch } from '../lib/routeSearch'
import { showToast } from '../lib/toast'
import {
  deleteWords, emptyFilterWords, emptyWatchlist, entryTitle, failureWords, removedMessage, switchWords, watchlistCounts, watchlistFilters, watchlistSorts, withoutHeld, type WatchlistSort, type WatchlistType,
} from '../lib/watchlists'

const PAGE = 100
const b1 = 'inline-flex min-h-11 items-center justify-center rounded-md btn-primary px-5 text-sm font-semibold disabled:opacity-50 max-sm:flex-1'
const b2 = 'inline-flex min-h-11 items-center justify-center gap-2 rounded-md border border-line-2 bg-surface px-4 text-sm font-medium hover:bg-surface-2 disabled:opacity-50'

/**
 * A watchlist's page, its row's See all: every title on it, newest saved
 * first, the ones the library doesn't have dimmed with Request. Each title
 * has Remove, with Undo in the message; the watchlist has Rename, Delete
 * and the switch that keeps watched titles. From a Movies or TV screen it
 * opens on that type.
 */
export function WatchlistPage({ list, search }: { list: MediaList; search: ListSearch }) {
  const qc = useQueryClient()
  const navigate = useNavigate()
  const [offset, setOffset] = useState(0)
  const view = { sort: search.sort, mediaType: search.type }
  const entries = useQuery(mediaListEntriesQuery(list.id, offset, PAGE, view))
  const [requesting, setRequesting] = useState<MediaListEntry | null>(null)
  useHeldRemovals()
  const choose = (next: ListSearch) => {
    setOffset(0)
    void navigate({ to: '/lists/$listId', params: { listId: String(list.id) }, search: { ...search, ...next }, replace: true })
  }
  const page = entries.data
  const shown = page && withoutHeld(page, (t) => isHeld(t, list.id))
  // What the whole watchlist holds, less what waits for Undo.
  const total = Math.max(0, list.entries - (page && shown ? page.total - shown.total : 0))
  const inLibrary = Math.max(0, list.inLibrary - (page && shown ? page.inLibrary - shown.inLibrary : 0))
  const remove = (e: MediaListEntry) => {
    const t = entryTitle(e)
    if (!t) return
    const h = holdRemoval(qc, t, list.id, (error) => showWatchlistFailure(error, { name: list.name, remove: true }))
    showToast({ text: removedMessage(list.name), icon: 'removed', undo: h.undo, done: () => void h.commit() })
  }

  return (
    <div className="flex flex-col gap-6 pb-16">
      <Header list={list} total={total} inLibrary={inLibrary} />
      <RemoveSwitch list={list} />
      {total === 0 && !entries.isPending ? (
        <Empty />
      ) : (
        <>
          <div className="flex flex-wrap items-center gap-3">
            <div role="group" aria-label="Show" className="flex flex-wrap gap-2">
              {watchlistFilters.map((f) => (
                <button
                  key={f.label}
                  type="button"
                  aria-pressed={search.type === f.value}
                  onClick={() => choose({ type: f.value })}
                  className={`min-h-9 rounded-full border px-3 text-sm max-sm:min-h-11 max-sm:px-3.5 ${search.type === f.value ? 'border-accent text-neutral-100' : 'border-line-2 text-neutral-400 hover:text-neutral-200'}`}
                >
                  {f.label}
                </button>
              ))}
            </div>
            <label className="ml-auto flex items-center gap-2 text-sm text-neutral-400">
              Sort
              <select
                value={search.sort ?? 'added'}
                onChange={(e) => choose({ sort: e.target.value === 'added' ? undefined : (e.target.value as WatchlistSort) })}
                className="min-h-9 rounded-md border border-line-2 bg-surface px-2 text-sm text-neutral-100 max-sm:min-h-11"
              >
                {watchlistSorts.map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </select>
            </label>
          </div>
          {entries.isPending && <p className="text-neutral-400">Loading titles…</p>}
          {entries.isError && <p className="text-red-400">{entries.error.message}</p>}
          {shown && shown.items.length === 0 && search.type && <p className="text-neutral-400">{emptyFilterWords(search.type as WatchlistType)}</p>}
          {shown && shown.items.length > 0 && (
            <ul aria-label={`${list.name}, ${sortWords(search.sort)}`} className={`grid grid-cols-2 gap-x-4 gap-y-5 sm:grid-cols-[repeat(auto-fill,minmax(150px,1fr))] ${entries.isPlaceholderData ? 'opacity-60' : ''}`}>
              {shown.items.map((entry) => (
                <li key={`${entry.mediaType}-${entry.tmdbId ?? entry.rank}`} className="group relative flex min-w-0 flex-col">
                  <ListEntryCard entry={entry} onRequest={() => setRequesting(entry)} />
                  {entryTitle(entry) && (
                    <button
                      type="button"
                      onClick={() => remove(entry)}
                      aria-label={`Remove ${entry.item?.title ?? entry.title} from ${list.name}`}
                      className="absolute right-1.5 top-1.5 z-20 inline-flex min-h-7 items-center gap-1 rounded-md border border-neutral-600 bg-ground/90 pl-1.5 pr-2 text-xs font-semibold opacity-0 hover:border-accent hover:text-accent focus-visible:opacity-100 group-hover:opacity-100 max-sm:min-h-9 max-sm:opacity-100"
                    >
                      <Icon d="M6 6l12 12M18 6 6 18" size={12} />
                      Remove
                    </button>
                  )}
                </li>
              ))}
            </ul>
          )}
          {page && <Pager offset={offset} limit={PAGE} total={page.total} onChange={setOffset} />}
        </>
      )}
      {requesting?.tmdbId && (requesting.mediaType === 'movie' || requesting.mediaType === 'tv') && (
        <RequestDialog mediaType={requesting.mediaType} tmdbId={requesting.tmdbId} onClose={() => setRequesting(null)} />
      )}
    </div>
  )
}

/** The grid's name to a screen reader: which order it is in. */
function sortWords(sort: WatchlistSort | undefined): string {
  return sort === 'title' ? 'by title' : sort === 'released' ? 'newest released first' : 'newest saved first'
}

function Header({ list, total, inLibrary }: { list: MediaList; total: number; inLibrary: number }) {
  const lists = useQuery(watchlistsQuery)
  const update = useUpdateWatchlist()
  const [acting, setActing] = useState<'rename' | 'delete' | null>(null)
  return (
    <div>
      <Link to="/watchlists" className="-ml-1 inline-flex min-h-[44px] items-center gap-1 pr-2 text-sm text-neutral-300 hover:text-neutral-100">
        <Icon d="m15 18-6-6 6-6" />
        Watchlists
      </Link>
      <div className="mt-1 flex flex-wrap items-end justify-between gap-x-6 gap-y-4">
        <div className="min-w-0 flex-[1_1_16rem]">
          <p className="text-xs font-semibold uppercase tracking-[.06em] text-accent">Watchlist</p>
          <h1 className="mt-1.5 break-words text-[30px] font-bold leading-tight">{list.name}</h1>
          {total > 0 && <p className="mt-2 text-sm text-neutral-300">{watchlistCounts({ titles: total, inLibrary })}</p>}
        </div>
        <div className="flex flex-wrap gap-2">
          <button type="button" onClick={() => setActing('rename')} className={b2}>
            <Icon d="M4 20h4L19 9l-4-4L4 16v4zM14 6l4 4" />
            Rename
          </button>
          <button type="button" onClick={() => setActing('delete')} className={`${b2} text-red-300 hover:text-red-200`}>
            <Icon d="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3" />
            Delete
          </button>
        </div>
      </div>
      {acting === 'rename' && (
        <WatchlistNameDialog
          heading="Rename watchlist"
          confirmLabel="Rename"
          lists={lists.data?.items ?? []}
          self={list}
          busy={update.isPending}
          error={update.error}
          onSubmit={(name) => update.mutate({ id: list.id, patch: { name } }, { onSuccess: () => setActing(null) })}
          onClose={() => {
            update.reset()
            setActing(null)
          }}
        />
      )}
      {acting === 'delete' && <DeleteDialog list={list} inLibrary={inLibrary} onClose={() => setActing(null)} />}
    </div>
  )
}

/** Remove titles once watched: saves at once, and says so in its row when refused. */
function RemoveSwitch({ list }: { list: MediaList }) {
  const update = useUpdateWatchlist()
  const on = !!list.removeWatched
  return (
    <div className="flex max-w-[46rem] items-start gap-3.5 rounded-[10px] border border-line bg-surface px-4 py-3">
      <button
        type="button"
        role="switch"
        aria-checked={on}
        aria-labelledby={`switch-${list.id}`}
        aria-describedby={`switch-${list.id}-detail`}
        onClick={() => update.mutate({ id: list.id, patch: { removeWatched: !on } })}
        className={`relative my-0.5 h-8 w-[52px] shrink-0 rounded-full ${on ? 'bg-accent' : 'bg-line-2 ring-1 ring-inset ring-neutral-700'}`}
      >
        <span className={`absolute top-1 h-6 w-6 rounded-full ${on ? 'left-6 bg-ground' : 'left-1 bg-neutral-400'}`} />
      </button>
      <div className="min-w-0">
        <p id={`switch-${list.id}`} className="text-sm font-semibold leading-snug">
          {switchWords.label}
        </p>
        <p id={`switch-${list.id}-detail`} className="mt-0.5 text-[13px] leading-normal text-neutral-400">
          {switchWords.detail}
        </p>
        {update.isError && (
          <p role="alert" className="mt-1.5 text-[13px] text-warn">
            {failureWords(update.error, 'Couldn’t change it. Try again.')}
          </p>
        )}
      </div>
    </div>
  )
}

function DeleteDialog({ list, inLibrary, onClose }: { list: MediaList; inLibrary: number; onClose: () => void }) {
  const del = useDeleteWatchlist()
  const navigate = useNavigate()
  const words = deleteWords({ name: list.name, inLibrary })
  return (
    <Dialog title={words.title} onClose={onClose} width="max-w-md">
      <div className="px-6 pb-6 pt-2">
        <p className="text-sm leading-normal text-neutral-400">{words.body}</p>
        {del.isError && (
          <p role="alert" className="mt-3 text-sm text-warn">
            {failureWords(del.error, 'Couldn’t delete it. Try again.')}
          </p>
        )}
        <div className="mt-6 flex flex-wrap justify-end gap-3">
          <button type="button" onClick={onClose} className={`${b2} max-sm:flex-1`}>
            Cancel
          </button>
          <button type="button" disabled={del.isPending} onClick={() => del.mutate(list.id, { onSuccess: () => void navigate({ to: '/watchlists' }) })} className={b1}>
            {del.isPending ? 'Deleting…' : 'Delete'}
          </button>
        </div>
      </div>
    </Dialog>
  )
}

function Empty() {
  return (
    <div className="flex flex-col items-center gap-2 rounded-xl border border-dashed border-neutral-700 px-6 py-16 text-center">
      <BookmarkIcon size={32} className="text-neutral-500" />
      <h2 className="mt-2 text-xl font-semibold">{emptyWatchlist.title}</h2>
      <p className="max-w-[28rem] text-sm leading-normal text-neutral-400">{emptyWatchlist.detail}</p>
    </div>
  )
}
