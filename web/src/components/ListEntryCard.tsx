import type { ReactNode } from 'react'
import { movieLine } from '../lib/cardFacts'
import { entryView, type MediaListEntry } from '../lib/mediaLists'
import { PlusIcon } from './icons'
import { Poster } from './Poster'
import { PosterCard, RankBadge } from './PosterCard'

const pills: Record<string, { label: string; gold?: boolean }> = { requested: { label: 'Requested' }, downloading: { label: 'Downloading', gold: true }, available: { label: 'Available' } }

/**
 * A title on a list's page: the library's card, saying its year and length;
 * a dimmed missing one with Request or where its request stands; or Not
 * found. rank puts its place on the picture (a watchlist's titles have
 * none); actions follow Request under a missing title.
 */
export function ListEntryCard({ entry, rank, onRequest, actions }: { entry: MediaListEntry; rank?: number; onRequest: () => void; actions?: ReactNode }) {
  const view = entryView(entry)
  if (view.kind === 'library') {
    if (!entry.item) return null
    const line = entry.item.kind === 'movie' ? movieLine(entry.item) : entry.item.year ? String(entry.item.year) : ''
    return <PosterCard item={entry.item} rank={rank} subtitle={line} />
  }
  if (view.kind === 'notFound')
    return (
      <>
        <span className="relative flex aspect-[2/3] w-full flex-col items-center justify-center gap-2 rounded-lg border border-dashed border-line-3 p-3 text-center text-[12.5px] leading-snug text-neutral-400">
          {rank !== undefined && <RankBadge rank={rank} />}
          <b className="text-[13px] font-semibold text-neutral-300">Not found</b>
          This title is no longer on TMDB, so it can’t be matched or requested.
        </span>
        <span className="mt-2 block truncate text-sm font-medium text-neutral-300">{entry.title}</span>
        <span className="block truncate text-[12.5px] text-neutral-400">Left out of the row</span>
      </>
    )
  const pill = entry.requestState ? pills[entry.requestState] : undefined
  return (
    <>
      <span className="relative block">
        {rank !== undefined && <RankBadge rank={rank} />}
        <span className="block opacity-[.42]">
          <Poster path={entry.posterPath} alt={entry.title} />
        </span>
      </span>
      <span className="mt-2 block truncate text-sm font-medium text-neutral-300">{entry.title}</span>
      <span className="block truncate text-[12.5px] text-neutral-400">{[entry.year || '', 'Not in your library'].filter(Boolean).join(' · ')}</span>
      {(view.request || pill || actions) && (
        <span className="mt-2 flex flex-wrap items-center gap-1.5">
          {view.request ? (
            <button type="button" onClick={onRequest} aria-label={`Request ${entry.title}`} className="btn btn-sm flex-1 max-md:min-h-11">
              <PlusIcon size={14} />
              Request
            </button>
          ) : (
            pill && <span className={`pill min-h-8 ${pill.gold ? 'pill-gold' : ''}`}>{pill.label}</span>
          )}
          {actions}
        </span>
      )}
    </>
  )
}

export function Icon({ d, size = 16 }: { d: string; size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d={d} />
    </svg>
  )
}
