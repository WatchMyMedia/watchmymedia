import { queryOptions, useMutation, useQuery, useQueryClient, type QueryClient, type QueryKey } from '@tanstack/react-query'
import { useSyncExternalStore } from 'react'
import { api, unwrap } from './client'
import { invalidateRowsAndLists } from './queries'
import type { components } from './schema'
import { FIRST_WATCHLIST, MAX_TITLES, MAX_WATCHLISTS, onlyName, pressAction, titleActionLabel, watchlistLabel, type Watchlist, type WatchlistTitle } from '../lib/watchlists'

// Watchlists: the calls, and the one place a change shows
// in what is cached. Keys: ['watchlists'] for the person's watchlists,
// ['watchlists', type, tmdbId] for them with one title's checks (the
// picker); a title's page carries the ids holding it (['movie', id],
// ['show', id]), and cards, list entries and requestable titles carry
// watchlisted. A watchlist's titles are a list's (['lists', id, …]).
//
// Nothing here draws or touches the browser, so the Samsung TV app uses it
// as it is. Words are lib/watchlists.ts.

export type WatchlistList = components['schemas']['WatchlistList']
export type WatchlistChange = components['schemas']['WatchlistTitleChange']
export type WatchlistDetails = components['schemas']['WatchlistTitleDetails']
type Conflict = components['schemas']['WatchlistsConflict']
type MediaList = components['schemas']['MediaList']
type MediaListEntries = components['schemas']['MediaListEntries']
type Detail = { tmdbId: number; watchlists?: number[] }

/** A title to save, with its name, year and poster for one the server has no record of (a missing list entry, a Request search result). */
export type WatchlistTarget = WatchlistTitle & WatchlistDetails

/** The person's watchlists in the order made, with the limits. */
export const watchlistsQuery = queryOptions({
  queryKey: ['watchlists'],
  queryFn: async () => unwrap(await api.GET('/api/me/watchlists')),
})

/** The same, each saying whether it holds one title: what Save to watchlist checks. */
export const titleWatchlistsQuery = (t: WatchlistTitle) =>
  queryOptions({
    queryKey: ['watchlists', t.mediaType, t.tmdbId],
    queryFn: async () => unwrap(await api.GET('/api/me/watchlists', { params: { query: { mediaType: t.mediaType, tmdbId: t.tmdbId } } })),
  })

/**
 * What holds a title: the ids of its watchlists when a page or the picker
 * has them, or only whether any does, as a card says. The first press of
 * someone with no watchlist holds it on NEW, the Watchlist about to be made.
 */
type Holding = readonly number[] | boolean
const NEW = 0

const onOf = (h: Holding) => (typeof h === 'boolean' ? h : h.length > 0)
const same = (a: Holding, b: Holding) =>
  typeof a !== 'boolean' && typeof b !== 'boolean' ? a.length === b.length && a.every((id) => b.includes(id)) : onOf(a) === onOf(b)

const detailRoot = (t: WatchlistTitle) => (t.mediaType === 'movie' ? 'movie' : 'show')
/** A title's own page: ['movie', id] or ['show', id], not a show's seasons. */
const isDetail = (key: QueryKey, root?: string) => key.length === 2 && (root ? key[0] === root : key[0] === 'movie' || key[0] === 'show')

function details(qc: QueryClient, t: WatchlistTitle): [QueryKey, Detail][] {
  const root = detailRoot(t)
  return qc.getQueriesData<Detail>({ predicate: (q) => isDetail(q.queryKey, root) }).filter((e): e is [QueryKey, Detail] => e[1]?.tmdbId === t.tmdbId)
}

/** Replies that hold cards, list entries or requestable titles, which carry watchlisted. */
const holdsCards = (key: QueryKey) =>
  ['home', 'rows', 'related', 'items', 'search', 'lists'].includes(String(key[0])) || (key[0] === 'requests' && (key[1] === 'search' || key[1] === 'lookup'))

/** A card (kind movie or show), a list entry or a requestable title (mediaType) for this title. */
function isTitle(o: Record<string, unknown>, t: WatchlistTitle): boolean {
  if (o.tmdbId !== t.tmdbId) return false
  const type = o.kind === 'movie' ? 'movie' : o.kind === 'show' ? 'tv' : o.kind === undefined ? o.mediaType : undefined
  return type === t.mediaType
}

