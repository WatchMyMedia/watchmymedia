import { Link } from '@tanstack/react-router'
import { Badge } from './Badge'
import { CardLine, CardPicture, CardTitle } from './CardParts'
import { Poster } from './Poster'
import type { components } from '../api/schema'
import { posterLinks } from '../lib/cardLinks'
import { PeekHost } from './PeekCard'
import { CheckIcon } from './icons'
import { newFlag } from '../lib/browsePrefs'

export type BrowseItem = components['schemas']['BrowseItem']

/**
 * 2:3 poster with the New badge ("2 new" for a show's new episodes); fixed width inside rows, fluid in grids. Its
 * parts are links of their own (lib/cardLinks.ts): the
 * title opens the title, a Recently added show card's season and episode
 * open the show there, and the picture is a bigger target for the most
 * specific of them. rank puts a list's place where New goes.
 * subtitle is the plain end of the second
 * line; without it the line is the year and count.
 */
export function PosterCard({ item, inRow = false, subtitle, showCount = false, rank, peek = inRow }: { item: BrowseItem; inRow?: boolean; subtitle?: string; showCount?: boolean; rank?: number; peek?: boolean }) {
  if (item.kind === 'unmatched') return <UnmatchedCard item={item} inRow={inRow} />
  return <MatchedCard item={item} inRow={inRow} subtitle={subtitle} showCount={showCount} rank={rank} peek={peek} />
}

function MatchedCard({ item, inRow, subtitle, showCount, rank, peek }: { item: BrowseItem; inRow: boolean; subtitle?: string; showCount: boolean; rank?: number; peek: boolean }) {
  const links = posterLinks(item, subtitle)
  const flag = newFlag(item)
  return (
    <PeekHost item={item} enabled={peek} className={`shrink-0 snap-start ${inRow ? 'w-[124px] md:w-[168px]' : ''}`}>
      <CardPicture {...links.picture}>
        <Poster path={item.posterPath} alt={item.title} />
        {rank !== undefined ? <RankBadge rank={rank} /> : flag && <CardFlag>{flag}</CardFlag>}
        {showCount && item.kind === 'show' && item.episodeCount ? (
          <Badge className="absolute bottom-1.5 left-1.5">{item.episodeCount} ep</Badge>
        ) : null}
        {item.watched && <WatchedTick />}
        {!item.watched && item.progress ? <CardProgress pct={Math.round(item.progress * 100)} /> : null}
      </CardPicture>
      <CardTitle {...links.title} />
      <CardLine segments={links.line} />
    </PeekHost>
  )
}

/** A gold flag in a picture's top-left corner: New, a count. */
export function CardFlag({ children, className = '' }: { children: React.ReactNode; className?: string }) {
  return <span className={`absolute left-2 top-2 rounded-[5px] bg-accent px-[7px] py-0.5 text-[11px] font-bold tracking-[.02em] text-ground ${className}`}>{children}</span>
}

/** Watched: a gold tick in a picture's top-right corner. */
export function WatchedTick() {
  return (
    <span role="img" aria-label="Watched" title="Watched" className="absolute right-2 top-2 grid size-[22px] place-items-center rounded-full bg-ground/80 text-accent ring-1 ring-inset ring-accent/70">
      <CheckIcon size={13} />
    </span>
  )
}

/** How far in: a gold bar along a picture's foot. */
export function CardProgress({ pct }: { pct: number }) {
  return (
    <span aria-hidden="true" className="absolute inset-x-0 bottom-0 h-1 bg-black/60">
      <span className="block h-full bg-accent" style={{ width: `${pct}%` }} />
    </span>
  )
}

/**
 * A file (or show folder) that is not matched yet: no artwork, just its name
 * on a plain tile, and a link to the library's Unmatched view to fix it.
 */
function UnmatchedCard({ item, inRow }: { item: BrowseItem; inRow: boolean }) {
  const to = item.libraryType === 'movie' ? '/movies' : '/tv'
  const many = (item.fileCount ?? 1) > 1
  return (
    <Link to={to} search={{ view: 'unmatched' }} className={`card group block shrink-0 snap-start ${inRow ? 'w-[168px]' : ''}`} title={item.fileName}>
      <div className="relative flex aspect-[2/3] w-full flex-col justify-between overflow-hidden rounded-md border border-dashed border-line-2 bg-surface p-2.5">
        <div className="flex items-center justify-between">
          <Badge tone="warn">Unmatched</Badge>
        </div>
        <div className="flex flex-col items-center gap-2 text-neutral-500">
          {many || item.libraryType === 'tv' ? (
            <svg width="34" height="34" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <rect x="3" y="6" width="18" height="12" rx="2" />
              <path d="m8 3 4 3 4-3" />
            </svg>
          ) : (
            <svg width="34" height="34" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <rect x="3" y="4" width="18" height="16" rx="2" />
              <path d="M7 4v16M17 4v16M3 9h4M3 15h4M17 9h4M17 15h4" />
            </svg>
          )}
        </div>
        <p className="line-clamp-4 break-all font-mono text-[11px] leading-snug text-neutral-300">{item.fileName}</p>
      </div>
      <div className="mt-1.5 truncate text-sm font-medium">{item.title}</div>
      <div className="truncate text-xs text-warn">{item.subtitle}</div>
    </Link>
  )
}

/** A title's place on a list, over its poster. */
export function RankBadge({ rank }: { rank: number }) {
  return <span className="absolute left-1.5 top-1.5 z-10 min-w-[22px] rounded bg-black/80 px-1.5 py-0.5 text-center text-[11px] font-bold">{rank}</span>
}
