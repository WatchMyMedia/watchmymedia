import { withConfirm } from './withConfirm'
import { infiniteQueryOptions, queryOptions, useMutation, useQueryClient, type InfiniteData, type QueryClient } from '@tanstack/react-query'
import { api, unwrap } from './client'
import type { components } from './schema'
import { invalidateWatch } from './watch'
import { authScope } from '../lib/authScope'
import { plexHistoryCursor, type PlexHistoryPage, type PlexHistoryPages } from '../lib/plexHistory'
import { queryEventInvalidator } from './queryEventInvalidator'

export type PlexSignIn = components['schemas']['PlexSignIn']
export type PlexServer = components['schemas']['PlexServer']
export type PlexConnect = components['schemas']['PlexConnect']
export type PlexConnection = components['schemas']['PlexConnection']
export type PlexPerson = components['schemas']['PlexPerson']
export type PlexSection = components['schemas']['PlexSection']
export type PlexLinks = components['schemas']['PlexLinks']
export type PlexPreview = components['schemas']['PlexPreview']

export type PlexRunPage = components['schemas']['PlexRunPage']

/** Persisted live state only. Provider discovery is explicitly refreshed or
 * re-keyed by connection generation; historical pages are manual snapshots. */
export function invalidatePlexState(qc: QueryClient) {
  void qc.invalidateQueries({
    predicate: ({ queryKey }) => queryKey[0] === 'plex' &&
      (queryKey[1] === 'connections' || ['runs', 'exclusions', 'run'].includes(String(queryKey[2]))),
  })
}

/** Keep an old retried/cancelled import current without replaying every page. */
export function patchPlexRunHistory(qc: QueryClient, id: number, run: PlexRun) {
  qc.setQueriesData<InfiniteData<PlexRunPage>>({ queryKey: ['plex', id, 'run-history'] }, (data) => data ? {
    ...data, pages: data.pages.map((page) => ({ ...page, items: page.items.map((old) => old.id === run.id ? run : old) })),
  } : data)
}

export function recordPlexRun(qc: QueryClient, id: number, run: PlexRun) {
  qc.setQueryData(plexRunQuery(id, run.id).queryKey, run)
  qc.setQueryData<PlexRunPage>(plexRunsQuery(id).queryKey, (data) => data ? {
    ...data,
    items: data.items.map((old) => old.id === run.id ? run : old),
    active: run.status === 'running' || run.status === 'queued' ? run : data.active?.id === run.id ? undefined : data.active,
  } : data)
  patchPlexRunHistory(qc, id, run)
}

export const plexRunQuery = (id: number, runId: number) => queryOptions({
  queryKey: ['plex', id, 'run', runId],
  queryFn: async ({ signal }) => unwrap(await api.GET('/api/integrations/plex/connections/{id}/runs/{runId}', { params: { path: { id, runId } }, signal })),
  staleTime: 0,
  refetchInterval: (query) => query.state.data?.status === 'queued' || query.state.data?.status === 'running' ? 1_000 : false,
})

export const plexConnectionsQuery = queryOptions({
  queryKey: ['plex', 'connections'],
  queryFn: async () => unwrap(await api.GET('/api/integrations/plex/connections')),
  refetchInterval: 30_000,
})

export const plexPeopleQuery = (id: number, generation = 0) =>
  queryOptions({
    queryKey: ['plex', id, 'people', generation],
    queryFn: async () => unwrap(await api.GET('/api/integrations/plex/connections/{id}/users', { params: { path: { id } } })),
  })

export const plexLibrariesQuery = (id: number, generation = 0) =>
  queryOptions({
    queryKey: ['plex', id, 'libraries', generation],
    queryFn: async () => unwrap(await api.GET('/api/integrations/plex/connections/{id}/libraries', { params: { path: { id } } })),
  })

export const plexSignInQuery = (id: string) =>
  queryOptions({
    queryKey: ['plex', 'sign-in', id],
    queryFn: async () => unwrap(await api.GET('/api/integrations/plex/auth/{id}', { params: { path: { id } } })),
    enabled: !!id,
    refetchInterval: (query) => (!query.state.error && query.state.data?.status === 'pending' ? 2_000 : false),
    retry: false,
  })

export const plexPreviewQuery = (id: number, previewId: string) =>
  queryOptions({
    queryKey: ['plex', id, 'preview', previewId],
    queryFn: async () => unwrap(await api.GET('/api/integrations/plex/connections/{id}/previews/{previewId}', { params: { path: { id, previewId } } })),
    enabled: !!previewId,
    refetchInterval: (query) => (!query.state.error && query.state.data?.status === 'running' ? 1_000 : false),
    retry: false,
  })

