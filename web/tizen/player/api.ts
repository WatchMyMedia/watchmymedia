import type { Client } from 'openapi-fetch'
import type { components, paths } from '../../src/api/schema'
import { withRequestTimeout } from '../../src/lib/requestTimeout'

/** The typed client the shell made for one server and sign-in: bearer auth, no cookies. */
export type PlayerApi = Client<paths>

type Schemas = components['schemas']
export type Decision = Schemas['Decision']
export type DecisionRequest = Schemas['Request']
export type Profile = Schemas['Profile']
export type ClientInfo = Schemas['Client']
export type AudioTrack = Schemas['AudioTrack']
export type SubtitleTrack = Schemas['SubtitleTrack']
export type SubtitleSearch = Schemas['SubtitleSearch']
export type OnlineSubtitle = Schemas['OnlineSubtitle']
export type SubtitlePreferences = Schemas['SubtitlePreferences']
export type Preferences = Schemas['Preferences']
export type Progress = Schemas['Progress']
export type ProgressBody = Schemas['ProgressInputBody']
export type Directive = Schemas['ActivityDirective']
export type Segment = Schemas['Segment']
export type UpNext = Schemas['UpNext']
export type MediaToken = Schemas['MediaToken']
export type Trickplay = Schemas['Trickplay']
export type LiveSession = Schemas['LiveTVSessionView']
export type LiveChannel = Schemas['LiveTVChannel']
export type LiveCapabilities = Schemas['LiveTVCapabilities']

/** A message written here, safe to show. The server's own text never is: it can carry URLs or tokens. */
export class PlayerError extends Error {
  constructor(message: string, readonly status?: number, readonly reason?: string, readonly retryAfter?: string | null) {
    super(message)
  }
}

export function statusMessage(status: number): string {
  if (status === 401) return 'The TV has been signed out. Link it again.'
  if (status === 403) return 'The server refused this. Check your account’s access.'
  if (status === 404) return 'This is no longer on the server.'
  if (status === 409) return 'The server isn’t ready for this yet. Try again in a moment.'
  if (status === 429) return 'The server has had too many requests. Try again later.'
  if (status >= 500) return `The server is unavailable (HTTP ${status}). Check that it’s running and try again.`
  return `The server returned HTTP ${status}.`
}

/** A reply's reason code (tuners_busy, no_signal): only a bare code, never a sentence. */
export function reasonOf(error: unknown): string | undefined {
  const detail = (error as { detail?: unknown } | undefined)?.detail
  return typeof detail === 'string' && /^[a-z_]{1,64}$/.test(detail) ? detail : undefined
}

type Reply<T> = { data?: T; error?: unknown; response: Response }

/** Sends one request, bounded in time; its data, or a PlayerError naming only the status. */
export async function call<T>(request: (signal: AbortSignal) => Promise<Reply<T>>, ms = 15_000): Promise<T> {
  const reply = await withRequestTimeout(request, ms)
  if (!reply.response.ok) {
    const status = reply.response.status
    throw new PlayerError(statusMessage(status), status, reasonOf(reply.error), reply.response.headers.get('Retry-After'))
  }
  return reply.data as T
}

/** Words for any failure: ours as they are, a timeout or a lost connection said plainly. */
export function safeMessage(error: unknown, fallback = 'Something went wrong. Check the connection and try again.'): string {
  if (error instanceof PlayerError) return error.message
  if (error instanceof DOMException && (error.name === 'TimeoutError' || error.name === 'AbortError')) return 'The server took too long to answer. Check the connection and try again.'
  if (error instanceof TypeError) return 'The server couldn’t be reached. Check the connection and try again.'
  return fallback
}
