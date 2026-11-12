import { Link } from '@tanstack/react-router'
import type { BrowseItem } from './PosterCard'

/** 16:9 backdrop card with a date chip, for wide rows such as Recently released. */
export function ReleasedCard({ item }: { item: BrowseItem }) {
  const art = item.backdropPath ?? item.posterPath
  const link =
    item.kind === 'show'
      ? ({ to: '/shows/$showId', params: { showId: String(item.id) } } as const)
      : ({ to: '/movies/$movieId', params: { movieId: String(item.id) } } as const)
  const date = item.kind === 'show' ? item.lastAirDate ?? (item.year ? String(item.year) : '') : item.releaseDate
  return (
    <Link {...link} className="card group block w-[304px] shrink-0 snap-start">
      <div className="relative">
        <div className="aspect-video w-full overflow-hidden rounded-md bg-surface-2">
          {art && <img src={`/api/images/w780${art}`} alt={item.title} loading="lazy" className="h-full w-full object-cover" />}
        </div>
        {date && (
          <span className="absolute bottom-1.5 left-1.5 rounded bg-black/70 px-1.5 py-0.5 text-[10px] font-semibold uppercase tracking-wide backdrop-blur">{date}</span>
        )}
      </div>
      <div className="mt-1.5 truncate text-sm font-medium">{item.title}</div>
      <div className="truncate text-xs text-neutral-500">{item.genres.slice(0, 2).join(', ')}</div>
    </Link>
  )
}
