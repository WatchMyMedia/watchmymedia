import createClient, { type Client } from 'openapi-fetch'
import type { paths } from './schema'

export type ApiClient = Client<paths>

/** Fired on any 401 outside the auth routes so the app can show the sign-in screen. */
export const UNAUTHORIZED_EVENT = 'watchmymedia:unauthorized'

// Typed client generated from the server's OpenAPI document (pnpm gen:api),
// for the page's own server with its cookie.
const pageClient = createClient<paths>({ baseUrl: '/' })

pageClient.use({
  onResponse({ response }) {
    if (response.status === 401 && !response.url.includes('/api/auth/') && !response.url.includes('/api/setup')) {
      window.dispatchEvent(new Event(UNAUTHORIZED_EVENT))
    }
    return response
  },
})

/**
 * The client every query and mutation here calls. The web app and the Cast
 * receiver never change it. An app outside the browser app (the Samsung TV
 * app, `tv/`) reuses these queries with its own client, for another
 * server and a bearer token, through setApiClient; ES modules see the new
 * value wherever `api` was imported.
 */
export let api: ApiClient = pageClient

/** Swaps the client for one outside the browser app; the web app never calls it. */
export function setApiClient(client: ApiClient) {
  api = client
}

/** Keep the problem type and retry delay for password confirmation and throttling. */
export class ApiError extends Error {
  readonly status: number
  readonly type?: string
  readonly retryAfter: number
  constructor(response: Response, problem: unknown) {
    super(typeof problem === 'object' && problem && 'detail' in problem ? String(problem.detail) : `Request failed with status ${response.status}`)
    this.name = 'ApiError'
    this.status = response.status
    this.type = typeof problem === 'object' && problem && 'type' in problem ? String(problem.type) : undefined
    this.retryAfter = Math.max(1, Number(response.headers.get('Retry-After')) || 30)
  }
}

/**
 * Unwraps an openapi-fetch result for use as a TanStack Query queryFn. Any 2xx
 * succeeds, and one without a body (a 204, or a 202 that only says the work
 * started, as Search now's does) gives undefined; anything else throws.
 */
export function unwrap<T>(res: { data?: T; error?: unknown; response: Response }): T {
  if (res.error !== undefined || !res.response.ok) {
    throw new ApiError(res.response, res.error)
  }
  return res.data as T
}
