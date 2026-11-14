import { queryOptions } from '@tanstack/react-query'
import { api, unwrap } from './client'
import type { components } from './schema'

export type Session = components['schemas']['Session']
export type PairingInfo = components['schemas']['PairingInfo']

/** Where the account is signed in; `all` is everyone's, for admins. */
export const sessionsQuery = (all: boolean) =>
  queryOptions({
    queryKey: ['sessions', all],
    queryFn: async () => unwrap(await api.GET('/api/auth/sessions', { params: { query: { all: all || undefined } } })),
  })

/** Signs another device out. This browser signs out with the auth context's logout. */
export async function revokeSession(id: string) {
  return unwrap(await api.DELETE('/api/auth/sessions/{id}', { params: { path: { id } } }))
}

/** Changes your own password; the server signs every other device out. */
export async function changePassword(current: string, next: string) {
  return unwrap(await api.PUT('/api/auth/password', { body: { current, new: next } }))
}

/** The device waiting behind a pairing code. Wrong codes count like failed sign-ins. */
export const pairingQuery = (code: string) =>
  queryOptions({
    queryKey: ['pairing', code],
    retry: false,
    queryFn: async () => unwrap(await api.GET('/api/auth/pair/{code}', { params: { path: { code } } })),
  })

/** Signs the device behind a code in as yourself. */
export async function approvePairing(code: string) {
  return unwrap(await api.POST('/api/auth/pair/{code}/approve', { params: { path: { code } } }))
}
