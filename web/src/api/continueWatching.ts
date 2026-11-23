import { useMutation, useQueryClient } from '@tanstack/react-query'
import { api, unwrap } from './client'
import type { components } from './schema'

// Removing a title from Continue watching, and putting it back (API level
// 34): PUT and DELETE /api/watch/{type}/{id}/hidden. The place is kept, and
// playing the title again brings it back by itself.

type HomeData = components['schemas']['HomeData']
type ContinueItem = components['schemas']['ContinueItem']

/** What is hidden: a movie, or an episode (a show's card is its episode). */
export type ContinueTarget = { type: 'movie' | 'episode'; id: number }

/** A Continue watching card's target, when it names one. */
export function continueTarget(item: Pick<ContinueItem, 'kind' | 'movieId' | 'episodeId'>): ContinueTarget | undefined {
  if (item.kind === 'movie') return item.movieId !== undefined ? { type: 'movie', id: item.movieId } : undefined
  return item.episodeId !== undefined ? { type: 'episode', id: item.episodeId } : undefined
}

const same = (item: ContinueItem, t: ContinueTarget) => {
  const of = continueTarget(item)
  return !!of && of.type === t.type && of.id === t.id
}

/** Home's reply without the card, in its own list and in the arranged rows. */
export function withoutContinueItem(home: HomeData, t: ContinueTarget): HomeData {
  return {
    ...home,
    continueWatching: home.continueWatching.filter((c) => !same(c, t)),
    ...(home.rows ? { rows: home.rows.map((r) => (r.continue ? { ...r, continue: r.continue.filter((c) => !same(c, t)) } : r)) } : {}),
  }
}

export const hideFromContinueWatching = (t: ContinueTarget) =>
  api.PUT('/api/watch/{type}/{id}/hidden', { params: { path: { type: t.type, id: t.id } } }).then(unwrap)

export const showInContinueWatching = (t: ContinueTarget) =>
  api.DELETE('/api/watch/{type}/{id}/hidden', { params: { path: { type: t.type, id: t.id } } }).then(unwrap)

/**
 * Hides a card (`hidden: true`) or brings it back. Home's cached reply
 * loses the card at once and gets it back if the server refuses; Home is
 * asked again either way.
 */
export function useContinueWatchingHidden() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ target, hidden }: { target: ContinueTarget; hidden: boolean }) => (hidden ? hideFromContinueWatching(target) : showInContinueWatching(target)),
    onMutate: async ({ target, hidden }) => {
      if (!hidden) return undefined
      await qc.cancelQueries({ queryKey: ['home'] })
      const before = qc.getQueryData<HomeData>(['home'])
      if (before) qc.setQueryData<HomeData>(['home'], withoutContinueItem(before, target))
      return { before }
    },
    onError: (_error, _vars, context) => {
      if (context?.before) qc.setQueryData(['home'], context.before)
    },
    onSettled: () => {
      void qc.invalidateQueries({ queryKey: ['home'] })
    },
  })
}
