import { withConfirm } from './withConfirm'
import { queryOptions, useMutation, useQueryClient } from '@tanstack/react-query'
import { api, unwrap } from './client'
import type { components } from './schema'

export type Integration = components['schemas']['Integration']
export type IntegrationDetail = components['schemas']['IntegrationDetail']
export type PathMapping = components['schemas']['PathMapping']
export type RequestTarget = components['schemas']['RequestTarget']
export type IntegrationKind = Integration['kind']
export type SeerrUser = components['schemas']['SeerrUser']

/** Radarr, Sonarr and Seerr connections, and the addresses an app may reach this server at. */
export const integrationsQuery = queryOptions({
  queryKey: ['integrations'],
  queryFn: async () => unwrap(await api.GET('/api/integrations')),
  // Webhook times and errors change on their own.
  refetchInterval: 30_000,
})

/** One connection with what the app says now: root folders, quality profiles, suggested mappings. */
export const integrationQuery = (id: number) =>
  queryOptions({
    queryKey: ['integrations', id],
    queryFn: async () => unwrap(await api.GET('/api/integrations/{id}', { params: { path: { id } } })),
  })

type Fields = { name?: string; url?: string; apiKey?: string; pathMap?: PathMapping[]; targets?: RequestTarget[] }

function useInvalidateIntegrations() {
  const qc = useQueryClient()
  return () => void qc.invalidateQueries({ queryKey: ['integrations'] })
}

export function useCreateIntegration() {
  const inv = useInvalidateIntegrations()
  return useMutation({
    mutationFn: async (body: Fields & { kind: IntegrationKind }) => withConfirm(async () => unwrap(await api.POST('/api/integrations', { body })), 'connect this app'),
    onSuccess: inv,
  })
}

export function useUpdateIntegration(id: number) {
  const inv = useInvalidateIntegrations()
  return useMutation({
    mutationFn: async (body: Fields) => withConfirm(async () => unwrap(await api.PATCH('/api/integrations/{id}', { params: { path: { id } }, body })), 'change this connection'),
    onSuccess: inv,
  })
}

export function useDeleteIntegration() {
  const inv = useInvalidateIntegrations()
  return useMutation({
    mutationFn: async (id: number) => withConfirm(async () => unwrap(await api.DELETE('/api/integrations/{id}', { params: { path: { id } } })), 'disconnect this app'),
    onSuccess: inv,
  })
}

export function testIntegration(body: { kind: IntegrationKind; url: string; apiKey?: string; id?: number }) {
  return withConfirm(async () => api.POST('/api/integrations/test', { body }).then(unwrap), 'test this connection')
}

export function useConnectWebhook(id: number) {
  const inv = useInvalidateIntegrations()
  return useMutation({
    mutationFn: async (address: string) =>
      withConfirm(async () => unwrap(await api.POST('/api/integrations/{id}/webhook/connect', { params: { path: { id } }, body: { address } })), 'connect this app’s webhook'),
    onSuccess: inv,
  })
}

/** Seerr's users, read from Seerr now, for linking people on Settings › Users. */
export const seerrUsersQuery = queryOptions({
  queryKey: ['integrations', 'seerr', 'users'],
  queryFn: async () => unwrap(await api.GET('/api/integrations/seerr/users')),
})

/** Links a person to a Seerr user; 0 unlinks. */
export function useLinkSeerrUser() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async ({ id, seerrUserId }: { id: number; seerrUserId: number }) =>
      unwrap(await api.PATCH('/api/users/{id}', { params: { path: { id } }, body: { seerrUserId } })),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['users'] }),
  })
}

export const appName: Record<IntegrationKind, string> = { radarr: 'Radarr', sonarr: 'Sonarr', seerr: 'Seerr' }
