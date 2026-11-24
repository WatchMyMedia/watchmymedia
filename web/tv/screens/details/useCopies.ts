import { useEffect, useMemo, useState } from 'react'
import { useQueries, useQueryClient } from '@tanstack/react-query'
import { unwrap, type ApiClient } from '../../../src/api/client'
import { findMovieCopies, findShowCopies } from '../../../src/api/copies'
import { invalidateWatch } from '../../../src/api/watch'
import type { components } from '../../../src/api/schema'
import { useLevel34, usePeers, useSession } from '../../app/hooks'
import { askAt34, currentSource, peerSource, type Source } from '../../session/peerRegistry'
import type { CopyServer, ServerCopy } from './copies'
import { movieVersions } from './movieLabels'

// A title page asks the other servers this TV is signed in to whether they
// have its title, by TMDB id, as soon as it opens: in the background, each
// server asked which one it is first (its client does), 4 s each, and one
// that doesn't answer in time is simply left out. Only from API level 34 on
// the current server, and only servers at level 34 or later are asked.

type MovieDetail = components['schemas']['MovieDetail']
type ShowDetail = components['schemas']['ShowDetail']

/** Each server gets this long, as search gives it. */
export const COPY_MS = 4000

/** Runs `ask` with its own signal, cut off after `ms`. */
export async function within<T>(ms: number, outer: AbortSignal, ask: (signal: AbortSignal) => Promise<T>): Promise<T> {
  const controller = new AbortController()
  const stop = () => controller.abort()
  if (outer.aborted) stop()
  else outer.addEventListener('abort', stop, { once: true })
  const timer = setTimeout(stop, ms)
  try { return await ask(controller.signal) } finally { clearTimeout(timer); outer.removeEventListener('abort', stop) }
}

export function copyServer(source: Source, currentId: string): CopyServer {
  return { id: source.id, name: source.name, current: source.id === currentId, away: source.away }
}

/** The servers a page asks for copies: the current one (when the page is another's) and every other signed-in server but the page's. */
function useAsked(page: Source): Source[] {
  const session = useSession()
  const peers = usePeers()
  const on = useLevel34()
  return useMemo(() => {
    if (!on) return []
    const all = [currentSource(session), ...peers.map(peerSource)]
    return all.filter((s) => s.id !== page.id)
  }, [on, session, peers, page.id])
}

/** Asks one server: through its own client, a peer only once it says it answers level 34. */
function ask<T>(source: Source, signal: AbortSignal, fn: (api: ApiClient, signal: AbortSignal) => Promise<T>): Promise<T | undefined> {
  return within(COPY_MS, signal, (s) => (source.peer ? askAt34(source.peer, s, fn) : fn(source.api, s)))
}

export type FoundCopy<T> = { source: Source; detail: T }

/** Still out after this long, the panel says which server it waits for. */
const SLOW_MS = 1000

/** The names of the servers still being asked after a moment, for "Still asking Den server…". */
export function useSlow(pending: readonly string[]): string[] {
  const key = pending.join('|')
  // The set of servers that has been out for a moment: a different set starts the moment again.
  const [late, setLate] = useState<string>()
  useEffect(() => {
    if (!key) return
    const t = setTimeout(() => setLate(key), SLOW_MS)
    return () => clearTimeout(t)
  }, [key])
  return key && late === key ? [...pending] : []
}

/** A movie's copies on the other servers: each server's MovieDetail for it, as they answer. */
export function useMovieCopies(tmdbId: number, page: Source): { found: FoundCopy<MovieDetail>[]; asking: string[] } {
  const asked = useAsked(page)
  const results = useQueries({
    queries: asked.map((source) => ({
      queryKey: ['oneCard', source.id, 'movie', tmdbId],
      queryFn: async ({ signal }: { signal: AbortSignal }) => {
        const found = await ask(source, signal, async (api, s) => {
          const copies = await findMovieCopies(tmdbId, s, api)
          const first = copies.items[0]
          return first ? unwrap(await api.GET('/api/movies/{id}', { params: { path: { id: first.id } }, signal: s })) : null
        })
        return found ?? null
      },
      retry: false,
      staleTime: 60_000,
      enabled: tmdbId > 0,
    })),
  })
  const found = asked.flatMap((source, i) => (results[i]?.data ? [{ source, detail: results[i].data as MovieDetail }] : []))
  const asking = useSlow(asked.filter((_, i) => results[i]?.isPending && tmdbId > 0).map((s) => s.name))
  return { found, asking }
}

/** A show's copies on the other servers: each server's ShowDetail for it, as they answer. */
export function useShowCopies(tmdbId: number, page: Source): { found: FoundCopy<ShowDetail>[]; asking: string[] } {
  const asked = useAsked(page)
  const results = useQueries({
    queries: asked.map((source) => ({
      queryKey: ['oneCard', source.id, 'show', tmdbId],
      queryFn: async ({ signal }: { signal: AbortSignal }) => {
        const found = await ask(source, signal, async (api, s) => {
          const copies = await findShowCopies(tmdbId, s, api)
          const first = copies.items[0]
          return first ? unwrap(await api.GET('/api/shows/{id}', { params: { path: { id: first.id } }, signal: s })) : null
        })
        return found ?? null
      },
      retry: false,
      staleTime: 60_000,
      enabled: tmdbId > 0,
    })),
  })
  const found = asked.flatMap((source, i) => (results[i]?.data ? [{ source, detail: results[i].data as ShowDetail }] : []))
  const asking = useSlow(asked.filter((_, i) => results[i]?.isPending && tmdbId > 0).map((s) => s.name))
  return { found, asking }
}

/** A movie's copy as the copies model takes it. */
export function movieCopy(source: Source, currentId: string, m: MovieDetail): ServerCopy {
  return { server: copyServer(source, currentId), titleId: m.id, files: movieVersions(m), watch: m.watch }
}

/**
 * Marks a title watched or not on every server that has it, as the person
 * sees one card for it: each through its own client. Refetches what shows
 * marks here, the other servers' pages and their copies.
 */
export function useMarkEverywhere() {
  const qc = useQueryClient()
  const [busy, setBusy] = useState(false)
  const mark = async (targets: { source: Source; type: 'movie' | 'episode'; id: number }[], watched: boolean) => {
    setBusy(true)
    try {
      const results = await Promise.allSettled(targets.map(async ({ source, type, id }) => {
        const params = { params: { path: { type, id } } }
        unwrap(watched ? await source.api.POST('/api/watch/{type}/{id}/watched', params) : await source.api.DELETE('/api/watch/{type}/{id}/watched', params))
      }))
      const failed = results.find((r): r is PromiseRejectedResult => r.status === 'rejected')
      if (failed) throw failed.reason
    } finally {
      setBusy(false)
      invalidateWatch(qc)
      void qc.invalidateQueries({ predicate: ({ queryKey }) => queryKey[0] === 'peer' || queryKey[0] === 'oneCard' })
    }
  }
  return { mark, busy }
}
