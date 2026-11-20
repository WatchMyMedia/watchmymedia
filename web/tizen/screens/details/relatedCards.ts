import type { components } from '../../../src/api/schema'
import { relatedRows } from '../../../src/lib/screenRows'
import type { Route } from '../../app/routes'
import type { CardBadge } from '../../ui/Card'

// The rows a movie's and a show's page end with (PLAN-RELATED.md §7;
// MovieRelated and ShowRelated on the Samsung TV canvas): the movie's
// collection, then More like this, as poster rows.

type BrowseItem = components['schemas']['BrowseItem']
type RelatedRows = components['schemas']['RelatedRows']

/** Beside the collection row's name, when the server sends no line of its own (the Android TV app's RELEASE_ORDER). */
export const RELEASE_ORDER = 'In release order'

export type RelatedRow = { id: string; title: string; subtitle?: string; items: BrowseItem[] }

/** The rows the page draws, in the server's order: poster rows with cards, never a See all. */
export function relatedPosterRows(reply: RelatedRows | undefined): RelatedRow[] {
  return relatedRows(reply)
    .filter((r) => r.style === 'poster' && !!r.items?.length)
    .map((r) => ({ id: r.id, title: r.title, subtitle: r.subtitle ?? (r.id === 'collection' ? RELEASE_ORDER : undefined), items: r.items ?? [] }))
}

export type TitleCard = { key: string; title: string; subtitle?: string; posterPath?: string; progress?: number; badges: CardBadge[]; label: string; route: Route }

/**
 * A related title's card: "2026 · Thriller" under it, New while it is new
 * and not watched, a gold tick once watched, and how far through. OK opens
 * its page on top of this one.
 */
export function titleCard(item: BrowseItem): TitleCard {
  const subtitle = [item.year ? String(item.year) : '', item.genres[0] ?? ''].filter(Boolean).join(' · ') || undefined
  const progress = !item.watched && item.progress && item.progress > 0 ? item.progress : undefined
  const badges: CardBadge[] = item.watched ? [{ label: '✓', tone: 'gold' }] : item.isNew ? [{ label: 'New', tone: 'gold' }] : []
  const state = item.watched ? 'watched' : progress ? 'partly watched' : ''
  return {
    key: `${item.kind}${item.id}`,
    title: item.title,
    subtitle,
    posterPath: item.posterPath,
    progress,
    badges,
    label: [item.title, subtitle, state].filter(Boolean).join(', '),
    route: item.kind === 'show' ? { name: 'show', id: item.id } : { name: 'movie', id: item.id },
  }
}
