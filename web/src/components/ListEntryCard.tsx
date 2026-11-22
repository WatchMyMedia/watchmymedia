import type { ReactNode } from 'react'
import { entryView, type MediaListEntry } from '../lib/mediaLists'
import { Poster } from './Poster'
import { PosterCard, RankBadge } from './PosterCard'

const pills: Record<string, { label: string; gold?: boolean }> = { requested: { label: 'Requested' }, downloading: { label: 'Downloading', gold: true }, available: { label: 'Available' } }

/**
 * A title on a list's page: the library's card, a dimmed missing one with
 * Request or where its request stands, or Not found. rank puts its place
 * on the picture (a watchlist's titles have none); actions follow Request
 * under a missing title.
 */
export function ListEntryCard({ entry, rank, onRequest, actions }: { entry: MediaListEntry; rank?: number; onRequest: () => void; actions?: ReactNode }) {
  const view = entryView(entry)
  if (view.kind === 'library') return entry.item ? <PosterCard item={entry.item} rank={rank} subtitle={entry.item.year ? String(entry.item.year) : ''} /> : null
  if (view.kind === 'notFound')
    return (
      <>
        <span className="relative flex aspect-[2/3] w-full items-center justify-center rounded-md border border-dashed border-neutral-600 bg-ground p-2.5 text-center text-xs leading-snug text-neutral-400">
          {rank !== undefined && <RankBadge rank={rank} />}
          {entry.title}
        </span>
        <span className="mt-1.5 block truncate text-sm font-medium text-neutral-400">{entry.title}</span>
        <span className="block truncate text-xs text-orange-300">Not found on TMDB</span>
      </>
    )
  const pill = entry.requestState ? pills[entry.requestState] : undefined
  return (
    <>
      <span className="relative block">
        {rank !== undefined && <RankBadge rank={rank} />}
        <span className="block opacity-40 grayscale-[.6]">
          <Poster path={entry.posterPath} alt={entry.title} />
        </span>
      </span>
      <span className="mt-1.5 block truncate text-sm font-medium text-neutral-400">{entry.title}</span>
      <span className="block truncate text-xs text-neutral-500">{[entry.year || '', 'Not in your library'].filter(Boolean).join(' · ')}</span>
      {(view.request || pill || actions) && <span className="mt-2 flex flex-wrap items-center gap-1.5">
        {view.request ? (
          <button
            type="button"
            onClick={onRequest}
            aria-label={`Request ${entry.title}`}
            className="inline-flex min-h-[32px] items-center gap-1.5 rounded-md border border-line-2 bg-surface px-3 text-[13px] font-semibold hover:border-accent hover:text-accent max-sm:min-h-11"
          >
            <Icon d="M12 5v14M5 12h14" size={14} />
            Request
          </button>
        ) : (
          pill && <Pill gold={pill.gold}>{pill.label}</Pill>
        )}
        {actions}
      </span>}
    </>
  )
}

function Pill({ gold, children }: { gold?: boolean; children: ReactNode }) {
  return (
    <span className={`inline-flex min-h-[28px] items-center gap-1.5 whitespace-nowrap rounded-full border px-2.5 text-xs font-medium ${gold ? 'border-accent/55 text-accent' : 'border-line-2 text-neutral-400'}`}>
      <span aria-hidden="true" className="h-1.5 w-1.5 shrink-0 rounded-full bg-current" />
      {children}
    </span>
  )
}

export function Icon({ d, size = 16 }: { d: string; size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d={d} />
    </svg>
  )
}
