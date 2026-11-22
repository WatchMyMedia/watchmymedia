import type { WatchlistTarget } from '../../api/watchlists'
import { BookmarkIcon, WithPicker } from './WatchlistPicker'
import { useWatchlistToggle } from './useWatchlistToggle'

/**
 * Watchlist on a movie's or a show's page: a toggle with a bookmark that
 * fills once the title is saved, and says where ("On Halloween"). With two
 * or more watchlists it opens Save to watchlist instead. tall matches a
 * row of 44px buttons, as the show page's are.
 */
export function WatchlistButton({ target, title, holding, tall = false }: { target: WatchlistTarget; title: string; holding: readonly number[]; tall?: boolean }) {
  const w = useWatchlistToggle(target, { holding })
  return (
    <WithPicker open={w.open} onClose={() => w.setOpen(false)} target={target} title={title} className="inline-flex">
      <button
        type="button"
        aria-pressed={w.saved}
        aria-haspopup={w.picker ? 'dialog' : undefined}
        aria-expanded={w.picker ? w.open : undefined}
        onClick={() => void w.press()}
        className={`inline-flex ${tall ? 'min-h-11' : 'min-h-10'} items-center justify-center gap-2 whitespace-nowrap rounded-md border bg-surface px-4 text-sm font-medium hover:bg-surface-2 ${w.open ? 'border-accent bg-surface-2' : 'border-line-2'}`}
      >
        <BookmarkIcon filled={w.saved} />
        {w.label}
      </button>
    </WithPicker>
  )
}

/**
 * Add to Watchlist on a title the library doesn't have (another list's
 * page, Request): the same press as the button, worded as an action.
 * compact draws the bookmark alone at phone width, for a card in a grid.
 */
export function WatchlistAction({ target, title, watchlisted, compact = true, className = '' }: { target: WatchlistTarget; title: string; watchlisted?: boolean; compact?: boolean; className?: string }) {
  const w = useWatchlistToggle(target, { watchlisted })
  return (
    <WithPicker open={w.open} onClose={() => w.setOpen(false)} target={target} title={title} className={`inline-flex ${className}`}>
      <button
        type="button"
        aria-haspopup={w.picker ? 'dialog' : undefined}
        aria-expanded={w.picker ? w.open : undefined}
        aria-label={`${w.label}: ${title}`}
        onClick={() => void w.press()}
        className={`inline-flex min-h-8 items-center justify-center gap-1.5 rounded-md border bg-surface px-3 text-[13px] font-semibold hover:border-accent hover:text-accent max-sm:min-h-11 ${compact ? 'max-sm:w-11 max-sm:px-0' : ''} ${w.open ? 'border-accent' : 'border-line-2'}`}
      >
        <BookmarkIcon filled={w.saved} size={14} />
        {/* A narrow card at phone width has room for the bookmark alone; the button's name says the rest. */}
        <span className={compact ? 'max-sm:hidden' : ''}>{w.label}</span>
      </button>
    </WithPicker>
  )
}