/** A reply with watchlisted set on every copy of the title in it; the same object when nothing changes. */
function marked(v: unknown, t: WatchlistTitle, on: boolean): unknown {
  if (Array.isArray(v)) {
    let changed = false
    const out = v.map((x) => {
      const y = marked(x, t, on)
      if (y !== x) changed = true
      return y
    })
    return changed ? out : v
  }
  if (!v || typeof v !== 'object') return v
  const o = v as Record<string, unknown>
  let out: Record<string, unknown> | undefined
  for (const [k, x] of Object.entries(o)) {
    const y = marked(x, t, on)
    if (y !== x) (out ??= { ...o })[k] = y
  }
  if (isTitle(o, t) && !!o.watchlisted !== on) (out ??= { ...o }).watchlisted = on
  return out ?? o
}

/** What the cache says holds a title, or undefined when nothing cached shows it. */
function cachedHolding(qc: QueryClient, t: WatchlistTitle): Holding | undefined {
  for (const [, d] of details(qc, t)) if (d.watchlists) return d.watchlists
  const lists = qc.getQueryData<WatchlistList>(titleWatchlistsQuery(t).queryKey)
  if (lists) return lists.items.filter((w) => w.contains).map((w) => w.id)
  for (const [, data] of qc.getQueriesData({ predicate: (q) => holdsCards(q.queryKey) })) {
    const on = findOn(data, t)
    if (on !== undefined) return on
  }
  return undefined
}

function findOn(v: unknown, t: WatchlistTitle): boolean | undefined {
  if (!v || typeof v !== 'object') return undefined
  if (!Array.isArray(v) && isTitle(v as Record<string, unknown>, t)) return !!(v as { watchlisted?: boolean }).watchlisted
  for (const x of Object.values(v)) {
    const on = findOn(x, t)
    if (on !== undefined) return on
  }
  return undefined
}

/**
 * Shows what holds a title everywhere it is cached, without a refetch: its
 * pages' watchlists and the picker's checks when the ids are known, and
 * watchlisted on its cards, entries and requestable titles.
 */
export function applyHolding(qc: QueryClient, t: WatchlistTitle, h: Holding) {
  if (typeof h !== 'boolean') {
    for (const [key, d] of details(qc, t)) qc.setQueryData<Detail>(key, { ...d, watchlists: [...h] })
    qc.setQueryData<WatchlistList>(titleWatchlistsQuery(t).queryKey, (l) => l && { ...l, items: l.items.map((w) => (!!w.contains === h.includes(w.id) ? w : { ...w, contains: h.includes(w.id) })) })
  }
  const on = onOf(h)
  for (const [key, data] of qc.getQueriesData({ predicate: (q) => holdsCards(q.queryKey) })) {
    const next = marked(data, t, on)
    if (next !== data) qc.setQueryData(key, next)
  }
}

/** Puts back what a refused change showed, unless a later change has shown something else since. */
function restore(qc: QueryClient, t: WatchlistTitle, before: Holding | undefined, after: Holding | undefined) {
  if (before === undefined || after === undefined) return
  const now = cachedHolding(qc, t)
  if (now !== undefined && same(now, after)) applyHolding(qc, t, before)
}

/** A watchlist as it is now, in every list of them; a new one goes last, as they are in the order made. */
function upsertWatchlist(qc: QueryClient, w: Watchlist) {
  for (const [key, l] of qc.getQueriesData<WatchlistList>({ queryKey: ['watchlists'] })) {
    if (!l) continue
    const at = l.items.findIndex((x) => x.id === w.id)
    const kept = { ...w, contains: at >= 0 ? l.items[at].contains : false }
    qc.setQueryData<WatchlistList>(key, { ...l, items: at >= 0 ? l.items.map((x, i) => (i === at ? kept : x)) : [...l.items, kept] })
  }
  qc.setQueryData<MediaList>(['lists', w.id], (l) => l && { ...l, name: w.name, removeWatched: w.removeWatched, entries: w.titles, inLibrary: w.inLibrary })
}

