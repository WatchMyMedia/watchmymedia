import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { libraryCollectionQuery, libraryCollectionsQuery } from '../api/queries'
import { requestOptionsQuery, type Requestable } from '../api/requests'
import { collectionHave, collectionLine, yearsWords } from '../lib/collections'
import { requestableLine, requestableState } from '../lib/searchPage'
import { LibraryCard } from './LibraryCard'
import { Poster } from './Poster'
import { RequestDialog } from './RequestDialog'
import { ArrowLeftIcon, PlusIcon } from './icons'

/**
 * A movie library's Collections tab: each TMDB collection it has two films of
 * or more, and, opened, the library's films of it with those it lacks set
 * apart, to request. The open collection is in the address.
 */
export function BrowseCollections({ libraryId, open, onOpen, onBack }: { libraryId: number; open?: number; onOpen: (id: number) => void; onBack: () => void }) {
  const list = useQuery(libraryCollectionsQuery(libraryId))
  if (open) return <CollectionView libraryId={libraryId} id={open} onBack={onBack} />
  if (list.isPending) return <p className="text-neutral-400">Loading…</p>
  if (list.isError) return <p className="text-red-400">{list.error.message}</p>
  if (!list.data.items.length) return <p className="text-neutral-400">No collections yet. One shows here once the library has two of its films.</p>
  return (
    <ul className="grid grid-cols-[repeat(auto-fill,minmax(min(120px,42%),1fr))] gap-x-3 gap-y-[18px] md:grid-cols-[repeat(auto-fill,minmax(min(168px,42%),1fr))] md:gap-x-4 md:gap-y-[22px]">
      {list.data.items.map((c) => (
        <li key={c.id} className="min-w-0">
          <button type="button" onClick={() => onOpen(c.id)} aria-label={`${c.name}, ${collectionLine(c)}`} className="group block w-full text-left">
            <span className="block rounded-lg ring-1 ring-inset ring-white/[.07] group-hover:ring-2 group-hover:ring-accent">
              <Poster path={c.posterPath} alt={c.name} />
            </span>
            <span className="mt-2 block truncate text-sm font-medium group-hover:text-accent">{c.name}</span>
            <span className="block truncate text-[12.5px] text-neutral-400">{collectionLine(c)}</span>
          </button>
        </li>
      ))}
    </ul>
  )
}

function CollectionView({ libraryId, id, onBack }: { libraryId: number; id: number; onBack: () => void }) {
  const c = useQuery(libraryCollectionQuery(libraryId, id))
  const options = useQuery(requestOptionsQuery)
  const [asking, setAsking] = useState<Requestable | null>(null)
  const back = (
    <button type="button" onClick={onBack} className="inline-flex min-h-9 items-center gap-1.5 text-sm text-neutral-300 hover:text-accent">
      <ArrowLeftIcon size={15} />
      All collections
    </button>
  )
  if (c.isPending)
    return (
      <div>
        {back}
        <p className="mt-4 text-neutral-400">Loading…</p>
      </div>
    )
  if (c.isError)
    return (
      <div>
        {back}
        <p className="mt-4 text-red-400">{c.error.message}</p>
      </div>
    )
  const d = c.data
  const canRequest = !!options.data?.movies
  return (
    <section aria-labelledby="collection-head">
      {back}
      <div className="mt-3">
        <h2 id="collection-head" className="text-[22px] font-bold tracking-[-.01em]">
          {d.name}
        </h2>
        <p className="mt-1 text-sm text-neutral-300">{[collectionHave(d), yearsWords(d.firstYear, d.lastYear)].filter(Boolean).join(' · ')}</p>
      </div>
      <ul className="mt-4 grid grid-cols-[repeat(auto-fill,minmax(min(104px,42%),1fr))] gap-x-3 gap-y-[18px] md:grid-cols-[repeat(auto-fill,minmax(min(152px,42%),1fr))] md:gap-x-4 md:gap-y-[22px]">
        {d.movies.map((m) => (
          <li key={`${m.kind}-${m.id}`} className="min-w-0">
            <LibraryCard item={m} />
          </li>
        ))}
      </ul>
      {d.missing.length > 0 && (
        <section aria-labelledby="collection-missing" className="mt-10 border-t border-line pt-7">
          <div className="rowh">
            <h3 id="collection-missing">Not in your library</h3>
            <span className="note">{d.missing.length.toLocaleString()} more in the collection</span>
          </div>
          <ul className="mt-3.5 grid grid-cols-[repeat(auto-fill,minmax(min(150px,42%),1fr))] gap-x-4 gap-y-5 md:grid-cols-[repeat(auto-fill,168px)]">
            {d.missing.map((r) => {
              const state = requestableState(r)
              return (
                <li key={r.tmdbId} className="min-w-0">
                  <span aria-hidden="true" className="block opacity-45">
                    <Poster path={r.posterPath} alt="" />
                  </span>
                  <span className="mt-2 block truncate text-sm font-medium text-neutral-300">{r.title}</span>
                  <span className="block truncate text-[12.5px] text-neutral-400">{requestableLine(r)}</span>
                  {state ? (
                    <span className={`pill mt-2.5 ${state.gold ? 'pill-gold' : ''}`}>{state.label}</span>
                  ) : (
                    canRequest && (
                      <button type="button" onClick={() => setAsking(r)} aria-label={`Request ${r.title}`} className="btn btn-sm mt-2.5 w-full max-md:min-h-11">
                        <PlusIcon size={14} />
                        Request
                      </button>
                    )
                  )}
                </li>
              )
            })}
          </ul>
        </section>
      )}
      {asking && <RequestDialog mediaType="movie" tmdbId={asking.tmdbId} onClose={() => setAsking(null)} />}
    </section>
  )
}
