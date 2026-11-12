import createClient from 'openapi-fetch'
import type { paths } from '../src/api/schema'

/** Only locally written messages may be shown directly. */
export class UserError extends Error {}

export class ApiError extends UserError {
  constructor(readonly status: number) {
    super(status === 401 ? 'The TV has been signed out. Link it again.'
      : status === 403 ? 'The server refused this request. Check the server address and account access.'
      : status === 404 ? 'This item or pairing code is no longer available.'
      : status >= 500 ? `The server is unavailable (HTTP ${status}). Check that it’s running and try again.`
      : `The server returned HTTP ${status}.`)
  }
}

export function result<T>(reply: { data?: T; error?: unknown; response: Response }): T {
  // Never surface arbitrary server/error text: it can contain URLs or tokens.
  if (!reply.response.ok) throw new ApiError(reply.response.status)
  return reply.data as T
}

export function serverUrl(input: string): string {
  let url: URL
  try { url = new URL(input.trim()) } catch { throw new UserError('Enter a complete http:// or https:// server address.') }
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.search || url.hash || url.pathname !== '/') {
    throw new UserError('Use the server’s http:// or https:// address without a path, password, query or fragment.')
  }
  return url.origin
}

/** Only same-server API URLs can receive a media token. Never attach a session token. */
export function mediaUrl(baseUrl: string, path: string, token: string): string {
  const base = serverUrl(baseUrl)
  const url = new URL(path, `${base}/`)
  const mediaPath = /^\/api\/(files\/\d+\/stream|playback\/[^/]+\/.+|files\/\d+\/subtitles\/[^/]+\.(vtt|ass)|images\/.+)$/.test(url.pathname)
  if (url.origin !== base || url.username || url.password || !mediaPath) {
    throw new UserError('The server returned an unexpected media address.')
  }
  url.hash = ''
  url.searchParams.set('access_token', token)
  return url.href
}

export function tvClient(baseUrl: string, token = '', fetcher: typeof fetch = fetch) {
  const api = createClient<paths>({ baseUrl: serverUrl(baseUrl), credentials: 'omit', fetch: fetcher })
  api.use({ onRequest({ request }) {
    if (token) request.headers.set('Authorization', `Bearer ${token}`)
    return request
  } })
  return api
}

export type TVClient = ReturnType<typeof tvClient>

/** AbortSignal.timeout/any are newer than the stock QN90D browser. */
export async function timed<T>(operation: (signal: AbortSignal) => Promise<T>, parent?: AbortSignal, ms = 15_000): Promise<T> {
  const controller = new AbortController()
  const abort = () => controller.abort()
  if (parent?.aborted) controller.abort()
  parent?.addEventListener('abort', abort, { once: true })
  const timeout = setTimeout(abort, ms)
  try { return await operation(controller.signal) }
  finally { clearTimeout(timeout); parent?.removeEventListener('abort', abort) }
}

export function safeError(error: unknown): string {
  if (error instanceof UserError) return error.message
  if (error instanceof DOMException && error.name === 'AbortError') return 'The request timed out or was cancelled. Try again.'
  return 'The operation failed. Check the connection and try again.'
}
