import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryHistory, createRootRoute, createRouter, RouterProvider } from '@tanstack/react-router'
import { renderToStaticMarkup } from 'react-dom/server'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { components } from '../../api/schema'

// The rows at the end of a movie's and a show's page:
// drawn from the server's reply as Home's poster rows, and nothing at all
// when there are none or the request fails. The API is a stand-in that
// answers with the reply each test sets, and records what was asked.

type BrowseItem = components['schemas']['BrowseItem']
type RelatedRowsReply = components['schemas']['RelatedRows']

let reply: RelatedRowsReply | 'fail' = { rows: [] }
const asked: string[] = []
vi.mock('../../api/client', () => ({
  api: {
    GET: async (path: string, o: { params: { path: { id: number } } }) => {
      asked.push(path.replace('{id}', String(o.params.path.id)))
      return reply === 'fail'
        ? { error: { detail: 'unavailable' }, response: new Response(null, { status: 503 }) }
        : { data: reply, response: new Response(null, { status: 200 }) }
    },
  },
  unwrap: (res: { data?: unknown; error?: unknown }) => {
    if (res.error !== undefined) throw new Error('unavailable')
    return res.data
  },
}))

const { movieRelatedQuery, showRelatedQuery } = await import('../../api/queries')
const { RelatedRows } = await import('./RelatedRows')

const movie = (id: number, title: string, year: number, over: Partial<BrowseItem> = {}): BrowseItem => ({
  id, kind: 'movie', title, year, libraryId: 2, addedAt: '2026-11-01T00:00:00Z', genres: [], hdr: false, isNew: false, overview: '', totalSize: 0, watched: false, ...over,
})
const show = (id: number, title: string, year: number, episodeCount: number): BrowseItem => ({
  id, kind: 'show', title, year, episodeCount, libraryId: 3, addedAt: '2026-11-01T00:00:00Z', genres: [], hdr: false, isNew: false, overview: '', totalSize: 0, watched: false,
})

const collection = { id: 'collection', style: 'poster', title: 'The Fathoms Collection', items: [movie(11, 'Forty Fathoms', 2019, { watched: true }), movie(12, 'Sixty Fathoms', 2022, { progress: 0.62 })] }
const similar = {
  id: 'similar', style: 'poster', title: 'More like this',
  items: [movie(21, 'Dead Reckoning Bay', 2024, { isNew: true }), movie(22, 'The Salt Vault', 2023), movie(23, 'Undertow Station', 2021)],
}

let qc: QueryClient
beforeEach(() => {
  asked.length = 0
  qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
})

/** Fetches the page's related rows as the page would, then draws the end of the page. */
async function draw(kind: 'movie' | 'show', id: number): Promise<string> {
  await qc.prefetchQuery(kind === 'movie' ? movieRelatedQuery(id) : showRelatedQuery(id))
  const rootRoute = createRootRoute({ component: () => <RelatedRows kind={kind} id={id} /> })
  const router = createRouter({ routeTree: rootRoute, history: createMemoryHistory({ initialEntries: ['/'] }) })
  await router.load()
  return renderToStaticMarkup(
    <QueryClientProvider client={qc}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
}

const headings = (html: string) => [...html.matchAll(/<h2[^>]*>([^<]*)/g)].map((m) => m[1])
const titles = (html: string) => [...html.matchAll(/<div class="mt-2[^"]*"><a [^>]*>([^<]*)<\/a>/g)].map((m) => m[1])
const links = (html: string) => [...new Set([...html.matchAll(/href="([^"]*)"/g)].map((m) => m[1]))]

describe('the end of a movie page', () => {
  it('draws the collection, then More like this, as poster rows linking to each movie', async () => {
    reply = { rows: [collection, similar] }
    const html = await draw('movie', 7)
    expect(asked).toEqual(['/api/movies/7/related'])
    expect(headings(html)).toEqual(['The Fathoms Collection', 'More like this'])
    expect(titles(html)).toEqual(['Forty Fathoms', 'Sixty Fathoms', 'Dead Reckoning Bay', 'The Salt Vault', 'Undertow Station'])
    expect(links(html)).toEqual(['/movies/11', '/movies/12', '/movies/21', '/movies/22', '/movies/23'])
    // Home's marks: watched, partly watched and New.
    expect(html).toContain('title="Watched"')
    expect(html).toContain('width:62%')
    expect(html).toContain('>New<')
    // Under the line, full width, with no See all.
    expect(html).toMatch(/^<div class="[^"]*border-t border-line/)
    expect(html).not.toContain('See all')
  })

  it('draws More like this alone when the movie has no collection', async () => {
    reply = { rows: [similar] }
    const html = await draw('movie', 7)
    expect(headings(html)).toEqual(['More like this'])
    expect(titles(html)).toEqual(['Dead Reckoning Bay', 'The Salt Vault', 'Undertow Station'])
  })

  it('keeps a collection’s name whole', async () => {
    reply = { rows: [{ ...collection, title: 'Night · Day Collection' }] }
    expect(headings(await draw('movie', 7))).toEqual(['Night · Day Collection'])
  })

  it('draws nothing when nothing qualifies', async () => {
    reply = { rows: [] }
    expect(await draw('movie', 7)).toBe('')
  })

  it('draws nothing for rows with no titles in them', async () => {
    reply = { rows: [{ ...similar, items: [] }] }
    expect(await draw('movie', 7)).toBe('')
  })

  it('draws nothing when the request fails, and the page ends as before', async () => {
    reply = 'fail'
    expect(await draw('movie', 7)).toBe('')
    expect(asked).toEqual(['/api/movies/7/related'])
  })

  it('never draws a See all, even when a row brings a link', async () => {
    reply = { rows: [{ ...similar, seeAll: { kind: 'list', listId: 4 } }] }
    const html = await draw('movie', 7)
    expect(html).not.toContain('See all')
    expect(links(html)).not.toContain('/lists/4')
  })
})

describe('the end of a show page', () => {
  it('draws More like this with each show’s year and episodes, linking to the show', async () => {
    reply = { rows: [{ id: 'similar', style: 'poster', title: 'More like this', items: [show(31, 'Saltmarsh', 2023, 16), show(32, 'Tidewater Lane', 2020, 24), show(33, 'Fogbound', 2018, 18)] }] }
    const html = await draw('show', 9)
    expect(asked).toEqual(['/api/shows/9/related'])
    expect(headings(html)).toEqual(['More like this'])
    expect(titles(html)).toEqual(['Saltmarsh', 'Tidewater Lane', 'Fogbound'])
    expect(links(html)).toEqual(['/shows/31', '/shows/32', '/shows/33'])
    expect(html).toContain('2023 · 16 ep')
  })

  it('draws nothing when the request fails', async () => {
    reply = 'fail'
    expect(await draw('show', 9)).toBe('')
  })
})