export const startPlexSignIn = async () => withConfirm(async () => unwrap(await api.POST('/api/integrations/plex/auth')), 'sign in to Plex')
export const cancelPlexSignIn = async (id: string) => unwrap(await api.DELETE('/api/integrations/plex/auth/{id}', { params: { path: { id } } }))
export const connectPlex = async (body: PlexConnect) => withConfirm(async () => unwrap(await api.POST('/api/integrations/plex/connections', { body })), 'connect this Plex server')

export function useSavePlexMappings(id: number) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async (body: PlexLinks) => unwrap(await api.PUT('/api/integrations/plex/connections/{id}/mappings', { params: { path: { id } }, body })),
    onSuccess: () => invalidatePlexState(qc),
  })
}

export function useStartPlexPreview(id: number) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async () => unwrap(await api.POST('/api/integrations/plex/connections/{id}/previews', { params: { path: { id } } })),
    onSuccess: (preview) => qc.setQueryData(plexPreviewQuery(id, preview.id).queryKey, preview),
  })
}

export function useDisconnectPlex(id: number) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async () => withConfirm(async () => unwrap(await api.DELETE('/api/integrations/plex/connections/{id}', { params: { path: { id } } })), 'disconnect this Plex server'),
    onSuccess: () => {
      qc.removeQueries({ queryKey: ['plex', id] })
      void qc.invalidateQueries({ queryKey: ['plex', 'connections'] })
    },
  })
}

export type PlexRun = components['schemas']['PlexRun']
export type PlexRunStart = components['schemas']['PlexRunStart']
export type PlexExclusions = components['schemas']['PlexExclusions']
export type PlexExclusion = components['schemas']['PlexExclusion']
export type PlexUndoPreview = components['schemas']['PlexUndoPreview']
export type PlexHistoryItem = components['schemas']['PlexHistoryItem']
export type PlexHistoryChanged = components['schemas']['PlexHistoryChanged']

export const plexRunsQuery = (id: number) =>
  queryOptions({
    queryKey: ['plex', id, 'runs'],
    queryFn: async () => unwrap(await api.GET('/api/integrations/plex/connections/{id}/runs', { params: { path: { id } } })),
    refetchInterval: (query) =>
      query.state.data?.active || query.state.data?.items.some((r) => r.status === 'running' || r.status === 'queued') ? 1_000 : 30_000,
  })

/** Earlier imports remain reachable for guarded undo after later scheduled checks. */
export const plexRunHistoryQuery = (id: number) =>
  infiniteQueryOptions({
    queryKey: ['plex', id, 'run-history'],
    initialPageParam: 0,
    queryFn: async ({ pageParam }) =>
      unwrap(await api.GET('/api/integrations/plex/connections/{id}/runs', { params: { path: { id }, query: { before: pageParam || undefined, limit: 30 } } })),
    getNextPageParam: (page) => page.next ?? undefined,
    enabled: false,
    staleTime: Infinity,
    refetchOnWindowFocus: false,
  })

export const plexExclusionsQuery = (id: number) =>
  queryOptions({
    queryKey: ['plex', id, 'exclusions'],
    queryFn: async () => unwrap(await api.GET('/api/integrations/plex/connections/{id}/exclusions', { params: { path: { id } } })),
  })

export function useStartPlexRun(id: number) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async (body: PlexRunStart) => unwrap(await api.POST('/api/integrations/plex/connections/{id}/runs', { params: { path: { id } }, body })),
    onSuccess: () => invalidatePlexState(qc),
  })
}

export function usePlexRunAction(id: number, action: 'cancel' | 'retry') {
  const qc = useQueryClient()
  return useMutation({
    onMutate: () => authScope(),
    mutationFn: async (runId: number) =>
      unwrap(
        await api.POST(
          action === 'cancel' ? '/api/integrations/plex/connections/{id}/runs/{runId}/cancel' : '/api/integrations/plex/connections/{id}/runs/{runId}/retry',
          { params: { path: { id, runId } } },
        ),
      ),
    onSuccess: (run, _id, scope) => {
      if (scope !== authScope()) return
      recordPlexRun(qc, id, run)
      invalidatePlexState(qc)
    },
  })
}

export function usePlexSync(id: number) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async (body: components['schemas']['PlexSyncUpdate']) =>
      unwrap(await api.PUT('/api/integrations/plex/connections/{id}/sync', { params: { path: { id } }, body })),
    onSuccess: () => invalidatePlexState(qc),
  })
}

export function useSavePlexExclusions(id: number) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async (body: PlexExclusions) => unwrap(await api.PUT('/api/integrations/plex/connections/{id}/exclusions', { params: { path: { id } }, body })),
    onSuccess: () => invalidatePlexState(qc),
  })
}

export const previewPlexUndo = async (id: number, runId: number) =>
  unwrap(await api.POST('/api/integrations/plex/connections/{id}/runs/{runId}/undo-previews', { params: { path: { id, runId } } }))

