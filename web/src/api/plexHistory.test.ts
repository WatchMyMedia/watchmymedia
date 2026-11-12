import { InfiniteQueryObserver, QueryClient, QueryObserver } from '@tanstack/react-query'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { api } from './client'
import { plexHistoryEventInvalidator, plexHistoryPagesQuery, plexHistoryQuery, removePlexHistoryFromCache, resetPlexHistoryPages, type PlexHistoryItem } from './plex'
import { authScope, changeAuthScope } from '../lib/authScope'
import { plexHistoryCursor, plexHistoryRows, retainPlexHistoryHead, type PlexHistoryPage } from '../lib/plexHistory'

vi.mock('./client', () => ({ api: { GET: vi.fn() }, unwrap: <T,>(value: { data: T }) => value.data }))
afterEach(() => vi.clearAllMocks())
const turn = () => new Promise<void>((resolve) => setTimeout(resolve, 0))
const viewing = (id: number, userId = 1): PlexHistoryItem => ({ id, userId, itemType: 'movie', title: `Fictional film ${id}`, viewedAt: '2026-11-04T10:00:00Z', importedAt: '2026-11-04T11:00:00Z', source: 'plex' })
const queryClient = () => new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: Infinity, staleTime: Infinity } } })

describe('personal Plex history loading', () => {
  it('refreshes one live head after an event burst with 33 retained pages and fills a large import gap', async () => {
    const client = queryClient()
    const all = Array.from({ length: 2000 }, (_, index) => viewing(2000 - index))
    const requests: (number | undefined)[] = []
    vi.mocked(api.GET).mockImplementation(async (_path, options) => {
      const before = (options as { params?: { query?: { before?: number } } })?.params?.query?.before
      requests.push(before)
      const items = all.filter((item) => before === undefined || item.id < before).slice(0, 50)
      return { data: { items, next: items.length === 50 ? items.at(-1)!.id : undefined } } as never
    })
    const head = new QueryObserver(client, plexHistoryQuery())
    const pages = new InfiniteQueryObserver(client, plexHistoryPagesQuery())
    const stop = [head.subscribe(() => {}), pages.subscribe(() => {})]
    try {
      await head.refetch({ cancelRefetch: false })
      expect(requests).toEqual([undefined])
      client.setQueryData(plexHistoryPagesQuery().queryKey, retainPlexHistoryHead(head.getCurrentResult().data!))
      pages.setOptions(plexHistoryPagesQuery(undefined, head.getCurrentResult().data))
      for (let page = 0; page < 32; page++) await pages.fetchNextPage()
      expect(pages.getCurrentResult().data?.pages).toHaveLength(33)
      expect(requests).toHaveLength(33)
      expect(plexHistoryPagesQuery().enabled).toBe(false)
      expect(plexHistoryQuery().refetchInterval).toBe(30_000)

      all.unshift(...Array.from({ length: 120 }, (_, index) => viewing(2120 - index)))
      requests.length = 0
      const changed = plexHistoryEventInvalidator(client)
      for (let event = 0; event < 30; event++) changed({})
      await turn()
      expect(requests).toEqual([undefined])
      expect(head.getCurrentResult().data?.items[0].id).toBe(2120)
      expect(pages.getCurrentResult().data?.pages).toHaveLength(33)
      let rows = plexHistoryRows(head.getCurrentResult().data, pages.getCurrentResult().data)
      expect(rows.some((item) => item.id === 1951)).toBe(true)
      expect(new Set(rows.map((item) => item.id)).size).toBe(rows.length)

      // More than 50 arrivals leave a gap above the frozen pages. The same
      // Show more action fills it before continuing at the oldest cursor.
      pages.setOptions(plexHistoryPagesQuery(undefined, head.getCurrentResult().data))
      await pages.fetchNextPage()
      await pages.fetchNextPage()
      expect(requests).toEqual([undefined, 2071, 2021])
      rows = plexHistoryRows(head.getCurrentResult().data, pages.getCurrentResult().data)
      expect(rows.map((item) => item.id)).toEqual(Array.from({ length: 1770 }, (_, index) => 2120 - index))
      expect(plexHistoryCursor(head.getCurrentResult().data, pages.getCurrentResult().data)).toBe(351)

      requests.length = 0
      await head.refetch() // The scheduled refresh uses this same head query.
      expect(requests).toEqual([undefined])
    } finally {
      stop.forEach((done) => done())
      client.clear()
    }
  })

  it('coalesces in-flight import events and fetches the final committed head without old pages', async () => {
    const client = queryClient()
    let committed = 1
    let requests = 0
    let inFlight = 0
    let peak = 0
    const finish: (() => void)[] = []
    vi.mocked(api.GET).mockImplementation(async () => {
      requests++
      peak = Math.max(peak, ++inFlight)
      const snapshot = committed
      return new Promise((resolve) => finish.push(() => { inFlight--; resolve({ data: { items: [viewing(snapshot)] } } as never) }))
    })
    client.setQueryData(plexHistoryQuery().queryKey, { items: [viewing(0)] })
    const head = new QueryObserver(client, plexHistoryQuery())
    const stop = head.subscribe(() => {})
    try {
      const first = head.refetch()
      const changed = plexHistoryEventInvalidator(client)
      for (committed = 2; committed <= 31; committed++) changed({})
      committed = 31
      await turn()
      expect(requests).toBe(1)
      finish.shift()!()
      await first
      await turn()
      expect(requests).toBe(2)
      finish.shift()!()
      await turn()
      expect(head.getCurrentResult().data?.items[0].id).toBe(31)
      expect(peak).toBe(1)
    } finally {
      stop()
      client.clear()
    }
  })

  it('removes deleted boundary rows from every snapshot, cancels stale pages and clears only the selected person', async () => {
    const client = queryClient()
    let all = Array.from({ length: 150 }, (_, index) => viewing(150 - index))
    let late: ((value: { data: PlexHistoryPage }) => void) | undefined
    vi.mocked(api.GET).mockImplementation(async (_path, options) => {
      const before = (options as { params?: { query?: { before?: number } } })?.params?.query?.before
      const items = all.filter((item) => before === undefined || item.id < before).slice(0, 50)
      const response = { data: { items, next: items.length === 50 ? items.at(-1)!.id : undefined } }
      if (before === 51) return new Promise((resolve) => { late = resolve as never })
      return response as never
    })
    const head = new QueryObserver(client, plexHistoryQuery())
    const pages = new InfiniteQueryObserver(client, plexHistoryPagesQuery())
    const stop = [head.subscribe(() => {}), pages.subscribe(() => {})]
    try {
      await head.refetch({ cancelRefetch: false })
      client.setQueryData(plexHistoryPagesQuery().queryKey, retainPlexHistoryHead(head.getCurrentResult().data!))
      pages.setOptions(plexHistoryPagesQuery(undefined, head.getCurrentResult().data))
      await pages.fetchNextPage()
      const pending = pages.fetchNextPage()
      await turn()
      expect(late).toBeDefined()
      all = all.filter((item) => item.id !== 101)
      await removePlexHistoryFromCache(client, undefined, authScope(), 101)
      late!({ data: { items: [viewing(101), viewing(1)] } })
      await pending
      const retained = pages.getCurrentResult().data!
      expect(retained.pages).toHaveLength(2)
      expect(retained.pages[0].next).toBe(101)
      expect(plexHistoryRows(head.getCurrentResult().data, retained).some((item) => item.id === 101)).toBe(false)
      expect(plexHistoryCursor(head.getCurrentResult().data, retained)).toBe(51)

      client.setQueryData(plexHistoryQuery(2).queryKey, { items: [viewing(900, 2)] })
      client.setQueryData(plexHistoryPagesQuery(2).queryKey, retainPlexHistoryHead({ items: [viewing(900, 2)] }))
      all = []
      await removePlexHistoryFromCache(client, undefined, authScope())
      expect(head.getCurrentResult().data?.items).toEqual([])
      expect(pages.getCurrentResult().data?.pages).toEqual([])
      expect(client.getQueryData<PlexHistoryPage>(plexHistoryQuery(2).queryKey)?.items[0].id).toBe(900)
    } finally {
      stop.forEach((done) => done())
      client.clear()
    }
  })

  it('does not reuse another account’s head, snapshots or late deletion callbacks', async () => {
    const client = queryClient()
    const previousScope = authScope()
    const previousKey = plexHistoryQuery().queryKey
    client.setQueryData(previousKey, { items: [viewing(100)] })
    client.setQueryData(plexHistoryPagesQuery().queryKey, retainPlexHistoryHead({ items: [viewing(100)] }))
    changeAuthScope()
    try {
      expect(client.getQueryData(plexHistoryQuery().queryKey)).toBeUndefined()
      expect(client.getQueryData(plexHistoryPagesQuery().queryKey)).toBeUndefined()
      client.setQueryData(plexHistoryQuery().queryKey, { items: [viewing(200, 2)] })
      await removePlexHistoryFromCache(client, undefined, previousScope)
      expect(client.getQueryData<PlexHistoryPage>(plexHistoryQuery().queryKey)?.items[0].id).toBe(200)
      expect(client.getQueryData<PlexHistoryPage>(previousKey)?.items[0].id).toBe(100)
    } finally {
      client.clear()
    }
  })

  it('uses private deletion metadata for an old snapshot row and clears only its owner without replaying pages', async () => {
    const client = queryClient()
    const all = Array.from({ length: 2000 }, (_, index) => viewing(2000 - index))
    const first: PlexHistoryPage = { items: all.slice(0, 50), next: 1951 }
    const retained = {
      pages: Array.from({ length: 33 }, (_, page) => ({ items: all.slice(page * 50, (page + 1) * 50), next: 1951 - page * 50 })),
      pageParams: Array.from({ length: 33 }, (_, page) => 2001 - page * 50),
    }
    client.setQueryData(plexHistoryQuery().queryKey, first)
    client.setQueryData(plexHistoryPagesQuery().queryKey, retained)
    client.setQueryData(plexHistoryQuery(2).queryKey, { items: [viewing(9000, 2)] })
    client.setQueryData(plexHistoryPagesQuery(2).queryKey, retainPlexHistoryHead({ items: [viewing(9000, 2)] }))
    let requests = 0
    let cleared = false
    vi.mocked(api.GET).mockImplementation(async () => {
      requests++
      return { data: cleared ? { items: [] } : first } as never
    })
    const head = new QueryObserver(client, plexHistoryQuery())
    const pages = new InfiniteQueryObserver(client, plexHistoryPagesQuery(undefined, first))
    const stop = [head.subscribe(() => {}), pages.subscribe(() => {})]
    try {
      const changed = plexHistoryEventInvalidator(client)
      changed({ deletedIds: [500] }) // Older than the live head and 30 pages deep.
      await turn()
      expect(requests).toBe(1)
      expect(pages.getCurrentResult().data?.pages).toHaveLength(33)
      expect(plexHistoryRows(head.getCurrentResult().data, pages.getCurrentResult().data).some((item) => item.id === 500)).toBe(false)
      expect(plexHistoryCursor(head.getCurrentResult().data, pages.getCurrentResult().data)).toBe(351)
      cleared = true
      changed({ cleared: true })
      await turn()
      expect(requests).toBe(2)
      expect(pages.getCurrentResult().data?.pages).toEqual([])
      expect(head.getCurrentResult().data?.items).toEqual([])
      expect(client.getQueryData<PlexHistoryPage>(plexHistoryQuery(2).queryKey)?.items[0].id).toBe(9000)
      expect(client.getQueryData(plexHistoryPagesQuery(2).queryKey)).toEqual(retainPlexHistoryHead({ items: [viewing(9000, 2)] }))
    } finally {
      stop.forEach((done) => done())
      client.clear()
    }
  })

  it.each(['complete head', 'reconnect'])('cancels old pages on %s before later imports become visible', async (reason) => {
    const client = queryClient()
    const original = Array.from({ length: 150 }, (_, index) => viewing(150 - index))
    const first: PlexHistoryPage = { items: original.slice(0, 50), next: 101 }
    const second: PlexHistoryPage = { items: original.slice(50, 100), next: 51 }
    client.setQueryData(plexHistoryQuery().queryKey, first)
    client.setQueryData(plexHistoryPagesQuery().queryKey, { pages: [first, second], pageParams: [151, 101] })
    let finish: ((value: { data: PlexHistoryPage }) => void) | undefined
    const requests: (number | undefined)[] = []
    vi.mocked(api.GET).mockImplementation(async (_path, options) => {
      const before = (options as { params?: { query?: { before?: number } } })?.params?.query?.before
      requests.push(before)
      if (before === 51) return new Promise((resolve) => { finish = resolve as never })
      return { data: { items: Array.from({ length: 50 }, (_, index) => viewing((before ?? 301) - index - 1)), next: (before ?? 301) - 50 } } as never
    })
    const pages = new InfiniteQueryObserver(client, plexHistoryPagesQuery(undefined, first))
    const stop = pages.subscribe(() => {})
    try {
      const pending = pages.fetchNextPage()
      await turn()
      expect(finish).toBeDefined()
      if (reason === 'complete head') client.setQueryData(plexHistoryQuery().queryKey, { items: [] })
      await resetPlexHistoryPages(client, undefined, authScope())
      const imported: PlexHistoryPage = { items: Array.from({ length: 50 }, (_, index) => viewing(300 - index)), next: 251 }
      client.setQueryData(plexHistoryQuery().queryKey, imported)
      finish!({ data: { items: original.slice(100) } })
      await pending
      expect(pages.getCurrentResult().data?.pages).toEqual([])
      expect(plexHistoryRows(imported, pages.getCurrentResult().data).map((item) => item.id)).toEqual(imported.items.map((item) => item.id))

      // Show more after a clear starts below the current head, never with a
      // duplicate unbounded head request or the old account's cursor.
      client.setQueryData(plexHistoryPagesQuery().queryKey, retainPlexHistoryHead(imported))
      pages.setOptions(plexHistoryPagesQuery(undefined, imported))
      await pages.fetchNextPage()
      expect(requests).toEqual([51, 251])
    } finally {
      stop()
      client.clear()
    }
  })
})
