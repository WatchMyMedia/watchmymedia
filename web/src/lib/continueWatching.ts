import type { components } from '../api/schema'

type ContinueItem = components['schemas']['ContinueItem']

// Remove from Continue watching: the title leaves the row and its place is
// kept, so playing it here again (or Undo) brings it back where it was.

/** What the hidden route takes: the movie, or the episode. Nothing for an item without either id. */
export type ContinueTarget = { type: 'movie' | 'episode'; id: number }

export function continueTarget(item: Pick<ContinueItem, 'kind' | 'movieId' | 'episodeId'>): ContinueTarget | undefined {
  if (item.kind === 'movie') return item.movieId != null ? { type: 'movie', id: item.movieId } : undefined
  return item.episodeId != null ? { type: 'episode', id: item.episodeId } : undefined
}

function isTarget(item: ContinueItem, t: ContinueTarget): boolean {
  const own = continueTarget(item)
  return !!own && own.type === t.type && own.id === t.id
}

/**
 * A cached Home or library rows reply without the item: from every
 * Continue watching row, and from the fixed list older replies carry.
 * Anything else is returned as it is.
 */
export function withoutContinue<T>(data: T, t: ContinueTarget): T {
  if (!data || typeof data !== 'object') return data
  const d = data as { rows?: { continue?: ContinueItem[] }[]; continueWatching?: ContinueItem[] }
  let changed = false
  const rows = d.rows?.map((r) => {
    if (!r.continue?.some((c) => isTarget(c, t))) return r
    changed = true
    return { ...r, continue: r.continue.filter((c) => !isTarget(c, t)) }
  })
  const fixed = d.continueWatching?.some((c) => isTarget(c, t)) ? d.continueWatching.filter((c) => !isTarget(c, t)) : d.continueWatching
  if (fixed !== d.continueWatching) changed = true
  if (!changed) return data
  return { ...d, ...(rows ? { rows } : {}), ...(fixed ? { continueWatching: fixed } : {}) } as T
}

/** The message once it has gone: "Copper Valley is off Continue watching. Your place is kept." */
export function hiddenWords(title: string): string {
  return `${title} is off Continue watching. Your place is kept.`
}
