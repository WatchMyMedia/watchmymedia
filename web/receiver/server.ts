import createClient from 'openapi-fetch'
import type { paths } from '../src/api/schema'
import type { LoadData } from '../src/cast/protocol'

export type CastCredential = { token: string }

/** Pending reports retain this play's origin and credential, including renewal. */
export function castClient(baseUrl: string, credential: CastCredential, fetcher: typeof fetch = fetch) {
  const client = createClient<paths>({ baseUrl, fetch: fetcher })
  client.use({ onRequest({ request }) {
    request.headers.set('Authorization', `Bearer ${credential.token}`)
    return request
  } })
  return client
}

/** A public ID cannot move a cached endpoint between different LOAD addresses. */
export const castServerKey = (server: LoadData['server']) => JSON.stringify([server.id, server.urls])
