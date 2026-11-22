import { MutationObserver, QueryClient } from '@tanstack/react-query'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { components } from './schema'

// Saving and removing a title: shown at once in every
// cached reply, put back when the server refuses, removals held for Undo,
// and the one press that opens the picker when the person has several.
// The API is a stand-in that records each call and answers when told.

type Watchlist = components['schemas']['Watchlist']
type WatchlistChange = components['schemas']['WatchlistTitleChange']
type Call = { method: string; path: string; params?: { path?: Record<string, unknown> }; body?: unknown; answer: (status: number, body?: unknown) => void }

const calls: Call[] = []
const stand = (method: string) => (path: string, o: { params?: { path?: Record<string, unknown> }; body?: unknown } = {}) =>
  new Promise((resolve) => {
    calls.push({
      method, path, params: o.params, body: o.body,
      answer: (status, body) => resolve(status < 300 ? { data: body, response: new Response(null, { status }) } : { error: body ?? { detail: 'refused' }, response: new Response(null, { status }) }),
    })
  })
vi.mock('./client', async (original) => ({
  ...(await original<typeof import('./client')>()),
  api: { GET: stand('GET'), PUT: stand('PUT'), DELETE: stand('DELETE'), POST: stand('POST'), PATCH: stand('PATCH') },
}))

const { deleteWatchlistOptions, holdRemoval, isHeld, removeTitle, saveTitle, saveTitleToOnly, titleWatchlistsQuery, watchlistsQuery } = await import('./watchlists')
const { eventInvalidations } = await import('./eventInvalidation')

const watchlist = (id: number, name: string, over: Partial<Watchlist> = {}): Watchlist =>
  ({ id, name, titles: 12, inLibrary: 9, removeWatched: true, posters: [], createdAt: '2026-11-01T00:00:00Z', updatedAt: '2026-11-01T00:00:00Z', ...over })
const WATCHLIST = watchlist(1, 'Watchlist')
const HALLOWEEN = watchlist(2, 'Halloween', { removeWatched: false })

const fathoms = { mediaType: 'movie', tmdbId: 101 } as const
const amberline = { mediaType: 'movie', tmdbId: 303, title: 'Amberline', year: 2026, posterPath: '/amberline.jpg' } as const
const card = (id: number, tmdbId: number, title: string, watchlisted?: boolean) => ({ id, kind: 'movie', tmdbId, title, watchlisted })

let qc: QueryClient
/** The movie page's ids, a card on Home, the title as another list's entry, and as a Request search result. */
const state = () => ({
  page: qc.getQueryData<{ watchlists: number[] }>(['movie', 7])?.watchlists,
  card: qc.getQueryData<{ rows: { items: { watchlisted?: boolean }[] }[] }>(['home'])?.rows[0].items[0].watchlisted,
  entry: qc.getQueryData<{ items: { watchlisted?: boolean }[] }>(['lists', 9, 'entries', {}])?.items[0].watchlisted,
})
const last = () => calls[calls.length - 1]
/** The title on these watchlists everywhere it is cached, as the server would send it. */
const saved = (ids: number[]) => {
  qc.setQueryData(['movie', 7], { id: 7, tmdbId: 101, title: 'Ninety Fathoms', watchlists: ids })
  qc.setQueryData(['home'], { rows: [{ id: 'recentlyAddedMovies', items: [card(7, 101, 'Ninety Fathoms', true), card(8, 102, 'Tin Sky')] }] })
  qc.setQueryData(['lists', 9, 'entries', {}], { items: [{ rank: 1, mediaType: 'movie', tmdbId: 101, title: 'Ninety Fathoms', found: true, watchlisted: true }], total: 1, inLibrary: 1, offset: 0, limit: 100 })
}
const change = (watchlists: number[], over: Partial<WatchlistChange> = {}): WatchlistChange => ({ watchlists, ...over })

