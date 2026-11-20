import type { components } from '../../../src/api/schema'

// Search across the servers this TV is signed in to. The current server
// answers through the session's own query, as it always has; each other
// server is asked by itself here, and `mergeResults` puts them together:
// the current server's titles first, each other server's after them in the
// order Servers lists them, and nothing already on screen ever moves.

type SearchResults = components['schemas']['SearchResults']
type Movie = components['schemas']['Movie']
type Show = components['schemas']['Show']
type SearchEpisode = components['schemas']['SearchEpisode']

/** A server other than the current one, asked with its own sign-in at its own address. */
export type Asked = { id: string; name: string; search(q: string, signal: AbortSignal): Promise<SearchResults> }

/**
 * Where one server's answer stands. While new words are out, `results` are
 * still the last words' (as the current server's are), so its cards stay
 * where they are until the new ones replace them.
 */
export type Part = { id: string; name: string; state: 'asking' | 'done' | 'failed'; slow: boolean; results?: SearchResults }

/** Each server gets this long to answer. */
export const SERVER_MS = 4000
/** Still out after this long, the results say which server they are waiting for. */
export const SLOW_MS = 1000
/** A server that failed isn't asked again for this long on a visit to Search, so typing doesn't queue timeouts. */
export const ASK_AGAIN_MS = 30_000

type Timers = {
  now(): number
  setTimeout(fn: () => void, ms: number): ReturnType<typeof setTimeout>
  clearTimeout(id: ReturnType<typeof setTimeout>): void
}
const realTimers: Timers = { now: () => Date.now(), setTimeout: (fn, ms) => setTimeout(fn, ms), clearTimeout: (id) => clearTimeout(id) }

type Call = { controller: AbortController; slow: ReturnType<typeof setTimeout>; limit: ReturnType<typeof setTimeout> }

/**
 * One visit to Search asking the other servers, outside React. `search`
 * asks them all at once; a keystroke (`cancel`) stops every call still out;
 * a server gets SERVER_MS, and one that failed waits ASK_AGAIN_MS (or Try
 * again) before it is asked once more.
 */
export class CrossSearch {
  private parts: Part[] = []
  private q = ''
  private interrupted = false
  private calls = new Map<string, Call>()
  private failedAt = new Map<string, number>()
  private listeners = new Set<() => void>()

  constructor(private servers: readonly Asked[], private timers: Timers = realTimers) {}

  readonly subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener) } }
  readonly snapshot = () => this.parts

  private emit(parts: Part[]) {
    this.parts = parts
    for (const l of this.listeners) l()
  }

  private update(id: string, change: Partial<Part>) {
    this.emit(this.parts.map((p) => p.id === id ? { ...p, ...change } : p))
  }

  /** Asks every server for `q`; with no words, nothing is asked and nothing is kept. */
  search(q: string) {
    if (q === this.q && !this.interrupted) return
    this.cancel()
    this.interrupted = false
    this.q = q
    if (!q) { this.emit([]); return }
    const now = this.timers.now()
    const before = new Map(this.parts.map((p) => [p.id, p]))
    const waiting = (id: string) => { const at = this.failedAt.get(id); return at !== undefined && now - at < ASK_AGAIN_MS }
    this.emit(this.servers.map((s): Part => waiting(s.id)
      ? { id: s.id, name: s.name, state: 'failed', slow: false }
      : { id: s.id, name: s.name, state: 'asking', slow: false, results: before.get(s.id)?.results }))
    for (const s of this.servers) if (!waiting(s.id)) this.ask(s, q)
  }

  private ask(server: Asked, q: string) {
    const controller = new AbortController()
    const mine = () => this.calls.get(server.id) === call
    const end = () => {
      this.timers.clearTimeout(call.slow)
      this.timers.clearTimeout(call.limit)
      this.calls.delete(server.id)
    }
    const fail = () => {
      if (!mine()) return
      end()
      this.failedAt.set(server.id, this.timers.now())
      this.update(server.id, { state: 'failed', slow: false, results: undefined })
    }
    const call: Call = {
      controller,
      slow: this.timers.setTimeout(() => { if (mine()) this.update(server.id, { slow: true }) }, SLOW_MS),
      limit: this.timers.setTimeout(() => { fail(); controller.abort() }, SERVER_MS),
    }
    this.calls.set(server.id, call)
    server.search(q, controller.signal).then((results) => {
      if (!mine()) return
      end()
      this.update(server.id, { state: 'done', slow: false, results })
    }, fail)
  }

  /** A keystroke: every call still out is cancelled. */
  cancel() {
    if (!this.calls.size) return
    this.interrupted = true
    for (const call of this.calls.values()) {
      this.timers.clearTimeout(call.slow)
      this.timers.clearTimeout(call.limit)
      call.controller.abort()
    }
    this.calls.clear()
  }

  /** Try again, on a server that didn't answer: asked now, whatever it did a moment ago. */
  retry(id: string) {
    const server = this.servers.find((s) => s.id === id)
    if (!server || !this.q || this.calls.has(id)) return
    this.failedAt.delete(id)
    this.update(id, { state: 'asking', slow: false, results: undefined })
    this.ask(server, this.q)
  }

  /** Search is left: nothing is out, and nothing more is said. */
  stop() {
    this.cancel()
    this.listeners.clear()
  }
}

/** A result, and the server it is on when that isn't the current one. */
export type Tagged<T> = { item: T; server?: { id: string; name: string } }

export type Merged = {
  shows: Tagged<Show>[]
  movies: Tagged<Movie>[]
  episodes: Tagged<SearchEpisode>[]
  /** The servers still out after a second, in Servers' order. */
  asking: string[]
  /** The servers whose titles aren't listed. */
  failed: { id: string; name: string }[]
  /** Every server has answered or failed for these words. */
  settled: boolean
  /** How many answered, the current one included. */
  answered: number
}

/**
 * The results as they are drawn. The current server's come first and carry
 * no server. Each other server's are added to the end of each group in
 * Servers' order, and only once every server before it has answered or
 * failed and the current server's are in: a server that answers early
 * waits its turn, so a card on screen never moves.
 */
export function mergeResults(current: SearchResults | undefined, currentDone: boolean, parts: readonly Part[]): Merged {
  const merged: Merged = {
    shows: (current?.shows ?? []).map((item) => ({ item })),
    movies: (current?.movies ?? []).map((item) => ({ item })),
    episodes: (current?.episodes ?? []).map((item) => ({ item })),
    asking: parts.filter((p) => p.state === 'asking' && p.slow).map((p) => p.name),
    failed: parts.filter((p) => p.state === 'failed').map((p) => ({ id: p.id, name: p.name })),
    settled: currentDone && parts.every((p) => p.state !== 'asking'),
    answered: (currentDone && current ? 1 : 0) + parts.filter((p) => p.state === 'done').length,
  }
  let open = !!current || currentDone
  for (const p of parts) {
    if (p.state === 'failed') continue
    if (!p.results) open = false
    if (!open || !p.results) continue
    const server = { id: p.id, name: p.name }
    for (const item of p.results.shows) merged.shows.push({ item, server })
    for (const item of p.results.movies) merged.movies.push({ item, server })
    for (const item of p.results.episodes) merged.episodes.push({ item, server })
  }
  return merged
}

/** A result's key in its row: its id, with its server's when it is on another one (ids are each server's own). */
export function resultKey(tagged: Tagged<{ id: number }>): string {
  return tagged.server ? `${tagged.server.id}:${tagged.item.id}` : String(tagged.item.id)
}
