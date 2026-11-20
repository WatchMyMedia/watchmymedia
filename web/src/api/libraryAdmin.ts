import { queryOptions } from '@tanstack/react-query'
import { api, unwrap } from './client'

/**
 * What removing a library would take with it, for the Remove dialog.
 * Under ['libraries', id] so a scan or a
 * match refreshes it. Nothing changes on the server.
 */
export const libraryRemovalQuery = (id: number) =>
  queryOptions({
    queryKey: ['libraries', id, 'removal'],
    queryFn: async () => unwrap(await api.GET('/api/libraries/{id}/removal', { params: { path: { id } } })),
    staleTime: 30_000,
  })
