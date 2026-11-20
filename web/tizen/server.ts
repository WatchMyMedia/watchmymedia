// What the prototype's player files (avplay.ts, browser-player.ts,
// playback.ts, profile.ts) import from here, kept for them until the
// player engine in player/ replaces them. New code uses session/ and the
// web's src/api and src/lib directly; delete this file when nothing imports it.
import type { ApiClient } from '../src/api/client'
import { withRequestTimeout } from '../src/lib/requestTimeout'
import { bearerClient } from './session/client'
import { tokenedUrl } from './session/media'

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

/** Only same-server media routes (session/media.ts) can receive a media token. Never attach a session token. */
export function mediaUrl(baseUrl: string, path: string, token: string): string {
  const url = tokenedUrl(serverUrl(baseUrl), path, token)
  if (!url) throw new UserError('The server returned an unexpected media address.')
  return url
}

export function tvClient(baseUrl: string, token = '', fetcher?: typeof fetch): ApiClient {
  return bearerClient(serverUrl(baseUrl), { token: token || undefined, fetch: fetcher, timeoutMs: 60_000 })
}

export type TVClient = ApiClient

/** A request bounded by `ms` (src/lib/requestTimeout.ts) that also stops with `parent`. */
export function timed<T>(operation: (signal: AbortSignal) => Promise<T>, parent?: AbortSignal, ms = 15_000): Promise<T> {
  return withRequestTimeout((signal) => {
    if (!parent) return operation(signal)
    const controller = new AbortController()
    const abort = () => controller.abort()
    if (parent.aborted || signal.aborted) abort()
    parent.addEventListener('abort', abort, { once: true })
    signal.addEventListener('abort', abort, { once: true })
    return operation(controller.signal).finally(() => parent.removeEventListener('abort', abort))
  }, ms)
}

export function safeError(error: unknown): string {
  if (error instanceof UserError) return error.message
  if (error instanceof DOMException && (error.name === 'AbortError' || error.name === 'TimeoutError')) return 'The request timed out or was cancelled. Try again.'
  return 'The operation failed. Check the connection and try again.'
}