beforeEach(() => {
  calls.length = 0
  qc = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } })
  qc.setQueryData(watchlistsQuery.queryKey, { items: [WATCHLIST], max: 25, maxTitles: 500 })
  qc.setQueryData(['movie', 7], { id: 7, tmdbId: 101, title: 'Ninety Fathoms', watchlists: [] })
  qc.setQueryData(['movie', 8], { id: 8, tmdbId: 102, title: 'Tin Sky', watchlists: [] })
  qc.setQueryData(['home'], { rows: [{ id: 'recentlyAddedMovies', items: [card(7, 101, 'Ninety Fathoms'), card(8, 102, 'Tin Sky')] }] })
  qc.setQueryData(['lists', 9, 'entries', {}], { items: [{ rank: 1, mediaType: 'movie', tmdbId: 101, title: 'Ninety Fathoms', found: true }], total: 1, inLibrary: 1, offset: 0, limit: 100 })
})

describe('saving to a watchlist', () => {
  it('shows at once on the title’s page, its cards and its entries, and nowhere else', () => {
    void saveTitle(qc, fathoms, 1)
    expect(state()).toEqual({ page: [1], card: true, entry: true })
    expect(qc.getQueryData<{ watchlists: number[] }>(['movie', 8])?.watchlists).toEqual([])
    expect(qc.getQueryData<{ rows: { items: { watchlisted?: boolean }[] }[] }>(['home'])?.rows[0].items[1].watchlisted).toBeUndefined()
  })

  it('takes what the server says holds the title once it answers', async () => {
    const saving = saveTitle(qc, fathoms, 1)
    last().answer(200, change([1, 2]))
    await saving
    expect(state().page).toEqual([1, 2])
  })

  it('puts it back when refused', async () => {
    const saving = saveTitle(qc, fathoms, 1)
    last().answer(422, { detail: 'A watchlist holds up to 500 titles.' })
    await expect(saving).rejects.toThrow('A watchlist holds up to 500 titles.')
    expect(state()).toEqual({ page: [], card: false, entry: false })
  })

  it('doesn’t take back a later change when an earlier one is refused', async () => {
    qc.setQueryData(watchlistsQuery.queryKey, { items: [WATCHLIST, HALLOWEEN], max: 25, maxTitles: 500 })
    const first = saveTitle(qc, fathoms, 1)
    const toFirst = last()
    void saveTitle(qc, fathoms, 2)
    toFirst.answer(500)
    await first.catch(() => {})
    expect(state().page).toEqual([1, 2])
  })

  it('sends what it knows of a title the server may not have', () => {
    void saveTitle(qc, amberline, 1)
    expect(last()).toMatchObject({ method: 'PUT', params: { path: { id: 1, mediaType: 'movie', tmdbId: 303 } }, body: { title: 'Amberline', year: 2026, posterPath: '/amberline.jpg' } })
  })
})

describe('one press', () => {
  it('with no watchlist yet shows On Watchlist at once, then the one the server made', async () => {
    qc.setQueryData(watchlistsQuery.queryKey, { items: [], max: 25, maxTitles: 500 })
    const saving = saveTitleToOnly(qc, fathoms)
    expect(state()).toEqual({ page: [0], card: true, entry: true })
    last().answer(200, change([1], { created: true, watchlist: watchlist(1, 'Watchlist', { titles: 1, inLibrary: 1 }) }))
    expect(await saving).toMatchObject({ change: { created: true } })
    expect(state().page).toEqual([1])
    expect(qc.getQueryData<{ items: Watchlist[] }>(watchlistsQuery.queryKey)?.items.map((w) => w.name)).toEqual(['Watchlist'])
  })

  it('opens the picker with the server’s watchlists when the person has several by now', async () => {
    const saving = saveTitleToOnly(qc, fathoms)
    last().answer(409, { type: 'about:blank', status: 409, watchlists: [{ ...WATCHLIST, contains: false }, { ...HALLOWEEN, contains: true }] })
    expect(await saving).toMatchObject({ picker: [{ name: 'Watchlist' }, { name: 'Halloween' }] })
    // Nothing was saved, and the picker opens with Halloween checked, without asking again.
    expect(state().page).toEqual([])
    expect(qc.getQueryData<{ items: Watchlist[] }>(titleWatchlistsQuery(fathoms).queryKey)?.items.map((w) => [w.name, w.contains])).toEqual([['Watchlist', false], ['Halloween', true]])
    expect(qc.getQueryData<{ items: Watchlist[] }>(watchlistsQuery.queryKey)?.items).toHaveLength(2)
  })
})

