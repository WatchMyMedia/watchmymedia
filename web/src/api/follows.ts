import { queryOptions, useMutation, useQueryClient, type QueryClient } from '@tanstack/react-query'
import { api, unwrap } from './client'
import type { components } from './schema'

// Following shows and the episodes that arrive for them
// (PLAN-NEW-EPISODES.md §5.3, §5.4; API level 29). Keys: ['follows'] for the
// person's list; a show's following is in its own reply, ['show', id].

export type Follow = components['schemas']['Follow']
export type FollowList = components['schemas']['FollowList']
export type NewEpisodes = components['schemas']['NewEpisodes']
export type NewEpisodeGroup = components['schemas']['NewEpisodeGroup']
type ShowDetail = components['schemas']['ShowDetail']

/** The shows the signed-in person follows, newest first. */
export const followsQuery = queryOptions({
  queryKey: ['follows'],
  queryFn: async () => unwrap(await api.GET('/api/me/follows')),
})

/**
 * What arrived for the person's shows after a cursor; without one, only the
 * current cursor. Undefined when the server can't be reached or refuses, so
 * the caller keeps its cursor and the next check catches up (§6.1).
 */
export async function fetchNewEpisodes(after?: string): Promise<NewEpisodes | undefined> {
  try {
    const { data, response } = await api.GET('/api/me/new-episodes', { params: { query: { after } } })
    return response.ok ? data : undefined
  } catch {
    return undefined
  }
}

/** A show's own reply, ['show', id], which carries following; not its seasons'. */
const isShow = (key: readonly unknown[]) => key.length === 2 && key[0] === 'show'

/** What shows follows: the list, a show's bell and Home's New from shows you follow. */
export function invalidateFollows(qc: QueryClient) {
  void qc.invalidateQueries({ queryKey: ['follows'] })
  void qc.invalidateQueries({ predicate: (q) => isShow(q.queryKey) })
  void qc.invalidateQueries({ queryKey: ['home'] })
}

type FollowsSnapshot = { shows: [readonly unknown[], ShowDetail | undefined][]; list: FollowList | undefined }

/** Marks every cached reply at once: the show's pages with this TMDB id, and the list. */
async function markFollowing(qc: QueryClient, tmdbId: number, following: boolean): Promise<FollowsSnapshot> {
  await qc.cancelQueries({ queryKey: ['follows'] })
  await qc.cancelQueries({ predicate: (q) => isShow(q.queryKey) })
  const shows = qc.getQueriesData<ShowDetail>({ predicate: (q) => isShow(q.queryKey) })
  const list = qc.getQueryData<FollowList>(followsQuery.queryKey)
  for (const [key, show] of shows) if (show?.tmdbId === tmdbId) qc.setQueryData<ShowDetail>(key, { ...show, following })
  if (list && !following) qc.setQueryData<FollowList>(followsQuery.queryKey, { ...list, follows: list.follows.filter((f) => f.tmdbId !== tmdbId) })
  return { shows: shows.map(([key, show]) => [key, show]), list }
}

function restore(qc: QueryClient, snapshot: FollowsSnapshot | undefined) {
  if (!snapshot) return
  for (const [key, show] of snapshot.shows) qc.setQueryData(key, show)
  qc.setQueryData(followsQuery.queryKey, snapshot.list)
}

/**
 * Notify me on a show, and pressing it again (§7.1): shown at once, and put
 * back if the server refuses, as stars and reminders are.
 */
export function useFollowShow() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async ({ showId, follow }: { showId: number; tmdbId: number; follow: boolean }) => {
      const path = { params: { path: { id: showId } } }
      unwrap(follow ? await api.PUT('/api/shows/{id}/follow', path) : await api.DELETE('/api/shows/{id}/follow', path))
    },
    onMutate: ({ tmdbId, follow }) => markFollowing(qc, tmdbId, follow),
    onError: (_e, _v, snapshot) => restore(qc, snapshot),
    onSettled: () => invalidateFollows(qc),
  })
}

/** Remove in Settings › Notifications, by TMDB id, so a show that left the library goes too. */
export function useRemoveFollow() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async (tmdbId: number) => unwrap(await api.DELETE('/api/me/follows/{tmdbId}', { params: { path: { tmdbId } } })),
    onMutate: (tmdbId) => markFollowing(qc, tmdbId, false),
    onError: (_e, _v, snapshot) => restore(qc, snapshot),
    onSettled: () => invalidateFollows(qc),
  })
}
