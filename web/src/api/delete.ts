import { queryOptions, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useAuth } from '../authContext'
import { api, unwrap } from './client'
import { requestOptionsQuery } from './requests'
import type { components } from './schema'

export type DeletePreview = components['schemas']['DeletePreview']
export type DeleteApp = components['schemas']['DeleteApp']
export type DeleteOptions = components['schemas']['DeleteOptions']
export type DeleteResult = components['schemas']['DeleteResult']
export type ReplaceResult = components['schemas']['ReplaceResult']

/** Whether the signed-in user can replace files and delete shows and seasons: an admin, here, with Radarr or Sonarr connected. */
export function useCanManage(): boolean {
  const auth = useAuth()
  const admin = (auth.user?.isAdmin ?? false) && auth.adminAllowed
  const options = useQuery({ ...requestOptionsQuery, enabled: admin })
  return admin && (options.data?.manage ?? false)
}

/**
 * Whether the signed-in user may delete episodes and movies: an admin, or
 * anyone when Settings › Users allows it, with Radarr or Sonarr connected
 * (PLAN-EPISODE-PAGE.md §4). Shows, seasons and Replace file stay with useCanManage.
 */
export function useCanDelete(): boolean {
  const options = useQuery(requestOptionsQuery)
  return options.data?.delete ?? false
}

export type DeleteSettings = components['schemas']['DeleteSettings']

/** Who may delete episodes and movies, for Settings › Users (admins). */
export const deleteSettingsQuery = queryOptions({
  queryKey: ['delete-settings'],
  queryFn: async () => unwrap(await api.GET('/api/settings/deleting')),
})

export function useUpdateDeleteSettings() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async (access: DeleteSettings['access']) => unwrap(await api.PUT('/api/settings/deleting', { body: { access } })),
    onSuccess: (s) => {
      qc.setQueryData(deleteSettingsQuery.queryKey, s)
      // What each person may do changes with it.
      void qc.invalidateQueries({ queryKey: requestOptionsQuery.queryKey })
    },
  })
}

/** What can be deleted: a movie, a show, one season of a show, or an episode. */
export type DeleteItem =
  | { type: 'movie'; id: number }
  | { type: 'show'; id: number }
  | { type: 'season'; showId: number; season: number }
  | { type: 'episode'; id: number; showId: number }

/** What deleting the item would do, asked of the apps when the dialog opens. */
export const deletePreviewQuery = (item: DeleteItem) =>
  queryOptions({
    queryKey: ['delete-preview', item],
    queryFn: async () => {
      const query =
        item.type === 'season' ? { type: item.type, id: item.showId, season: item.season } : { type: item.type, id: item.id }
      return unwrap(await api.GET('/api/delete/preview', { params: { query } }))
    },
    // Always what the apps say now.
    staleTime: 0,
    gcTime: 0,
    retry: false,
  })

async function deleteItem(item: DeleteItem, body: DeleteOptions): Promise<DeleteResult> {
  switch (item.type) {
    case 'movie':
      return unwrap(await api.POST('/api/movies/{id}/delete', { params: { path: { id: item.id } }, body }))
    case 'show':
      return unwrap(await api.POST('/api/shows/{id}/delete', { params: { path: { id: item.id } }, body }))
    case 'season':
      return unwrap(await api.POST('/api/shows/{id}/seasons/{number}/delete', { params: { path: { id: item.showId, number: item.season } }, body }))
    case 'episode':
      return unwrap(await api.POST('/api/episodes/{id}/delete', { params: { path: { id: item.id } }, body }))
  }
}

/** The page the item is on, to refetch once it is gone. */
function itemKey(item: DeleteItem): unknown[] {
  return item.type === 'movie' ? ['movie', item.id] : ['show', item.type === 'show' ? item.id : item.showId]
}

export function useDeleteItem(item: DeleteItem) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: DeleteOptions) => deleteItem(item, body),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: itemKey(item) })
      void qc.invalidateQueries({ queryKey: ['requests'] })
    },
  })
}

/** Replace a file: the app blocks its release, deletes it and searches again. */
export function useReplaceFile() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async (fileId: number) => unwrap(await api.POST('/api/files/{id}/replace', { params: { path: { id: fileId } } })),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['movie'] })
      void qc.invalidateQueries({ queryKey: ['show'] })
    },
  })
}