describe('a removal held for Undo', () => {
  beforeEach(() => {
    saved([1])
  })

  it('shows as gone at once and sends nothing until committed', () => {
    const held = holdRemoval(qc, fathoms)
    expect(state()).toEqual({ page: [], card: false, entry: false })
    expect(isHeld(fathoms)).toBe(true)
    expect(calls).toEqual([])
    held.undo()
  })

  it('keeps the title, as it was, on Undo', () => {
    holdRemoval(qc, fathoms).undo()
    expect(state()).toEqual({ page: [1], card: true, entry: true })
    expect(isHeld(fathoms)).toBe(false)
    expect(calls).toEqual([])
  })

  it('sends the removal on commit, and lets go once the server answers', async () => {
    const held = holdRemoval(qc, fathoms)
    const sending = held.commit()
    expect(last()).toMatchObject({ method: 'DELETE', path: '/api/me/watchlist/titles/{mediaType}/{tmdbId}' })
    expect(isHeld(fathoms)).toBe(true)
    last().answer(200, change([], { removed: [1] }))
    await sending
    expect(isHeld(fathoms)).toBe(false)
    expect(state().page).toEqual([])
  })

  it('puts the title back and says so when the server refuses', async () => {
    const failed: unknown[] = []
    const sending = holdRemoval(qc, fathoms, undefined, (e) => failed.push(e)).commit()
    last().answer(500)
    await sending
    expect(failed).toHaveLength(1)
    expect(state()).toEqual({ page: [1], card: true, entry: true })
  })

  it('from one watchlist takes it off that watchlist’s page once sent', async () => {
    const sending = holdRemoval(qc, fathoms, 9).commit()
    expect(isHeld(fathoms, 9)).toBe(true)
    expect(isHeld(fathoms)).toBe(false)
    last().answer(200, change([1]))
    await sending
    expect(qc.getQueryData<{ items: unknown[]; total: number }>(['lists', 9, 'entries', {}])).toMatchObject({ items: [], total: 0 })
  })

  it('is undone by saving the title again, which keeps its place', () => {
    const held = holdRemoval(qc, fathoms)
    void saveTitleToOnly(qc, fathoms)
    expect(isHeld(fathoms)).toBe(false)
    void held.commit()
    expect(calls.map((c) => c.method)).toEqual(['PUT'])
  })
})

describe('the picker’s checks', () => {
  it('take a title off one watchlist at once, keeping the other', () => {
    saved([1, 2])
    qc.setQueryData(titleWatchlistsQuery(fathoms).queryKey, { items: [{ ...WATCHLIST, contains: true }, { ...HALLOWEEN, contains: true }], max: 25, maxTitles: 500 })
    void removeTitle(qc, fathoms, 2)
    expect(state().page).toEqual([1])
    expect(qc.getQueryData<{ items: Watchlist[] }>(titleWatchlistsQuery(fathoms).queryKey)?.items.map((w) => w.contains)).toEqual([true, false])
  })
})

describe('deleting a watchlist', () => {
  it('drops it from the watchlists and from the pages of the titles it held', async () => {
    saved([1, 2])
    qc.setQueryData(watchlistsQuery.queryKey, { items: [WATCHLIST, HALLOWEEN], max: 25, maxTitles: 500 })
    const deleting = new MutationObserver(qc, deleteWatchlistOptions(qc)).mutate(2)
    await vi.waitFor(() => expect(calls).toHaveLength(1))
    last().answer(204)
    await deleting
    expect(state().page).toEqual([1])
    expect(qc.getQueryData<{ items: Watchlist[] }>(watchlistsQuery.queryKey)?.items.map((w) => w.name)).toEqual(['Watchlist'])
  })
})

describe('events from the person’s other devices', () => {
  it.each(['lists.changed', 'rows.changed'])('%s refetches the watchlists and the open title’s page', (event) => {
    eventInvalidations(qc)[event]({ listId: 1, screen: 'home' })
    expect(qc.getQueryState(watchlistsQuery.queryKey)?.isInvalidated).toBe(true)
    expect(qc.getQueryState(['movie', 7])?.isInvalidated).toBe(true)
  })
})
