import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryHistory, createRootRoute, createRouter, RouterProvider } from '@tanstack/react-router'
import type { ReactNode } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { beforeEach, describe, expect, it } from 'vitest'
import { mediaListEntriesQuery, mediaListQuery } from '../api/queries'
import { watchlistsQuery } from '../api/watchlists'
import { AuthContext, type AuthContextValue } from '../authContext'
import { WatchlistButton } from '../components/watchlists/WatchlistButton'
import { MAX_ENTRIES, type MediaList, type MediaListEntry } from '../lib/mediaLists'
import type { Watchlist } from '../lib/watchlists'
import { ListPage } from './ListPage'

// What a watchlist's page, another list's page and the Watchlist button
// draw from what is cached: words, buttons and their state. Nothing is
// fetched; every reply is in the cache before drawing.

const watchlist = (id: number, name: string, over: Partial<Watchlist> = {}): Watchlist =>
  ({ id, name, titles: 6, inLibrary: 5, removeWatched: true, posters: [], createdAt: '2026-11-01T00:00:00Z', updatedAt: '2026-11-01T00:00:00Z', ...over })
const list = (over: Partial<MediaList> = {}): MediaList => ({
  id: 5, name: 'Halloween', mediaType: 'all', source: { kind: 'watchlist' }, owner: 'me', canEdit: true, screen: 'home', limit: 500,
  entries: 2, inLibrary: 1, fetching: false, removeWatched: false, createdAt: '2026-11-01T00:00:00Z', updatedAt: '2026-11-01T00:00:00Z', fetchedAt: '2026-11-20T00:00:00Z', ...over,
})
const inLibrary: MediaListEntry = {
  rank: 1, mediaType: 'movie', tmdbId: 101, title: 'Black Orchard', found: true, watchlisted: true,
  item: { id: 7, kind: 'movie', tmdbId: 101, title: 'Black Orchard', year: 2026, libraryId: 2, addedAt: '2026-11-01T00:00:00Z', genres: [], hdr: false, isNew: false, overview: '', totalSize: 0, watched: true },
}
const missing: MediaListEntry = { rank: 2, mediaType: 'movie', tmdbId: 303, title: 'Amberline', year: 2026, found: true, requestState: 'requestable' }

let qc: QueryClient
beforeEach(() => {
  qc = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } })
  qc.setQueryData(watchlistsQuery.queryKey, { items: [watchlist(1, 'Watchlist'), watchlist(5, 'Halloween')], max: 25, maxTitles: 500 })
  qc.setQueryData(['requests', 'options'], { movies: true, shows: true })
  qc.setQueryData(['libraries'], { items: [] })
})

const auth = { status: 'ready', user: { id: 1, name: 'Robin', isAdmin: false }, adminAllowed: false } as AuthContextValue

async function draw(node: ReactNode): Promise<string> {
  const router = createRouter({ routeTree: createRootRoute({ component: () => node }), history: createMemoryHistory({ initialEntries: ['/'] }) })
  await router.load()
  return renderToStaticMarkup(
    <AuthContext.Provider value={auth}>
      <QueryClientProvider client={qc}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </AuthContext.Provider>,
  )
}

function page(l: MediaList, items: MediaListEntry[], view: { mediaType?: 'movie' | 'tv' } = {}) {
  qc.setQueryData(mediaListQuery(l.id).queryKey, l)
  qc.setQueryData(mediaListEntriesQuery(l.id, 0, MAX_ENTRIES, view).queryKey, { items, total: items.length, inLibrary: items.filter((e) => e.item).length, offset: 0, limit: MAX_ENTRIES })
  return draw(<ListPage listId={l.id} search={{ type: view.mediaType }} />)
}

const text = (html: string) => html.replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ')

describe('a watchlist’s page', () => {
  it('has the watchlist’s kicker, counts and actions, and none of a fetched list’s', async () => {
    const html = await page(list(), [inLibrary, missing])
    const words = text(html)
    expect(words).toContain('Watchlist Halloween 2 titles · 1 in your library')
    expect(words).toMatch(/Rename .*Delete/)
    for (const gone of ['From watchlist', 'Updated', 'Refresh', 'Edit', 'in the list’s order']) expect(words).not.toContain(gone)
    expect(html).not.toMatch(/min-w-\[22px\]/) // no rank numbers
  })

  it('offers Remove on every title, Request on a missing one, and no Add to Watchlist', async () => {
    const html = await page(list(), [inLibrary, missing])
    expect(html).toContain('aria-label="Remove Black Orchard from Halloween"')
    expect(html).toContain('aria-label="Remove Amberline from Halloween"')
    expect(html).toContain('aria-label="Request Amberline"')
    expect(text(html)).toContain('2026 · Not in your library')
    expect(html).not.toContain('Add to')
  })

  it.each([
    ['keeps watched titles', false, 'false'],
    ['removes them', true, 'true'],
  ])('shows the switch off when it %s', async (_case, removeWatched, checked) => {
    const html = await page(list({ removeWatched }), [inLibrary])
    expect(html).toMatch(new RegExp(`role="switch" aria-checked="${checked}"`))
    expect(text(html)).toContain('Remove titles once watched')
  })

  it.each([
    ['All', {}, 'aria-pressed="true"[^>]*>All<'],
    ['Movies', { mediaType: 'movie' as const }, 'aria-pressed="true"[^>]*>Movies<'],
  ])('marks %s as chosen', async (_case, view, pressed) => {
    const html = await page(list(), [inLibrary], view)
    expect(html).toMatch(new RegExp(pressed))
  })

  it.each([
    ['nothing on it', list({ entries: 0, inLibrary: 0 }), [], {}, 'Nothing on this watchlist yet'],
    ['no shows of the type chosen', list(), [], { mediaType: 'tv' as const }, 'No TV shows on this watchlist.'],
  ])('says so when it has %s', async (_case, l, items, view, words) => {
    expect(text(await page(l, items, view))).toContain(words)
  })
})

describe('another list’s page', () => {
  it('offers saving a missing title, not one the library has', async () => {
    const html = await page(list({ id: 6, name: 'Popular movies', source: { kind: 'tmdb', chart: 'popular' } }), [inLibrary, missing])
    expect(html).toContain('aria-label="Save to watchlist…: Amberline"')
    expect(html.match(/Save to watchlist…: /g)).toHaveLength(1)
    expect(html).not.toContain('Remove Amberline')
  })
})

describe('the Watchlist button', () => {
  it.each([
    ['on none, with one watchlist', [watchlist(1, 'Watchlist')], [], 'Watchlist', 'false', false],
    ['on its only watchlist', [watchlist(1, 'Watchlist')], [1], 'On Watchlist', 'true', false],
    ['on one of several', [watchlist(1, 'Watchlist'), watchlist(2, 'Halloween')], [2], 'On Halloween', 'true', true],
    ['on two', [watchlist(1, 'Watchlist'), watchlist(2, 'Halloween')], [1, 2], 'On 2 watchlists', 'true', true],
  ])('%s says %s', async (_case, items, holding, label, pressed, picker) => {
    qc.setQueryData(watchlistsQuery.queryKey, { items, max: 25, maxTitles: 500 })
    const html = await draw(<WatchlistButton target={{ mediaType: 'movie', tmdbId: 101 }} title="Ninety Fathoms" holding={holding} />)
    expect(text(html).trim()).toBe(label)
    expect(html).toContain(`aria-pressed="${pressed}"`)
    expect(html.includes('aria-haspopup="dialog"')).toBe(picker)
  })
})
