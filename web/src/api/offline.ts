import { queryOptions, useMutation, useQueryClient } from '@tanstack/react-query'
import { downloadsPoll } from '../lib/offline'
import { api, unwrap } from './client'
import { requestOptionsQuery } from './requests'
import type { components } from './schema'

export type OfflineSettings = components['schemas']['OfflineSettings']

/** Who may download to watch offline, for Settings › Users (admins; PLAN-DOWNLOADS.md §4.3). */
export const offlineSettingsQuery = queryOptions({
  queryKey: ['offline-settings'],
  queryFn: async () => unwrap(await api.GET('/api/settings/offline')),
})

export function useUpdateOfflineSettings() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async (access: OfflineSettings['access']) => unwrap(await api.PUT('/api/settings/offline', { body: { access } })),
    onSuccess: (s) => {
      qc.setQueryData(offlineSettingsQuery.queryKey, s)
      void qc.invalidateQueries({ queryKey: requestOptionsQuery.queryKey })
      void qc.invalidateQueries({ queryKey: ['offline', 'downloads'] })
    },
  })
}

/** Everyone's downloads and the transfers now, for Activity › Downloads (admins). */
export const offlineDownloadsQuery = queryOptions({
  queryKey: ['offline', 'downloads', 'all'],
  queryFn: async () => unwrap(await api.GET('/api/offline/downloads', { params: { query: { all: true } } })),
  // Nothing announces a transfer; poll, often while one runs.
  refetchInterval: (q) => downloadsPoll(q.state.data?.transfers.length ?? 0),
})

/** Revoke a download: its device deletes it at its next sync. */
export function useRevokeDownload() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async (id: string) => unwrap(await api.DELETE('/api/offline/downloads/{id}', { params: { path: { id }, query: { revoke: true } } })),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['offline', 'downloads'] }),
  })
}