/** Takes a title off a watchlist's cached pages of titles, once the server has. */
function dropEntry(qc: QueryClient, listId: number, t: WatchlistTitle) {
  for (const [key, page] of qc.getQueriesData<MediaListEntries>({ queryKey: ['lists', listId, 'entries'] })) {
    const gone = page?.items.filter((e) => isTitle(e, t)) ?? []
    if (!page || !gone.length) continue
    qc.setQueryData<MediaListEntries>(key, {
      ...page,
      items: page.items.filter((e) => !gone.includes(e)),
      total: Math.max(0, page.total - gone.length),
      inLibrary: Math.max(0, page.inLibrary - gone.filter((e) => e.item).length),
    })
  }
}

/**
 * Changes in flight. Once the last has settled, what the server works out
 * (counts, posters, the rows) is refetched, so an earlier reply can't
 * bring back what a later change took away.
 */
let inFlight = 0
const touched = new Set<number>()

async function changing<T>(qc: QueryClient, ids: readonly number[], call: () => Promise<T>): Promise<T> {
  inFlight++
  try {
    return await call()
  } finally {
    for (const id of ids) touched.add(id)
    if (--inFlight === 0) {
      const lists = [...touched]
      touched.clear()
      void qc.invalidateQueries({ queryKey: ['watchlists'] })
      for (const id of lists) void qc.invalidateQueries({ queryKey: ['lists', id] })
      void qc.invalidateQueries({ queryKey: ['home'] })
      void qc.invalidateQueries({ queryKey: ['rows'] })
    }
  }
}

/**
 * One change to what holds a title: shown at once where next can say what
 * it will be, sent, then shown as the server says; put back if refused.
 */
async function change(qc: QueryClient, t: WatchlistTitle, ids: readonly number[], next: (h: Holding | undefined) => Holding | undefined, call: () => Promise<WatchlistChange>): Promise<WatchlistChange> {
  const before = cachedHolding(qc, t)
  const after = next(before)
  if (after !== undefined) applyHolding(qc, t, after)
  return changing(qc, ids, async () => {
    try {
      const c = await call()
      if (c.watchlist) upsertWatchlist(qc, c.watchlist)
      applyHolding(qc, t, c.watchlists)
      return c
    } catch (e) {
      restore(qc, t, before, after)
      throw e
    }
  })
}

const path = (t: WatchlistTitle) => ({ mediaType: t.mediaType, tmdbId: t.tmdbId })
const detailsOf = (t: WatchlistTarget): WatchlistDetails | undefined => (t.title ? { title: t.title, year: t.year || undefined, posterPath: t.posterPath || undefined } : undefined)
const withId = (h: Holding | undefined, id: number): Holding => (h === undefined || typeof h === 'boolean' ? true : h.includes(id) ? h : [...h, id].sort((a, b) => a - b))

/** Saves a title to one watchlist; saving one already there keeps its place. A removal of it waiting for Undo is undone. */
export function saveTitle(qc: QueryClient, t: WatchlistTarget, listId: number): Promise<WatchlistChange> {
  holds.get(heldKey(t, listId))?.undo()
  holds.get(heldKey(t))?.undo()
  return change(qc, t, [listId], (h) => withId(h, listId), async () =>
    unwrap(await api.PUT('/api/me/watchlists/{id}/titles/{mediaType}/{tmdbId}', { params: { path: { id: listId, ...path(t) } }, body: detailsOf(t) })))
}

/** Takes a title off one watchlist at once, as the picker's checks do. */
export function removeTitle(qc: QueryClient, t: WatchlistTitle, listId: number): Promise<WatchlistChange> {
  return change(qc, t, [listId], (h) => (h === undefined || typeof h === 'boolean' ? undefined : h.filter((id) => id !== listId)), async () => {
    const c = unwrap(await api.DELETE('/api/me/watchlists/{id}/titles/{mediaType}/{tmdbId}', { params: { path: { id: listId, ...path(t) } } }))
    dropEntry(qc, listId, t)
    return c
  })
}

