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

/** What an optimistic change set aside, to put back on its own if the server refuses. */
export type FollowUndo = { tmdbId: number; following: boolean; removed?: Follow }

/** Follows and removes share a key, so a refetch waits for the last of several quick presses. */
const followMutation = ['follows', 'change'] as const

/** Newest follow first, as the server lists them. */
function insertFollow(follows: Follow[], f: Follow): Follow[] {
  const at = follows.findIndex((x) => Date.parse(x.followedAt) < Date.parse(f.followedAt))
  return at < 0 ? [...follows, f] : [...follows.slice(0, at), f, ...follows.slice(at)]
}

/** Marks every cached reply at once: the show's pages with this TMDB id, and the list. */
export async function applyFollowing(qc: QueryClient, tmdbId: number, following: boolean): Promise<FollowUndo> {
  await qc.cancelQueries({ queryKey: followsQuery.queryKey, exact: true })
  await qc.cancelQueries({ predicate: (q) => isShow(q.queryKey) })
  for (const [key, show] of qc.getQueriesData<ShowDetail>({ predicate: (q) => isShow(q.queryKey) })) {
    if (show?.tmdbId === tmdbId) qc.setQueryData<ShowDetail>(key, { ...show, following })
  }
  let removed: Follow | undefined
  if (!following) {
    qc.setQueryData<FollowList>(followsQuery.queryKey, (list) => {
      removed = list?.follows.find((f) => f.tmdbId === tmdbId)
      return list && { ...list, follows: list.follows.filter((f) => f.tmdbId !== tmdbId) }
    })
  }
  return { tmdbId, following, removed }
}

/**
 * Puts back one refused change and nothing else: this show's bell, and its
 * row where it belongs, so a second refusal doesn't take back the first's.
 */
export function undoFollowing(qc: QueryClient, undo: FollowUndo | undefined) {
  if (!undo) return
  for (const [key, show] of qc.getQueriesData<ShowDetail>({ predicate: (q) => isShow(q.queryKey) })) {
    if (show?.tmdbId === undo.tmdbId) qc.setQueryData<ShowDetail>(key, { ...show, following: !undo.following })
  }
  const removed = undo.removed
  if (removed) {
    qc.setQueryData<FollowList>(followsQuery.queryKey, (list) =>
      list && !list.follows.some((f) => f.tmdbId === removed.tmdbId) ? { ...list, follows: insertFollow(list.follows, removed) } : list)
  }
}

/** Refetches once the last of several quick changes has settled, so an earlier one's reply doesn't bring back a row a later one took away. */
function settled(qc: QueryClient) {
  if (qc.isMutating({ mutationKey: followMutation }) <= 1) invalidateFollows(qc)
}

/**
 * Notify me on a show, and pressing it again (§7.1): shown at once, and put
 * back if the server refuses, as stars and reminders are.
 */
export function useFollowShow() {
  const qc = useQueryClient()
  return useMutation({
    mutationKey: followMutation,
    mutationFn: async ({ showId, follow }: { showId: number; tmdbId: number; follow: boolean }) => {
      const path = { params: { path: { id: showId } } }
      unwrap(follow ? await api.PUT('/api/shows/{id}/follow', path) : await api.DELETE('/api/shows/{id}/follow', path))
    },
    onMutate: ({ tmdbId, follow }) => applyFollowing(qc, tmdbId, follow),
    onError: (_e, _v, undo) => undoFollowing(qc, undo),
    onSettled: () => settled(qc),
  })
}

/**
 * Stop notifying in Settings › Notifications, by TMDB id, so a show that
 * left the library goes too. onFailed and onRemoved are the hook's own, so
 * they come for every press, not only the latest (mutate's callbacks do).
 */
export function removeFollowOptions(qc: QueryClient, o: { onFailed?: (tmdbId: number) => void; onRemoved?: (tmdbId: number) => void } = {}) {
  return {
    mutationKey: followMutation,
    mutationFn: async (tmdbId: number) => unwrap(await api.DELETE('/api/me/follows/{tmdbId}', { params: { path: { tmdbId } } })),
    onMutate: (tmdbId: number) => applyFollowing(qc, tmdbId, false),
    onError: (_e: Error, tmdbId: number, undo: FollowUndo | undefined) => {
      undoFollowing(qc, undo)
      o.onFailed?.(tmdbId)
    },
    onSuccess: (_d: unknown, tmdbId: number) => o.onRemoved?.(tmdbId),
    onSettled: () => settled(qc),
  }
}

export function useRemoveFollow(o?: { onFailed?: (tmdbId: number) => void; onRemoved?: (tmdbId: number) => void }) {
  const qc = useQueryClient()
  return useMutation(removeFollowOptions(qc, o))
}
