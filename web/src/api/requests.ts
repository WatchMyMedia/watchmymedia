import { keepPreviousData, queryOptions, useMutation, useQueryClient } from '@tanstack/react-query'
import { api, unwrap } from './client'
import type { components } from './schema'

export type MediaRequest = components['schemas']['MediaRequest']
export type RequestState = components['schemas']['RequestState']
export type Requestable = components['schemas']['Requestable']
export type Problem = components['schemas']['Problem']
export type QueueDownload = components['schemas']['QueueDownload']
export type RequestOptions = components['schemas']['RequestOptions']

/** What the signed-in user may request, report and manage (PLAN-ARR.md §10). */
export const requestOptionsQuery = queryOptions({
  queryKey: ['requests', 'options'],
  queryFn: async () => unwrap(await api.GET('/api/requests/options')),
  staleTime: 60_000,
})

/** TMDB's movies and shows for a search, with where each stands. */
export const requestableSearchQuery = (q: string) =>
  queryOptions({
    queryKey: ['requests', 'search', q],
    queryFn: async () => unwrap(await api.GET('/api/requests/search', { params: { query: { q } } })),
    enabled: q.length > 0,
    placeholderData: keepPreviousData,
    staleTime: 30_000,
  })

/** One movie or show to request, with a show's seasons. */
export const requestableQuery = (mediaType: 'movie' | 'tv', tmdbId: number) =>
  queryOptions({
    queryKey: ['requests', 'lookup', mediaType, tmdbId],
    queryFn: async () => unwrap(await api.GET('/api/requests/lookup', { params: { query: { mediaType, tmdbId } } })),
  })

export const myRequestsQuery = queryOptions({
  queryKey: ['requests', 'mine'],
  queryFn: async () => unwrap(await api.GET('/api/requests/mine')),
})

export const inboxQuery = queryOptions({
  queryKey: ['requests', 'inbox'],
  queryFn: async () => unwrap(await api.GET('/api/requests/inbox')),
})

export const requestSettingsQuery = queryOptions({
  queryKey: ['requests', 'settings'],
  queryFn: async () => unwrap(await api.GET('/api/requests/settings')),
})

/** Anything showing requests or missing episodes is stale after a change. */
function useInvalidateRequests() {
  const qc = useQueryClient()
  return () => {
    void qc.invalidateQueries({ queryKey: ['requests'] })
    void qc.invalidateQueries({ queryKey: ['show'] })
    void qc.invalidateQueries({ queryKey: ['movie'] })
  }
}

export function useCreateRequest() {
  const inv = useInvalidateRequests()
  return useMutation({
    mutationFn: async (body: { mediaType: 'movie' | 'tv'; tmdbId: number; seasons?: number[] }) => unwrap(await api.POST('/api/requests', { body })),
    onSuccess: inv,
  })
}

export function useCancelRequest() {
  const inv = useInvalidateRequests()
  return useMutation({
    mutationFn: async (id: number) => unwrap(await api.DELETE('/api/requests/{id}', { params: { path: { id } } })),
    onSuccess: inv,
  })
}

export function useDecideRequest() {
  const inv = useInvalidateRequests()
  return useMutation({
    mutationFn: async ({ id, approve, note }: { id: number; approve: boolean; note?: string }) =>
      unwrap(await api.POST('/api/requests/{id}/decision', { params: { path: { id } }, body: { approve, note } })),
    onSuccess: inv,
  })
}

export function useUpdateRequestSettings() {
  const inv = useInvalidateRequests()
  return useMutation({
    mutationFn: async (approval: 'admins' | 'everyone') => unwrap(await api.PATCH('/api/requests/settings', { body: { approval } })),
    onSuccess: inv,
  })
}

export function useReportProblem() {
  const inv = useInvalidateRequests()
  return useMutation({
    mutationFn: async (body: { itemType: 'movie' | 'episode'; itemId: number; fileId?: number; kind: Problem['kind']; note?: string }) =>
      unwrap(await api.POST('/api/problems', { body })),
    onSuccess: inv,
  })
}

export function useResolveProblem() {
  const inv = useInvalidateRequests()
  return useMutation({
    mutationFn: async (id: number) => unwrap(await api.POST('/api/problems/{id}/resolve', { params: { path: { id } } })),
    onSuccess: inv,
  })
}

/** Admins: have Sonarr search for a season's (or some episodes') files now. */
export function useSearchMissing(showId: number) {
  const inv = useInvalidateRequests()
  return useMutation({
    mutationFn: async (body: { season: number; episodes?: number[] }) =>
      unwrap(await api.POST('/api/shows/{id}/search', { params: { path: { id: showId } }, body })),
    onSuccess: inv,
  })
}

/** Admins: monitor or unmonitor a season (or some episodes) in Sonarr. */
export function useMonitorEpisodes(showId: number) {
  const inv = useInvalidateRequests()
  return useMutation({
    mutationFn: async (body: { season: number; episodes?: number[]; monitored: boolean }) =>
      unwrap(await api.POST('/api/shows/{id}/monitor', { params: { path: { id: showId } }, body })),
    onSuccess: inv,
  })
}
