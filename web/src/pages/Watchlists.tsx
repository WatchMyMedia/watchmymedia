import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { useCreateWatchlist, watchlistsQuery } from '../api/watchlists'
import { Poster } from '../components/Poster'
import { PlusIcon } from '../components/icons'
import { BookmarkIcon } from '../components/watchlists/WatchlistPicker'
import { WatchlistNameDialog } from '../components/watchlists/WatchlistNameDialog'
import { emptyWatchlists, keepsWatchedWords, limitWords, watchlistCounts, type Watchlist } from '../lib/watchlists'

/**
 * The person's watchlists in the order they made them, each with its
 * first posters, what it holds and whether it keeps watched titles, and
 * New watchlist. Reached from the account menu, so a watchlist whose row
 * is empty or hidden is still a press away.
 */
export function Watchlists() {
  const lists = useQuery(watchlistsQuery)
  const create = useCreateWatchlist()
  const [naming, setNaming] = useState(false)
  const items = lists.data?.items ?? []
  const atLimit = !!lists.data && items.length >= lists.data.max
  return (
    <div className="flex flex-col gap-6 pb-12 pt-2">
      <div className="flex flex-wrap items-end gap-x-5 gap-y-3">
        <div className="flex min-w-0 flex-1 flex-col gap-2">
          <p className="eyebrow">My lists</p>
          <h1 className="text-[28px] font-bold leading-[1.1] tracking-[-.02em] md:text-[34px]">Watchlists</h1>
        </div>
        <button type="button" disabled={!lists.data || atLimit} onClick={() => setNaming(true)} className="btn">
          <PlusIcon size={16} />
          New watchlist
        </button>
        {atLimit && <p className="basis-full text-[13px] text-neutral-400">{limitWords(lists.data?.max)}</p>}
      </div>
      {lists.isPending && <p className="text-neutral-400">Loading…</p>}
      {lists.isError && <p className="text-red-400">{lists.error.message}</p>}
      {lists.data && items.length === 0 && (
        <div className="flex flex-col items-center gap-2 rounded-xl border border-dashed border-line-3 px-6 py-16 text-center">
          <BookmarkIcon size={32} className="text-neutral-400" />
          <h2 className="mt-2 text-xl font-semibold">{emptyWatchlists.title}</h2>
          <p className="max-w-[28rem] text-sm leading-normal text-neutral-400">{emptyWatchlists.detail}</p>
        </div>
      )}
      {items.length > 0 && (
        <ul aria-label="Your watchlists, in the order you made them" className="grid grid-cols-[repeat(auto-fill,minmax(min(100%,20rem),1fr))] gap-5">
          {items.map((w) => (
            <li key={w.id}>
              <WatchlistCard watchlist={w} />
            </li>
          ))}
        </ul>
      )}
      {naming && (
        <WatchlistNameDialog
          heading="New watchlist"
          confirmLabel="Create"
          lists={items}
          busy={create.isPending}
          error={create.error}
          onSubmit={(name) => create.mutate(name, { onSuccess: () => setNaming(false) })}
          onClose={() => {
            create.reset()
            setNaming(false)
          }}
        />
      )}
    </div>
  )
}

function WatchlistCard({ watchlist: w }: { watchlist: Watchlist }) {
  return (
    <Link to="/lists/$listId" params={{ listId: String(w.id) }} className="group panel flex h-full flex-col p-4 hover:border-accent">
      <span aria-hidden="true" className="grid grid-cols-4 gap-2">
        {[0, 1, 2, 3].map((i) => (w.posters[i] ? <Poster key={i} path={w.posters[i]} size="w185" alt="" className="rounded-md" /> : <span key={i} className="block aspect-[2/3] rounded-md bg-surface-2" />))}
      </span>
      <h2 className="mt-3.5 text-lg font-semibold leading-snug group-hover:text-accent">{w.name}</h2>
      <span className="mt-0.5 text-sm text-neutral-400">{watchlistCounts(w)}</span>
      {!w.removeWatched && (
        <span className="pill pill-line mt-2.5 self-start font-medium text-neutral-400">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <path d="m5 12 5 5 9-10" />
          </svg>
          {keepsWatchedWords}
        </span>
      )}
    </Link>
  )
}