/** Takes a title off every watchlist that holds it: On Watchlist pressed again. */
export function removeTitleFromAll(qc: QueryClient, t: WatchlistTitle): Promise<WatchlistChange> {
  const ids = (qc.getQueryData<WatchlistList>(watchlistsQuery.queryKey)?.items ?? []).map((w) => w.id)
  return change(qc, t, ids, (h) => (h === undefined ? undefined : typeof h === 'boolean' ? false : []), async () => {
    const c = unwrap(await api.DELETE('/api/me/watchlist/titles/{mediaType}/{tmdbId}', { params: { path: path(t) } }))
    for (const id of c.removed ?? []) dropEntry(qc, id, t)
    return c
  })
}

export type OnlyResult = { change: WatchlistChange } | { picker: Watchlist[] }

/**
 * The one press with no watchlist or one: saves to it, making Watchlist
 * the first time. A person who has two or more by now gets the picker
 * instead, with the server's list of them and their checks for the title.
 */
export async function saveTitleToOnly(qc: QueryClient, t: WatchlistTarget): Promise<OnlyResult> {
  holds.get(heldKey(t))?.undo()
  const lists = qc.getQueryData<WatchlistList>(watchlistsQuery.queryKey)
  const only = lists?.items.length === 1 ? lists.items[0].id : lists?.items.length === 0 ? NEW : undefined
  let picker: Watchlist[] | undefined
  // Not knowing the watchlists yet, a title on none shows on the one about to be made.
  const next = (h: Holding | undefined): Holding | undefined =>
    only !== undefined ? withId(h, only) : h === undefined || typeof h === 'boolean' ? true : h.length ? undefined : [NEW]
  const c = await change(qc, t, lists?.items.map((w) => w.id) ?? [], next, async () => {
    const res = await api.PUT('/api/me/watchlist/titles/{mediaType}/{tmdbId}', { params: { path: path(t) }, body: detailsOf(t) })
    if (res.response.status === 409 && res.error) {
      picker = (res.error as Conflict).watchlists
      throw new Error('several watchlists')
    }
    return unwrap(res)
  }).catch((e: unknown) => {
    if (!picker) throw e
    return undefined
  })
  if (picker) {
    const base = { max: lists?.max ?? MAX_WATCHLISTS, maxTitles: lists?.maxTitles ?? MAX_TITLES }
    qc.setQueryData<WatchlistList>(titleWatchlistsQuery(t).queryKey, { ...base, items: picker })
    qc.setQueryData<WatchlistList>(watchlistsQuery.queryKey, { ...base, items: picker.map((w) => ({ ...w, contains: false })) })
    return { picker }
  }
  return { change: c! }
}

// Removals that wait for Undo. Pressing On Watchlist again, or Remove on a
// watchlist's page, holds the call back while its message shows, so Undo
// keeps the title where it was on the list. The views that can show a held
// title (the button, the picker, the watchlist's page) leave it out through
// isHeld, so a refetch in the meantime doesn't bring it back.

export type HeldRemoval = {
  /** Keeps the title: nothing was sent. */
  undo: () => void
  /** Sends the removal; a refusal puts the title back and comes to onFailed. */
  commit: () => Promise<void>
}

const holds = new Map<string, HeldRemoval>()
let heldVersion = 0
const heldListeners = new Set<() => void>()
const heldKey = (t: WatchlistTitle, listId?: number) => `${t.mediaType}:${t.tmdbId}:${listId ?? '*'}`

function setHeld(key: string, h: HeldRemoval | undefined) {
  if (h) holds.set(key, h)
  else holds.delete(key)
  heldVersion++
  for (const f of heldListeners) f()
}

/** Whether a removal of the title from every watchlist, or from listId, is waiting for Undo. */
export function isHeld(t: WatchlistTitle, listId?: number): boolean {
  return holds.has(heldKey(t)) || (listId !== undefined && holds.has(heldKey(t, listId)))
}

export function subscribeHeld(f: () => void): () => void {
  heldListeners.add(f)
  return () => void heldListeners.delete(f)
}

/** Redraws when a removal is held or let go. */
export function useHeldRemovals(): number {
  return useSyncExternalStore(subscribeHeld, () => heldVersion, () => heldVersion)
}

/**
 * Holds back taking a title off one watchlist (listId), or off every one
 * (none). It shows as gone at once; commit sends it, undo forgets it. A
 * client that shows no message, as the TVs don't, commits at once.
 */
