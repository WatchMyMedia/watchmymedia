import type { components } from '../../src/api/schema'
import { FakeServer, problem } from './server'
import { FAKE_USER } from './data'

// Requests for the preview: what Search finds on TMDB, the request dialog's
// lookups, Sam's own requests and the admins' Inbox (two people's requests
// and a reported problem), with the options worked out from them, so the
// rail's Inbox count moves as they are decided. `mode` "seerr" carries them
// out through Seerr instead of Radarr and Sonarr. Every title and name is
// made up.

type MediaRequest = components['schemas']['MediaRequest']
type Requestable = components['schemas']['Requestable']
type RequestableSeason = components['schemas']['RequestableSeason']
type Problem = components['schemas']['Problem']

const ago = (ms: number) => new Date(Date.now() - ms).toISOString()
const H = 3_600_000, D = 24 * H

const season = (number: number, episodeCount: number, year: number, state: RequestableSeason['state']): RequestableSeason =>
  ({ number, name: number === 0 ? 'Specials' : `Season ${number}`, episodeCount, airDate: `${year}-09-01`, state })

/** TMDB's side: titles Search can find that the library may not have. */
const REQUESTABLE: Requestable[] = [
  { mediaType: 'tv', tmdbId: 7001, title: 'Harborwick', year: 2021, state: 'requestable', overview: 'Three generations of a boatbuilding family share one slipway, one debt and one grudge against the yard across the water.',
    seasons: [season(1, 8, 2021, 'requested'), season(2, 10, 2022, 'missing'), season(3, 10, 2024, 'missing'), season(4, 6, 2026, 'unaired')] },
  { mediaType: 'movie', tmdbId: 7002, title: 'Harbor Lights: Low Water', year: 2024, state: 'requested', overview: 'A feature-length special: the winter the harbour froze, told from the one ferry that could not leave the quay.' },
  { mediaType: 'tv', tmdbId: 7003, title: 'Saltharbor', year: 2023, state: 'downloading', overview: 'A made-up series about a salt works and the town it keeps.', seasons: [season(1, 8, 2023, 'requested'), season(2, 8, 2025, 'missing')] },
  { mediaType: 'movie', tmdbId: 7004, title: 'The Harbor Choir', year: 2018, state: 'requestable', overview: 'A made-up film about a choir that sings on the night ferry.' },
  { mediaType: 'tv', tmdbId: 9201, title: 'Harbor Lights', year: 2022, state: 'available', showId: 201, overview: 'A harbourmaster keeps the channel open.',
    seasons: [season(0, 4, 2022, 'available'), season(1, 10, 2022, 'available'), season(2, 10, 2023, 'available'), season(3, 7, 2025, 'partial')] },
  { mediaType: 'movie', tmdbId: 7005, title: 'Lantern Street Nights', year: 2020, state: 'requestable', overview: 'A made-up film set on one street over one night.' },
  { mediaType: 'tv', tmdbId: 7006, title: 'Northbound', year: 2019, state: 'requestable', overview: 'A made-up series on a night train north.', seasons: [season(1, 6, 2019, 'missing'), season(2, 6, 2021, 'missing')] },
]

const request = (id: number, title: string, o: Partial<MediaRequest>): MediaRequest => ({
  id, title, mediaType: 'tv', tmdbId: 8000 + id, status: 'pending', requestedBy: FAKE_USER.name, mine: true, seasons: [1], createdAt: ago(D), ...o,
})

