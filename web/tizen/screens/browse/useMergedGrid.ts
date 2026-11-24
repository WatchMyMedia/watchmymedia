import { useMemo, useState } from 'react'
import { useQueries, type UseQueryResult } from '@tanstack/react-query'
import { unwrap } from '../../../src/api/client'
import { fetchItems } from '../../../src/api/queries'
import type { components } from '../../../src/api/schema'
import type { LibraryType } from '../../../src/lib/browseOptions'
import { useLevel34, usePeers, useSession } from '../../app/hooks'
import { askAt34 } from '../../session/peerRegistry'
import { serverHeading } from '../details/copies'
import { COPY_MS, useSlow, within } from '../details/useCopies'
import { PAGE_SIZE } from './gridPages'
import { gridQuery, narrowed, type GridChoices } from './libraryView'
import { mergedCounts, mergeStreams, type GridCounts, type Stream } from './mergedGrid'
import type { TaggedItem } from './oneCardHome'

// Movies and TV Shows from every signed-in server at API level 34: each
// server's first library of the type, asked a page at a time in the
// person's sort and filters, merged one card a title. A server's next page
// is asked for only when the merge needs it to go on. Each server is asked
// which one it is first (its own client does), with 4 s to answer each
// call; one that doesn't is left out, with Try again in the header.

type ItemList = components['schemas']['ItemList']
type Library = components['schemas']['Library']

export type MergedGrid = {
  items: TaggedItem[]
  counts: GridCounts
  /** More to come from a server whose next page the merge needs. */
  hasMore: boolean
  loadMore(): void
  /** The servers that didn't answer, for Try again. */
  failed: { id: string; name: string }[]
  retry(): void
}

/** Undefined when no other server takes part: the grid is the current server's alone, as before. */
export function useMergedGrid(library: Library, type: LibraryType, choices: GridChoices, first: ItemList | undefined): MergedGrid | undefined {
  const session = useSession()
  const on = useLevel34()
  const peers = usePeers()
  const asked = useMemo(() => (on ? peers : []), [on, peers])
  const q = gridQuery(choices, true)

  // Each other server's own library of this type: its first, as this TV opens its own.
  const libs = useQueries({
    queries: asked.map((peer) => ({
      queryKey: ['peer', peer.id, 'libraries'],
      queryFn: async ({ signal }: { signal: AbortSignal }) =>
        (await within(COPY_MS, signal, (s) => askAt34(peer, s, async (api, s2) => unwrap(await api.GET('/api/libraries', { signal: s2 }))))) ?? null,
      retry: false,
      staleTime: 5 * 60_000,
    })),
  })
  const theirs = asked.map((peer, i) => ({ peer, lib: libs[i]?.data?.items.find((l) => l.type === type), state: libs[i] }))

  // How many pages each server has been asked for; a new choice starts them all again.
  const choiceKey = JSON.stringify(q)
  const [wanted, setWanted] = useState<{ key: string; pages: Record<string, number> }>({ key: choiceKey, pages: {} })
  const pagesOf = (id: string) => (wanted.key === choiceKey ? wanted.pages[id] ?? 1 : 1)

  const current = useQueries({
    queries: Array.from({ length: Math.max(0, pagesOf(session.server.id) - 1) }, (_, k) => ({
      queryKey: ['items', library.id, { ...q, limit: PAGE_SIZE, offset: (k + 1) * PAGE_SIZE }],
      queryFn: ({ signal }: { signal: AbortSignal }) => fetchItems(library.id, { ...q, limit: PAGE_SIZE, offset: (k + 1) * PAGE_SIZE }, undefined, signal),
    })),
  })
  const pageQueries = theirs.flatMap(({ peer, lib }) => (lib ? Array.from({ length: pagesOf(peer.id) }, (_, k) => ({ peer, lib, k })) : []))
  const pages = useQueries({
    queries: pageQueries.map(({ peer, lib, k }) => ({
      queryKey: ['peer', peer.id, 'items', lib.id, q, k],
      queryFn: ({ signal }: { signal: AbortSignal }) => within(COPY_MS, signal, (s) => fetchItems(lib.id, { ...q, limit: PAGE_SIZE, offset: k * PAGE_SIZE }, peer.api, s)),
      retry: false,
      staleTime: 30_000,
    })),
  })

  const loaded = (results: (UseQueryResult<ItemList> | undefined)[]) => {
    const out: ItemList[] = []
    for (const r of results) { if (!r?.data) break; out.push(r.data) }
    return out
  }
  const streamOf = (server: Stream['server'], lists: ItemList[]): Stream | undefined => {
    if (!lists.length) return undefined
    const items = lists.flatMap((l) => l.items)
    return { server, items, total: lists[0].total, totals: lists[0].totals, done: items.length >= lists[0].total }
  }
  const streams: Stream[] = []
  const mine = streamOf({ id: session.server.id, name: session.server.name, current: true }, first ? [first, ...loaded(current)] : [])
  if (mine) streams.push(mine)
  const failed: { id: string; name: string }[] = []
  const pending: string[] = []
  for (const { peer, lib, state } of theirs) {
    if (state?.isError) { failed.push({ id: peer.id, name: serverHeading(peer.name) }); continue }
    if (state?.isPending) { pending.push(serverHeading(peer.name)); continue }
    if (!lib) continue
    const own = pages.filter((_, i) => pageQueries[i].peer.id === peer.id)
    if (own[0]?.isError) { failed.push({ id: peer.id, name: serverHeading(peer.name) }); continue }
    const lists = loaded(own)
    if (!lists.length) { pending.push(serverHeading(peer.name)); continue }
    const s = streamOf({ id: peer.id, name: peer.name, current: false }, lists)
    if (s) streams.push(s)
  }
  const asking = useSlow(pending)
  const merged = mergeStreams(streams, q.sort)
  const counts = mergedCounts(type, streams, merged.shared, { asking: pending.length ? (asking.length ? asking : pending) : [], failed: failed.map((f) => f.name), narrowed: narrowed(choices), items: merged.items })
  // Only a server whose pages asked for are all in is asked for another, so a slow page isn't asked for twice.
  const pagesIn = (id: string) => (id === session.server.id ? (first ? 1 + loaded(current).length : 0) : loaded(pages.filter((_, i) => pageQueries[i].peer.id === id)).length)
  const needsKey = merged.needs.filter((id) => pagesIn(id) >= pagesOf(id)).join(',')

  // Called as focus nears the end; asks only servers whose pages are all in, so calling it again is harmless.
  const loadMore = () => {
    if (!needsKey) return
    setWanted((w) => {
      const pagesNow = w.key === choiceKey ? w.pages : {}
      const next = { ...pagesNow }
      for (const id of needsKey.split(',')) next[id] = (pagesNow[id] ?? 1) + 1
      return { key: choiceKey, pages: next }
    })
  }
  const retry = () => {
    theirs.forEach(({ peer, state }, i) => {
      if (state?.isError || failed.some((f) => f.id === peer.id)) { peer.forget(); void libs[i]?.refetch() }
    })
    pages.forEach((p) => { if (p.isError) void p.refetch() })
  }

  // Nothing to merge in: no other server answers level 34, or none has a library of this type.
  const takesPart = theirs.some(({ lib, state }) => !!lib || state?.isPending || state?.isError)
  if (!asked.length || !takesPart) return undefined
  return { items: merged.items, counts, hasMore: merged.needs.length > 0, loadMore, failed, retry }
}