export function useUndoPlexRun(id: number) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async ({ runId, previewId }: { runId: number; previewId: string }) =>
      unwrap(await api.POST('/api/integrations/plex/connections/{id}/runs/{runId}/undo', { params: { path: { id, runId } }, body: { previewId } })),
    onSuccess: () => {
      invalidatePlexState(qc)
      invalidateWatch(qc)
    },
  })
}

export function useRebaselinePlex(id: number) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async (generation: number) =>
      unwrap(await api.POST('/api/integrations/plex/connections/{id}/rebaseline', { params: { path: { id } }, body: { generation } })),
    onSuccess: () => invalidatePlexState(qc),
  })
}

const personalHistoryKey = (userId?: number) => ['plex', 'history', userId ?? 'me', authScope()] as const

/** Only the newest page polls or reacts to imported-history notifications. */
export const plexHistoryQuery = (userId?: number) =>
  queryOptions({
    queryKey: [...personalHistoryKey(userId), 'head'],
    queryFn: async ({ signal }) => unwrap(await api.GET('/api/plex-history', { params: { query: { userId, limit: 50 } }, signal })),
    refetchInterval: 30_000,
  })

export const plexHistoryPagesQuery = (userId?: number, head?: PlexHistoryPage) =>
  infiniteQueryOptions({
    queryKey: [...personalHistoryKey(userId), 'pages'],
    initialPageParam: 0,
    queryFn: async ({ pageParam, signal }) => unwrap(await api.GET('/api/plex-history', { params: { query: { userId, before: pageParam || undefined, limit: 50 } }, signal })),
    getNextPageParam: (_last, pages, _lastParam, pageParams) => plexHistoryCursor(head, { pages, pageParams }),
    enabled: false,
    staleTime: Infinity,
    refetchOnWindowFocus: false,
  })

export function plexHistoryEventInvalidator(qc: QueryClient) {
  const changed = queryEventInvalidator(qc, {
    predicate: ({ queryKey }) => queryKey[0] === 'plex' && queryKey[1] === 'history' && queryKey[2] === 'me' && queryKey[3] === authScope() && queryKey[4] === 'head',
  })
  return (event: PlexHistoryChanged) => {
    // This event is private to its owner. An admin's manually retained view of
    // another person must not be cleared by a change to the admin's own history.
    if (event.cleared || event.deletedIds?.length) {
      void removePlexHistoryFromCache(qc, undefined, authScope(), event.cleared ? undefined : event.deletedIds ?? undefined, false).then(changed)
    } else changed()
  }
}

/** A complete head also proves a remote clear/retention change. Cancel any
 * older snapshot first so its pre-clear reply cannot restore removed events. */
export async function resetPlexHistoryPages(qc: QueryClient, userId: number | undefined, scope: number) {
  if (scope !== authScope()) return
  const queryKey = [...personalHistoryKey(userId), 'pages']
  await qc.cancelQueries({ queryKey, exact: true })
  if (scope !== authScope()) return
  qc.setQueryData<PlexHistoryPages>(queryKey, { pages: [], pageParams: [] })
}

/** Cancel stale page replies before patching every retained copy. Keep its
 * original cursor even when the deleted row was the page's boundary. */
export async function removePlexHistoryFromCache(qc: QueryClient, userId: number | undefined, scope: number, historyId?: number | readonly number[], refresh = true) {
  if (scope !== authScope()) return
  const queryKey = personalHistoryKey(userId)
  await qc.cancelQueries({ queryKey })
  if (scope !== authScope()) return
  const ids = historyId === undefined ? undefined : new Set(typeof historyId === 'number' ? [historyId] : historyId)
  const remove = (page: PlexHistoryPage): PlexHistoryPage => ids === undefined ? { items: [] } : { ...page, items: page.items.filter((item) => !ids.has(item.id)) }
  qc.setQueryData<PlexHistoryPage>([...queryKey, 'head'], (page) => page && remove(page))
  qc.setQueryData<PlexHistoryPages>([...queryKey, 'pages'], (data) => data && (historyId === undefined ? { pages: [], pageParams: [] } : { ...data, pages: data.pages.map(remove) }))
  if (refresh) await qc.invalidateQueries({ queryKey: [...queryKey, 'head'], exact: true }, { cancelRefetch: false })
}

export function useDeletePlexHistory(userId?: number) {
  const qc = useQueryClient()
  return useMutation({
    onMutate: () => authScope(),
    mutationFn: async (historyId: number) => unwrap(await api.DELETE('/api/plex-history/{historyId}', { params: { path: { historyId }, query: { userId } } })),
    onSuccess: (_reply, historyId, scope) => removePlexHistoryFromCache(qc, userId, scope, historyId),
  })
}

export function useClearPlexHistory(userId?: number) {
  const qc = useQueryClient()
  return useMutation({
    onMutate: () => authScope(),
    mutationFn: async () => unwrap(await api.DELETE('/api/plex-history', { params: { query: { userId } } })),
    onSuccess: (_reply, _variables, scope) => removePlexHistoryFromCache(qc, userId, scope),
  })
}
