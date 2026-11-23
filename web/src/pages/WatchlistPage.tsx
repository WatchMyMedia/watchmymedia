import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { mediaListEntriesQuery } from '../api/queries'
import { holdRemoval, isHeld, useDeleteWatchlist, useHeldRemovals, useUpdateWatchlist, watchlistsQuery } from '../api/watchlists'
import { Dialog } from '../components/Dialog'
import { Icon, ListEntryCard } from '../components/ListEntryCard'
import { ListProgress } from '../components/ListParts'
import { Pager } from '../components/Pager'
import { RequestDialog } from '../components/RequestDialog'
import { BookmarkIcon } from '../components/watchlists/WatchlistPicker'
import { WatchlistNameDialog } from '../components/watchlists/WatchlistNameDialog'
import { showWatchlistFailure } from '../components/watchlists/useWatchlistToggle'
import { listTally } from '../lib/listProgress'
import { MAX_ENTRIES, type MediaList, type MediaListEntry } from '../lib/mediaLists'
import type { ListSearch } from '../lib/routeSearch'
import { showToast } from '../lib/toast'
import {
  deleteWords, emptyFilterWords, emptyWatchlist, entryTitle, failureWords, removedMessage, switchWords, watchlistCounts, watchlistFilters, watchlistSorts, withoutHeld, type WatchlistSort, type WatchlistType,
} from '../lib/watchlists'

// A page holds as many titles as the server sends at once, so most watchlists are one page and their counts are exact.
const PAGE = MAX_ENTRIES

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

  // Watched is counted from the titles on the page: only when the page holds them all.
  const whole = !!shown && !search.type && shown.items.length >= shown.total && shown.total > 0
  return (
    <div className="flex flex-col gap-[22px] pb-12">
      <Header list={list} total={total} inLibrary={inLibrary} />
      {whole && <ListProgress tally={listTally(shown.items)} />}
      <RemoveSwitch list={list} />
      {total === 0 && !entries.isPending ? (
        <Empty />
      ) : (
        <>
          <div className="flex flex-wrap items-center gap-x-4 gap-y-2.5">
            <div role="group" aria-label="Show" className="flex flex-wrap gap-2">
              {watchlistFilters.map((f) => (
                <button key={f.label} type="button" aria-pressed={search.type === f.value} onClick={() => choose({ type: f.value })} className="chip max-md:min-h-10">
                  {f.label}
                </button>
              ))}
            </div>
            <label className="ml-auto flex items-center gap-2 text-[13px] text-neutral-400">
              Sort
              <select
                value={search.sort ?? 'added'}
                onChange={(e) => choose({ sort: e.target.value === 'added' ? undefined : (e.target.value as WatchlistSort) })}
                className="min-h-[34px] rounded-lg border border-line-2 bg-surface pl-3 pr-2 text-[13px] font-semibold text-neutral-100 max-md:min-h-10"
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
            <ul aria-label={`${list.name}, ${sortWords(search.sort)}`} className={`grid grid-cols-[repeat(auto-fill,minmax(104px,1fr))] gap-x-3 gap-y-[18px] md:grid-cols-[repeat(auto-fill,minmax(150px,1fr))] md:gap-x-4 md:gap-y-[22px] ${entries.isPlaceholderData ? 'opacity-60' : ''}`}>
              {shown.items.map((entry) => (
                <li key={`${entry.mediaType}-${entry.tmdbId ?? entry.rank}`} className="group relative flex min-w-0 flex-col">
                  <ListEntryCard entry={entry} onRequest={() => setRequesting(entry)} />
                  {entryTitle(entry) && (
                    <button
                      type="button"
                      onClick={() => remove(entry)}
                      aria-label={`Remove ${entry.item?.title ?? entry.title} from ${list.name}`}
                      className="absolute right-2 top-2 z-20 inline-flex min-h-7 items-center gap-1 rounded-md border border-line-3 bg-ground/90 pl-1.5 pr-2 text-xs font-semibold opacity-0 hover:border-accent hover:text-accent focus-visible:opacity-100 group-hover:opacity-100 max-md:min-h-9 max-md:opacity-100"
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
      <Link to="/watchlists" className="-ml-1 inline-flex min-h-10 items-center gap-1 pr-2 text-[13.5px] text-neutral-400 hover:text-neutral-100">
        <Icon d="m15 18-6-6 6-6" size={15} />
        My lists
      </Link>
      <div className="mt-1 flex flex-wrap items-end gap-x-6 gap-y-4">
        <div className="flex min-w-0 flex-[1_1_16rem] flex-col gap-2">
          <p className="eyebrow">Watchlist</p>
          <h1 className="break-words text-[28px] font-bold leading-[1.1] tracking-[-.02em] md:text-[34px]">{list.name}</h1>
          {total > 0 && <p className="text-sm text-neutral-300">{watchlistCounts({ titles: total, inLibrary })}</p>}
        </div>
        <div className="flex flex-wrap gap-2">
          <button type="button" onClick={() => setActing('rename')} className="btn">
            Rename
          </button>
          <button type="button" onClick={() => setActing('delete')} className="btn text-red-400 hover:text-red-300">
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
    <div className="panel flex max-w-[46rem] items-start gap-3.5 px-4 py-3">
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
          <button type="button" onClick={onClose} className="btn max-sm:flex-1">
            Cancel
          </button>
          <button type="button" disabled={del.isPending} onClick={() => del.mutate(list.id, { onSuccess: () => void navigate({ to: '/watchlists' }) })} className="btn btn-primary max-sm:flex-1">
            {del.isPending ? 'Deleting…' : 'Delete'}
          </button>
        </div>
      </div>
    </Dialog>
  )
}

function Empty() {
  return (
    <div className="flex flex-col items-center gap-2 rounded-xl border border-dashed border-line-3 px-6 py-16 text-center">
      <BookmarkIcon size={32} className="text-neutral-400" />
      <h2 className="mt-2 text-xl font-semibold">{emptyWatchlist.title}</h2>
      <p className="max-w-[28rem] text-sm leading-normal text-neutral-400">{emptyWatchlist.detail}</p>
    </div>
  )
}
