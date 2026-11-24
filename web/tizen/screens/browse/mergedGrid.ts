import type { components } from '../../../src/api/schema'
import type { ItemSort, LibraryType } from '../../../src/lib/browseOptions'
import { libraryNoun, unwatchedWords } from './libraryView'
import type { TaggedItem } from './oneCardHome'

// Movies and TV Shows from every signed-in server (one card across servers,
// API level 34): each server is asked for its grid a page at a time, in the
// person's sort, and the pages are merged in that order, one card a title.
// A server's next page is asked for only when the merge needs it to go on,
// so a large library costs no more than one. The header adds each server's
// counts, less the titles they share, and says "about" until it knows.
// Pure, so it is tested.

type BrowseItem = components['schemas']['BrowseItem']
type ItemTotals = components['schemas']['ItemTotals']

export type GridServer = { id: string; name: string; current: boolean }

/** One server's grid so far: its titles in its own order, how many it has, and whether every page is in. */
export type Stream = { server: GridServer; items: readonly BrowseItem[]; total: number; totals?: ItemTotals; done: boolean }

const at = (v: string | undefined) => v ?? ''
const num = (v: number | undefined) => v ?? -Infinity

/** Whether `a` comes before `b` in the sort, as the server orders them: titles A to Z, the rest newest, highest or longest first. */
export function before(sort: ItemSort, a: BrowseItem, b: BrowseItem): boolean {
  switch (sort) {
    case 'title': return a.title.localeCompare(b.title, undefined, { sensitivity: 'base' }) < 0
    case 'added': return at(a.addedAt) > at(b.addedAt)
    case 'year': return num(a.year) > num(b.year)
    case 'rating': return num(a.rating) > num(b.rating)
    case 'released': return at(a.releaseDate) > at(b.releaseDate)
    case 'runtime': return num(a.runtimeMin) > num(b.runtimeMin)
    case 'aired': return at(a.lastAirDate) > at(b.lastAirDate)
  }
}

const sameKey = (i: BrowseItem) => (i.tmdbId ? `${i.kind}:${i.tmdbId}` : undefined)

/**
 * Every server's titles merged in the sort, as far as the pages in allow:
 * it stops where a server whose pages aren't all in runs out (`needs` says
 * which), so the order is never wrong. A title on several servers is one
 * card, where its first copy fell, drawn from the current server's copy
 * when that one has come in. Streams come in the panel's order (the current
 * server first), which also settles ties.
 */
export function mergeStreams(streams: readonly Stream[], sort: ItemSort): { items: TaggedItem[]; needs: string[]; shared: number } {
  const pos = streams.map(() => 0)
  const items: TaggedItem[] = []
  const seen = new Map<string, number>()
  let shared = 0
  for (;;) {
    let pick = -1
    const needs: string[] = []
    for (let s = 0; s < streams.length; s++) {
      const st = streams[s]
      if (pos[s] >= st.items.length) {
        if (!st.done) needs.push(st.server.id)
        continue
      }
      if (pick < 0 || before(sort, st.items[pos[s]], streams[pick].items[pos[pick]])) pick = s
    }
    // A server that has run out but has more to come holds the merge here.
    if (needs.length || pick < 0) return { items, needs, shared }
    const st = streams[pick]
    const item = st.items[pos[pick]++]
    const key = sameKey(item)
    const tagged: TaggedItem = st.server.current ? item : { ...item, oneCard: { server: { id: st.server.id, name: st.server.name }, where: 'only' } }
    const there = key !== undefined ? seen.get(key) : undefined
    if (there !== undefined) {
      shared++
      // The card stays where it is; the current server's copy is the one it opens.
      if (st.server.current) items[there] = tagged
      continue
    }
    if (key !== undefined) seen.set(key, items.length)
    items.push(tagged)
  }
}

export type GridCounts = { line: string; about: boolean }

/**
 * The header's line: "1,236 movies · 412 you haven't watched", "About 1,240
 * movies · still asking Den server…", "1,236 movies · Den server didn't
 * answer". Each server's count added up, less the titles seen on more than
 * one; "about" until every server has answered and every page is in, since
 * only then are the shared ones all known.
 */
export function mergedCounts(type: LibraryType, streams: readonly Stream[], shared: number, o: { asking: readonly string[]; failed: readonly string[]; narrowed?: boolean }): GridCounts {
  const total = streams.reduce((n, s) => n + (s.totals?.items ?? s.total), 0) - shared
  const left = streams.every((s) => s.totals?.unwatched !== undefined) ? streams.reduce((n, s) => n + (s.totals?.unwatched ?? 0), 0) : undefined
  const about = o.asking.length > 0 || streams.some((s) => !s.done)
  const noun = libraryNoun(type)
  // Narrowed: "86 of 1,236 movies", what the filters leave of every server's library.
  const shown = streams.reduce((n, s) => n + s.total, 0) - shared
  const count = o.narrowed
    ? `${about ? 'About ' : ''}${shown.toLocaleString()} of ${total.toLocaleString()} ${total === 1 ? noun : `${noun}s`}`
    : `${about ? 'About ' : ''}${total.toLocaleString()} ${total === 1 ? noun : `${noun}s`}`
  const tail = o.asking.length ? `still asking ${o.asking.join(' and ')}…`
    : o.failed.length ? `${o.failed.join(' and ')} didn’t answer`
      : left !== undefined && !about && !o.narrowed ? unwatchedWords(type, left) : ''
  return { line: [count, tail].filter(Boolean).join(' · '), about }
}
