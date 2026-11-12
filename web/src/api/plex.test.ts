import { InfiniteQueryObserver, QueryClient, QueryObserver } from '@tanstack/react-query'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { api } from './client'
import { invalidatePlexState, plexLibrariesQuery, plexPeopleQuery, plexRunHistoryQuery, plexRunsQuery, recordPlexRun, type PlexRun } from './plex'

vi.mock('./client', () => ({ api: { GET: vi.fn() }, unwrap: <T,>(value: { data: T }) => value.data }))
afterEach(() => vi.clearAllMocks())

const run = (id: number, status: PlexRun['status'] = 'complete'): PlexRun => ({
  id, connectionId: 1, generation: 1, status, phase: 'finished', startedAt: '2026-11-04T10:00:00Z', history: 'complete',
  counts: { marked: 1, alreadyWatched: 0, localKept: 0, unmatched: 0, ambiguous: 0, unavailable: 0, historyImported: 0, historySkipped: 0 },
})

describe('Plex loading uses one live head and manual historical pages', () => {
  it('bounds refetches after 33 pages, avoids provider discovery, and keeps earlier actions reachable', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0, staleTime: 10_000 } } })
    const requests: { path: string; before?: number }[] = []
    const all = Array.from({ length: 1050 }, (_, index) => run(1050 - index))
    let active: PlexRun | undefined = undefined
    vi.mocked(api.GET).mockImplementation(async (path, options) => {
      const before = (options as { params?: { query?: { before?: number } } })?.params?.query?.before
      requests.push({ path, before })
      if (String(path).endsWith('/users')) return { data: { generation: 1, users: [] } } as never
      if (String(path).endsWith('/libraries')) return { data: { generation: 1, libraries: [] } } as never
      const items = all.filter((item) => before === undefined || item.id < before).slice(0, 30)
      return { data: { items, next: items.length === 30 ? items.at(-1)!.id : undefined, active } } as never
    })
    const current = new QueryObserver(client, plexRunsQuery(1))
    const history = new InfiniteQueryObserver(client, plexRunHistoryQuery(1))
    const people = new QueryObserver(client, plexPeopleQuery(1, 1))
    const libraries = new QueryObserver(client, plexLibrariesQuery(1, 1))
    const stop = [current.subscribe(() => {}), history.subscribe(() => {}), people.subscribe(() => {}), libraries.subscribe(() => {})]
    try {
      await Promise.all([current.refetch({ cancelRefetch: false }), people.refetch({ cancelRefetch: false }), libraries.refetch({ cancelRefetch: false })])
      expect(requests.filter((request) => request.path.endsWith('/runs'))).toHaveLength(1)
      expect(history.getCurrentResult().data).toBeUndefined()

      // The UI reuses its already loaded head when Earlier imports is requested.
      client.setQueryData(plexRunHistoryQuery(1).queryKey, { pages: [current.getCurrentResult().data!], pageParams: [0] })
      for (let page = 0; page < 32; page++) await history.fetchNextPage()
      expect(history.getCurrentResult().data?.pages).toHaveLength(33)
      active = run(80, 'running')
      recordPlexRun(client, 1, active)
      requests.length = 0
      invalidatePlexState(client)
      await current.refetch({ cancelRefetch: false })
      expect(requests).toEqual([{ path: '/api/integrations/plex/connections/{id}/runs', before: undefined }])
      expect(current.getCurrentResult().data?.active?.id).toBe(80)
      expect(history.getCurrentResult().data?.pages.flatMap((page) => page.items).find((item) => item.id === 80)?.status).toBe('running')

      recordPlexRun(client, 1, run(80, 'cancelled'))
      expect(current.getCurrentResult().data?.active).toBeUndefined()
      expect(history.getCurrentResult().data?.pages.flatMap((page) => page.items).find((item) => item.id === 80)?.status).toBe('cancelled')
      all.unshift(run(1051))
      await current.refetch()
      const original = history.getCurrentResult().data!.pages.flatMap((page) => page.items)
      expect(original).toHaveLength(990)
      expect(original.some((item) => item.id === 1021)).toBe(true)
      expect(current.getCurrentResult().data?.items.at(-1)?.id).toBe(1022)
      // Existing pages keep their original cursor, so every original row can
      // still be reached and no old undo disappears at a moving head boundary.
      await history.fetchNextPage()
      expect(history.getCurrentResult().data?.pages.at(-1)?.items[0].id).toBe(60)
    } finally {
      stop.forEach((done) => done())
      client.clear()
    }
  })
})