export function holdRemoval(qc: QueryClient, t: WatchlistTitle, listId?: number, onFailed?: (error: unknown) => void): HeldRemoval {
  const key = heldKey(t, listId)
  holds.get(key)?.undo()
  const before = cachedHolding(qc, t)
  const after: Holding | undefined = before === undefined ? undefined : listId === undefined ? (typeof before === 'boolean' ? false : []) : typeof before === 'boolean' ? undefined : before.filter((id) => id !== listId)
  if (after !== undefined) applyHolding(qc, t, after)
  let open = true
  const h: HeldRemoval = {
    undo: () => {
      if (!open) return
      open = false
      setHeld(key, undefined)
      restore(qc, t, before, after)
    },
    commit: async () => {
      if (!open) return
      open = false
      try {
        await (listId === undefined ? removeTitleFromAll(qc, t) : removeTitle(qc, t, listId))
      } catch (e) {
        restore(qc, t, before, after)
        onFailed?.(e)
      } finally {
        setHeld(key, undefined)
      }
    },
  }
  setHeld(key, h)
  return h
}

/** Every held removal sent now: the page is going away. */
export function commitHeldRemovals() {
  for (const h of [...holds.values()]) void h.commit()
}

/** The ids holding a title as a view should show them: less any removal waiting for Undo. */
export function visibleHolding(t: WatchlistTitle, holding: readonly number[]): number[] {
  return isHeld(t) ? [] : holding.filter((id) => !isHeld(t, id))
}

// Hooks for the button and the actions on missing titles.

export type PressOutcome =
  | { kind: 'picker' }
  | { kind: 'saved'; name: string; undo: () => Promise<unknown> }
  | { kind: 'removed'; name: string; undo: () => void; commit: () => Promise<void> }
  | { kind: 'failed'; error: unknown }

/**
 * A title's watchlist state and its one press. known is what the view has:
 * the ids holding it on a title's page, or watchlisted on an entry or a
 * requestable title. label is the button's ("On Watchlist") with the ids,
 * and the action's ("Add to Watchlist") without them.
 */
export function useWatchlistPress(t: WatchlistTarget | undefined, known: { holding?: readonly number[]; watchlisted?: boolean }, onFailed?: (error: unknown, change: { name: string; remove: boolean }) => void) {
  const qc = useQueryClient()
  const lists = useQuery(watchlistsQuery)
  useHeldRemovals()
  const items = lists.data?.items
  const holding = t && known.holding ? visibleHolding(t, known.holding) : undefined
  const saved = holding ? holding.length > 0 : !!t && !!known.watchlisted && !isHeld(t)
  const action = pressAction(items?.length, saved)
  const press = async (): Promise<PressOutcome> => {
    if (!t) return { kind: 'failed', error: new Error('no TMDB match') }
    if (action === 'picker') return { kind: 'picker' }
    if (action === 'remove') {
      const name = (holding?.length === 1 && items?.find((w) => w.id === holding[0])?.name) || onlyName(items)
      const h = holdRemoval(qc, t, undefined, (e) => onFailed?.(e, { name, remove: true }))
      return { kind: 'removed', name, undo: h.undo, commit: h.commit }
    }
    try {
      const r = await saveTitleToOnly(qc, t)
      if ('picker' in r) return { kind: 'picker' }
      const w = r.change.watchlist
      const name = w?.name ?? FIRST_WATCHLIST
      return { kind: 'saved', name, undo: () => (w ? removeTitle(qc, t, w.id) : removeTitleFromAll(qc, t)).catch((e: unknown) => onFailed?.(e, { name, remove: true })) }
    } catch (error) {
      return { kind: 'failed', error }
    }
  }
  return {
    lists: items,
    saved,
    holding: holding ?? [],
    picker: action === 'picker',
    label: holding ? watchlistLabel(holding, items) : titleActionLabel(items, saved),
    press,
  }
}

/**
 * Save to watchlist for one title: the person's watchlists with a check
 * each, toggled on its own and at once, and New watchlist, which makes one
 * and saves the title to it.
 */
