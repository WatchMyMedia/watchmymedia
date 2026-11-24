import createClient from 'openapi-fetch'
import type { paths } from '../../src/api/schema'
import type { ApiClient } from '../../src/api/client'

export type ClientOptions = {
  /** The account's session token, sent as Authorization: Bearer. */
  token?: string
  /** A 401 from any route but signing in: the session was ended elsewhere. */
  onUnauthorized?: () => void
  /** Each request's bound; TVs have no AbortSignal.timeout to lean on everywhere. */
  timeoutMs?: number
  /** Stands in for the global fetch (tests); by default the global one, looked up per request. */
  fetch?: typeof fetch
  /**
   * Awaited before the sign-in goes on any request, and the request fails
   * with what it throws: for a server that isn't the current one, the check
   * that it is the server remembered at its address (`PeerServer`).
   */
  beforeSignIn?: (signal: AbortSignal) => Promise<void>
}

/** Routes whose 401 is an answer about signing in, not a lost session. */
const SIGN_IN = /^\/api\/auth\/(login|pair)/

/**
 * The typed client for one server, as the TV calls it: cross-origin, bearer
 * auth, never cookies (`credentials: 'omit'`), and a bound on every request
 * so a server that went away can't leave a screen waiting. It is the same
 * openapi-fetch client type as the web app's, so `setApiClient` can hand it to
 * the web's query options.
 */
export function bearerClient(baseUrl: string, { token, onUnauthorized, timeoutMs = 20_000, fetch: fetcher, beforeSignIn }: ClientOptions = {}): ApiClient {
  const timers = new WeakMap<Request, ReturnType<typeof setTimeout>>()
  // fetch is looked up per request, so the browser preview's fakes and tests can stand in for it.
  const client = createClient<paths>({ baseUrl, credentials: 'omit', fetch: (request) => (fetcher ?? fetch)(request) })
  client.use({
    async onRequest({ request }) {
      if (token && beforeSignIn) await beforeSignIn(request.signal)
      if (token) request.headers.set('Authorization', `Bearer ${token}`)
      const controller = new AbortController()
      const abort = () => controller.abort()
      if (request.signal.aborted) abort()
      else request.signal.addEventListener('abort', abort, { once: true })
      const bounded = new Request(request, { signal: controller.signal })
      timers.set(bounded, setTimeout(abort, timeoutMs))
      return bounded
    },
    onResponse({ request, response }) {
      clearTimeout(timers.get(request))
      if (response.status === 401 && token && !SIGN_IN.test(new URL(request.url).pathname)) onUnauthorized?.()
      return response
    },
    onError({ request }) {
      clearTimeout(timers.get(request))
    },
  })
  return client
}