export function registerRequests(server: FakeServer, mode: string) {
  const backend = mode === 'seerr' ? 'seerr' : 'arr'
  let mine: MediaRequest[] = [
    request(1, 'Harbor Lights: Low Water', { mediaType: 'movie', tmdbId: 7002, seasons: [], createdAt: ago(2 * H) }),
    request(2, 'Harborwick', { tmdbId: 7001, status: 'approved', createdAt: ago(5 * H) }),
    request(3, 'The Lantern House', { status: 'declined', note: 'Already on the other server', seasons: [3], createdAt: ago(D) }),
    request(4, 'Saltharbor', { tmdbId: 7003, status: 'approved', download: { state: 'downloading', progress: .45 }, createdAt: ago(2 * D) }),
    request(5, 'Driftwood', { status: 'available', showId: 207, createdAt: ago(4 * D) }),
    request(6, 'The Kestrel Accord', { mediaType: 'movie', seasons: [], status: 'failed', note: 'Radarr has no root folder for this library', createdAt: ago(7 * D) }),
  ]
  let others: MediaRequest[] = [
    request(11, 'Copperline', { requestedBy: 'Alex', mine: false, seasons: [5], createdAt: ago(3 * H) }),
    request(12, 'Paper Moons', { mediaType: 'movie', seasons: [], requestedBy: 'Robin', mine: false, status: 'failed', note: 'Radarr has no root folder for this library', createdAt: ago(D) }),
    request(13, 'Winter Marsh', { mediaType: 'movie', seasons: [], requestedBy: 'Alex', mine: false, status: 'approved', download: { state: 'downloading', progress: .7 }, createdAt: ago(2 * D) }),
  ]
  let problems: Problem[] = [
    { id: 21, reportedBy: 'Robin', itemType: 'episode', itemId: 6001, kind: 'video', note: 'The picture freezes after ten minutes', details: 'Harbor.Lights.S03E02.mkv · HEVC · 1080p', title: 'Harbor Lights · S3 E2', createdAt: ago(D), showId: 201, season: 3 },
  ]
  let next = 100
  const waiting = () => others.filter((r) => r.status === 'pending' || r.status === 'failed').length + problems.length

  server.route('GET', '/api/requests/options', () => ({ movies: true, shows: true, needsApproval: !FAKE_USER.isAdmin, problems: true, manage: true, backend, inbox: waiting(), delete: true }))
  server.route('GET', '/api/requests/search', ({ url }) => {
    const q = (url.searchParams.get('q') ?? '').trim().toLowerCase()
    return { items: q ? REQUESTABLE.filter((r) => r.title.toLowerCase().includes(q)).map((r) => ({ ...r, seasons: undefined })) : [] }
  })
  server.route('GET', '/api/requests/lookup', ({ url }) => {
    const id = Number(url.searchParams.get('tmdbId'))
    const found = REQUESTABLE.find((r) => r.tmdbId === id && r.mediaType === url.searchParams.get('mediaType'))
    return found ?? problem(404, 'not on TMDB')
  })
  server.route('POST', '/api/requests', ({ body }) => {
    const b = body as { mediaType: 'movie' | 'tv'; tmdbId: number; seasons?: number[] }
    const item = REQUESTABLE.find((r) => r.tmdbId === b.tmdbId)
    if (!item) return problem(404, 'not on TMDB')
    const r = request(++next, item.title, { mediaType: b.mediaType, tmdbId: b.tmdbId, seasons: b.seasons ?? [], createdAt: ago(0), status: FAKE_USER.isAdmin ? 'approved' : 'pending' })
    mine = [r, ...mine]
    item.state = 'requested'
    for (const s of item.seasons ?? []) if (b.seasons?.includes(s.number)) s.state = 'requested'
    return { created: true, request: r }
  })
  server.route('GET', '/api/requests/mine', () => ({ items: mine }))
  server.route('DELETE', '/api/requests/:id', ({ params }) => {
    const id = Number(params.id)
    if (!mine.some((r) => r.id === id)) return problem(404, 'no such request')
    mine = mine.filter((r) => r.id !== id)
    return undefined
  })
  server.route('GET', '/api/requests/inbox', () => ({
    requests: others, problems,
    ...(backend === 'seerr' && { seerr: { pending: 2, requestsUrl: 'http://lakeside.local:5055/requests' } }),
  }))
  server.route('POST', '/api/requests/:id/decision', ({ params, body }) => {
    const id = Number(params.id)
    const r = others.find((x) => x.id === id)
    if (!r) return problem(404, 'no such request')
    const { approve, note } = body as { approve: boolean; note?: string }
    // Approving the failed one fails again, as when Radarr still refuses it.
    const decided: MediaRequest = !approve ? { ...r, status: 'declined', note } : r.status === 'failed' ? { ...r, status: 'failed' } : { ...r, status: 'approved' }
    others = decided.status === 'declined' ? others.filter((x) => x.id !== id) : others.map((x) => x.id === id ? decided : x)
    return decided
  })
  server.route('POST', '/api/problems/:id/resolve', ({ params }) => {
    problems = problems.filter((p) => p.id !== Number(params.id))
    return undefined
  })
}