export function useWatchlistPicker(t: WatchlistTarget, open = true) {
  const qc = useQueryClient()
  const lists = useQuery({ ...titleWatchlistsQuery(t), enabled: open })
  useHeldRemovals()
  const items = lists.data?.items.map((w) => (w.contains && isHeld(t, w.id) ? { ...w, contains: false } : w))
  const create = useMutation({
    mutationFn: async (name: string) => {
      const w = await createWatchlist(qc, { name })
      await saveTitle(qc, t, w.id)
      return w
    },
  })
  return {
    query: lists,
    items,
    max: lists.data?.max ?? MAX_WATCHLISTS,
    toggle: (w: Watchlist) => (w.contains ? removeTitle(qc, t, w.id) : saveTitle(qc, t, w.id)),
    create,
  }
}

// The watchlists themselves.

/** Makes a watchlist; it is last in the person's list, and its row comes on every screen. */
export async function createWatchlist(qc: QueryClient, body: { name: string; removeWatched?: boolean }): Promise<Watchlist> {
  const w = unwrap(await api.POST('/api/me/watchlists', { body: { ...body, name: body.name.trim() } }))
  upsertWatchlist(qc, w)
  invalidateRowsAndLists(qc)
  return w
}

export function useCreateWatchlist() {
  const qc = useQueryClient()
  return useMutation({ mutationFn: (name: string) => createWatchlist(qc, { name }) })
}

type WatchlistPatch = { name?: string; removeWatched?: boolean }
const cleanPatch = (p: WatchlistPatch): WatchlistPatch => (p.name === undefined ? p : { ...p, name: p.name.trim() })

/** Rename and the switch: shown at once on the watchlist's page and in the list of them, and put back if refused. */
export function useUpdateWatchlist() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async ({ id, patch }: { id: number; patch: WatchlistPatch }) => unwrap(await api.PATCH('/api/me/watchlists/{id}', { params: { path: { id } }, body: cleanPatch(patch) })),
    onMutate: ({ id, patch: typed }) => {
      const patch = cleanPatch(typed)
      const list = qc.getQueryData<MediaList>(['lists', id])
      const watchlists = qc.getQueryData<WatchlistList>(watchlistsQuery.queryKey)
      if (list) qc.setQueryData<MediaList>(['lists', id], { ...list, ...patch })
      if (watchlists) qc.setQueryData<WatchlistList>(watchlistsQuery.queryKey, { ...watchlists, items: watchlists.items.map((w) => (w.id === id ? { ...w, ...patch } : w)) })
      return { list, watchlists }
    },
    onError: (_e, { id }, was) => {
      if (was?.list) qc.setQueryData(['lists', id], was.list)
      if (was?.watchlists) qc.setQueryData(watchlistsQuery.queryKey, was.watchlists)
    },
    onSuccess: (w) => upsertWatchlist(qc, w),
    // Its row's title is its name.
    onSettled: () => invalidateRowsAndLists(qc),
  })
}

/** Deletes a watchlist: its titles stay in the library, and its row goes from every screen. */
export function deleteWatchlistOptions(qc: QueryClient) {
  return {
    mutationFn: async (id: number) => unwrap(await api.DELETE('/api/me/watchlists/{id}', { params: { path: { id } } })),
    onSuccess: (_d: unknown, id: number) => {
      for (const [key, l] of qc.getQueriesData<WatchlistList>({ queryKey: ['watchlists'] })) {
        if (l) qc.setQueryData<WatchlistList>(key, { ...l, items: l.items.filter((w) => w.id !== id) })
      }
      for (const [key, d] of qc.getQueriesData<Detail>({ predicate: (q) => isDetail(q.queryKey) })) {
        if (d?.watchlists?.includes(id)) qc.setQueryData<Detail>(key, { ...d, watchlists: d.watchlists.filter((x) => x !== id) })
      }
      qc.removeQueries({ queryKey: ['lists', id] })
      invalidateRowsAndLists(qc)
    },
  }
}

export function useDeleteWatchlist() {
  return useMutation(deleteWatchlistOptions(useQueryClient()))
}

/**
 * What a watchlist made, renamed or deleted on another device, or a title
 * saved or removed there (or removed by watching), refetches here: the
 * watchlists, and the open title page's button.
 */
export function invalidateWatchlists(qc: QueryClient) {
  void qc.invalidateQueries({ queryKey: ['watchlists'] })
  void qc.invalidateQueries({ predicate: (q) => isDetail(q.queryKey) })
}
