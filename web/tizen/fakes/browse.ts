import { FakeServer, problem } from './server'
import { fakeHome, LIBRARIES, MOVIE_ITEMS, SHOW_ITEMS } from './data'

/**
 * Home, the libraries and what the rail needs: Live TV's capabilities
 * (on, so the rail shows it) and the request options (an admin with two in
 * the Inbox, so Settings shows its count).
 */
export function registerBrowse(server: FakeServer) {
  server.route('GET', '/api/libraries', () => ({ items: LIBRARIES }))
  server.route('GET', '/api/libraries/:id', ({ params }) => LIBRARIES.find((l) => l.id === Number(params.id)) ?? problem(404, 'no such library'))
  server.route('GET', '/api/home', () => fakeHome())
  server.route('GET', '/api/libraries/:id/items', ({ params, url }) => {
    const id = Number(params.id)
    let items = id === 1 ? [...MOVIE_ITEMS] : id === 2 ? [...SHOW_ITEMS] : []
    // Many more of each, so the grid has pages to load.
    items = Array.from({ length: 6 }, (_, n) => items.map((i) => ({ ...i, id: i.id + n * 1000, title: n ? `${i.title} ${n + 1}` : i.title }))).flat()
    const sort = url.searchParams.get('sort')
    if (sort === 'title') items.sort((a, b) => a.title.localeCompare(b.title))
    else if (sort === 'year') items.sort((a, b) => (b.year ?? 0) - (a.year ?? 0))
    const limit = Number(url.searchParams.get('limit') ?? 48)
    const offset = Number(url.searchParams.get('offset') ?? 0)
    return { items: items.slice(offset, offset + limit), total: items.length, limit, offset, facets: { genres: ['Drama', 'Comedy', 'Thriller'], decades: [2010, 2020] } }
  })
  server.route('GET', '/api/live-tv/capabilities', () => ({ enabled: true, allowed: true, playback: true, guide: true, setupComplete: true, recording: true, canRecord: true }))
  server.route('GET', '/api/requests/options', () => ({ movies: true, shows: true, needsApproval: false, problems: true, manage: true, backend: 'arr', inbox: 2, delete: true }))
}
