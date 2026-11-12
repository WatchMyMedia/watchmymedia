import createClient from 'openapi-fetch'
import type { paths } from './schema'

// Typed client generated from the server's OpenAPI document (pnpm gen:api).
export const api = createClient<paths>({ baseUrl: '/' })

/** Fired on any 401 outside the auth routes so the app can show the sign-in screen. */
export const UNAUTHORIZED_EVENT = 'watchmymedia:unauthorized'

api.use({
  onResponse({ response }) {
    if (response.status === 401 && !response.url.includes('/api/auth/') && !response.url.includes('/api/setup')) {
      window.dispatchEvent(new Event(UNAUTHORIZED_EVENT))
    }
    return response
  },
})

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

/** Unwraps an openapi-fetch result for use as a TanStack Query queryFn. */
export function unwrap<T>(res: { data?: T; error?: unknown; response: Response }): T {
  if (res.error !== undefined || (res.data === undefined && res.response.status !== 204)) {
    throw new ApiError(res.response, res.error)
  }
  return res.data as T
}
