import { Link } from '@tanstack/react-router'
import { Poster } from './Poster'
import { formatDuration } from '../lib/format'
import { episodePath } from '../lib/showPage'
import type { components } from '../api/schema'
import { RemoveFromContinue } from './RemoveFromContinue'

type ContinueItem = components['schemas']['ContinueItem']

/** 16:9 card with a progress bar; opens the player at the saved position. Its × removes it from the row, keeping the place. */
export function ContinueCard({ item }: { item: ContinueItem }) {
  const pct = item.durationMs ? Math.round((item.positionMs / item.durationMs) * 100) : 0
  const remaining = item.durationMs ? formatDuration(item.durationMs - item.positionMs) : ''
  // Back returns to the episode just played, on top of its show's page.
  const back =
    item.kind === 'movie' && item.movieId != null
      ? `/movies/${item.movieId}`
      : item.showId != null && item.seasonNumber != null && item.episodeId != null
        ? episodePath(item.showId, item.seasonNumber, item.episodeId)
        : item.showId != null
          ? `/shows/${item.showId}`
          : '/'
  return (
    <div className="relative w-[304px] shrink-0 snap-start">
      <Link
        to="/play/$fileId"
        params={{ fileId: String(item.fileId) }}
        search={{ title: item.subtitle ? `${item.title} · ${item.subtitle}` : item.title, back }}
        className="card group block"
      >
        <div className="relative">
          <Poster path={item.stillPath ?? item.backdropPath ?? item.posterPath} size="w780" alt={item.title} ratio="aspect-video" />
          <div className="absolute inset-0 grid place-items-center text-3xl text-white/0 transition group-hover:text-white/90">▶</div>
          <div className="absolute inset-x-0 bottom-0 h-1 bg-black/60">
            <div className="h-full bg-accent" style={{ width: `${pct}%` }} />
          </div>
        </div>
        <div className="mt-1.5 truncate text-sm font-medium">{item.title}</div>
        <div className="truncate text-xs text-neutral-500">
          {item.subtitle ? `${item.subtitle} · ` : ''}
          {remaining} left
        </div>
      </Link>
      <RemoveFromContinue item={item} name={item.title} className="absolute right-2 top-2 border-white/15 bg-ground/70" />
    </div>
  )
}
