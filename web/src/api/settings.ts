import { useAuth } from '../authContext'
import { withConfirm } from './withConfirm'
import { queryOptions, useMutation, useQueryClient } from '@tanstack/react-query'
import { api, unwrap } from './client'
import type { components } from './schema'

export type ActivitySettings = components['schemas']['ActivitySettings']

export const settingsQuery = queryOptions({
  queryKey: ['settings'],
  queryFn: async () => unwrap(await api.GET('/api/settings')),
})

export const browseQuery = (path: string) =>
  queryOptions({
    queryKey: ['browse', path],
    queryFn: async () => unwrap(await api.GET('/api/settings/browse', { params: { query: { path: path || undefined } } })),
  })

export function checkPath(path: string) {
  return api.POST('/api/settings/check-path', { body: { path } }).then(unwrap)
}

export function testTMDB(apiKey: string) {
  return api.POST('/api/settings/tmdb/test', { body: { apiKey } }).then(unwrap)
}

export function checkTurnstile(token: string) {
  return api.POST('/api/settings/turnstile/check', { body: { token } }).then(unwrap)
}

export type UpdateStatus = components['schemas']['UpdateStatus']

/** What the last update check found. Reading it never checks. */
export const updateStatusQuery = queryOptions({
  queryKey: ['updates'],
  queryFn: async () => unwrap(await api.GET('/api/settings/updates')),
})

/** Asks the server to fetch the release list now. */
export function useCheckForUpdates() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async () => unwrap(await api.POST('/api/settings/updates/check')),
    onSuccess: (s) => qc.setQueryData(updateStatusQuery.queryKey, s),
  })
}

function useInvalidate(keys: string[]) {
  const qc = useQueryClient()
  return () => keys.forEach((k) => void qc.invalidateQueries({ queryKey: [k] }))
}

export function useUpdateSettings() {
 const auth = useAuth()
  const inv = useInvalidate(['settings', 'health'])
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async (body: components['schemas']['UpdateSettingsInputBody']) =>
      withConfirm(async () => unwrap(await api.PUT('/api/settings', { body })), body.upload ? 'change the remote upload limit' : 'change sign-in protection'),
    onSuccess: async (settings, body) => {
      qc.setQueryData(settingsQuery.queryKey, settings)
      inv()
      void qc.invalidateQueries({ queryKey: ['activity'] })
      void qc.invalidateQueries({ queryKey: ['trickplay'] })
      if (body.remoteAdmin !== undefined) await auth.refresh()
    },
  })
}

/** How far seek thumbnails have got: the Seek thumbnails card asks again while there is work. */
export const trickplayQuery = queryOptions({
  queryKey: ['trickplay'],
  queryFn: async () => unwrap(await api.GET('/api/trickplay')),
})

/** Deletes every file's seek thumbnails. */
export function useDeleteTrickplay() {
  const inv = useInvalidate(['trickplay', 'settings'])
  return useMutation({
    mutationFn: async () => unwrap(await api.DELETE('/api/trickplay')),
    onSuccess: inv,
  })
}

export function useCreateLibrary() {
  const inv = useInvalidate(['libraries', 'home'])
  return useMutation({
    mutationFn: async (body: { type: 'movie' | 'tv'; name: string; path: string }) => withConfirm(async () => unwrap(await api.POST('/api/libraries', { body })), 'add a library'),
    onSuccess: inv,
  })
}

export function useUpdateLibrary() {
  const inv = useInvalidate(['libraries', 'home'])
  return useMutation({
    mutationFn: async ({ id, ...body }: { id: number; name: string; path: string }) =>
      withConfirm(async () => unwrap(await api.PUT('/api/libraries/{id}', { params: { path: { id } }, body })), 'change this library’s folders'),
    onSuccess: inv,
  })
}

export function useDeleteLibrary() {
  const inv = useInvalidate(['libraries', 'home', 'items', 'rows'])
  return useMutation({
    mutationFn: async (id: number) => unwrap(await api.DELETE('/api/libraries/{id}', { params: { path: { id } } })),
    onSuccess: inv,
  })
}

export function useCreateBackup() {
  const inv = useInvalidate(['settings'])
  return useMutation({
    mutationFn: async () => withConfirm(async () => unwrap(await api.POST('/api/backups')), 'make a backup'),
    onSuccess: inv,
  })
}

/** Check confirmation before starting the browser's streamed download. */
export function useDownloadBackup() {
  return useMutation({
    mutationFn: () => withConfirm(async () => unwrap(await api.POST('/api/backups/download')), 'download a backup'),
    onSuccess: () => { window.location.assign('/api/backups/download') },
  })
}
