import type { components } from '../../src/api/schema'
import { FakeServer, problem } from './server'
import { AIRED, MOVIE_ITEMS, SHOW_ITEMS } from './data'

// Search for the preview: the library's titles and episodes by name, and
// On Live TV's listings (one on now, two later, one with a reminder), with
// Hold OK's reminders. Every title, channel and name is made up.

type Movie = components['schemas']['Movie']
type Show = components['schemas']['Show']
type SearchEpisode = components['schemas']['SearchEpisode']
type LiveHit = components['schemas']['LiveTVSearchHit']
type LiveChannel = components['schemas']['LiveTVChannel']
type Reminder = components['schemas']['LiveTVReminder']

const has = (title: string, q: string) => title.toLowerCase().includes(q)

const MOVIES: Movie[] = [...MOVIE_ITEMS, { ...MOVIE_ITEMS[0], id: 151, title: 'Harbor at Dusk', year: 2011, runtimeMin: 98 }].map((m) => ({
  id: m.id, libraryId: m.libraryId, tmdbId: 9000 + m.id, title: m.title, overview: m.overview, genres: m.genres, fileCount: 1, credits: [], year: m.year, runtimeMin: m.runtimeMin,
}))
const SHOWS: Show[] = SHOW_ITEMS.map((s) => ({
  id: s.id, libraryId: s.libraryId, tmdbId: 9000 + s.id, title: s.title, overview: s.overview, genres: s.genres, episodeCount: s.episodeCount ?? 10, credits: [], year: s.year, status: s.status,
}))
const EPISODES: SearchEpisode[] = AIRED.map((e) => ({
  id: e.episodeId, showId: e.showId, showTitle: e.showTitle, seasonNumber: e.seasonNumber, number: e.episodeNumber, title: e.title, fileId: e.fileId ?? 0,
}))

const channel = (id: string, number: string, name: string): LiveChannel => ({ id, sourceId: 'fake-tuner', number, name, enabled: true, available: true })
const CHANNELS = { vault: channel('ch-20-1', '20.1', 'VAULT'), lake: channel('ch-7-1', '7.1', 'LKSD-HD'), harbor: channel('ch-2-1', '2.1', 'HRBR-HD') }

export function registerSearch(server: FakeServer) {
  server.route('GET', '/api/search', ({ url }) => {
    const q = (url.searchParams.get('q') ?? '').trim().toLowerCase()
    if (!q) return problem(422, 'q is required')
    return { movies: MOVIES.filter((m) => has(m.title, q)), shows: SHOWS.filter((s) => has(s.title, q)), episodes: EPISODES.filter((e) => has(e.title, q)) }
  })

  // Listings around now, made when asked, so "on now" stays on now.
  const hits = (): LiveHit[] => {
    const now = Date.now()
    const hour = new Date(now); hour.setMinutes(0, 0, 0)
    const at = (h: number) => new Date(hour.getTime() + h * 3_600_000).toISOString()
    return [
      { channel: CHANNELS.vault, programme: { id: 'p-glass', title: 'The Glass Harbor', kind: 'movie', airDate: '1949', start: at(-1), end: at(1), categories: ['Movie'] } },
      { channel: CHANNELS.lake, programme: { id: 'p-station', title: 'Station 9', kind: 'episode', season: 4, episode: 12, episodeTitle: 'The Harbor Fire', start: at(2), end: at(3) } },
      { channel: CHANNELS.harbor, programme: { id: 'p-news', title: 'Harbor News at 10', kind: 'show', categories: ['News'], start: at(3), end: at(4) } },
    ]
  }
  let reminders: Reminder[] = []
  const withReminders = (h: LiveHit): LiveHit => ({ ...h, programme: { ...h.programme, reminder: reminders.some((r) => r.programmeId === h.programme.id) } })
  server.route('GET', '/api/live-tv/guide/search', ({ url }) => {
    const words = (url.searchParams.get('q') ?? '').toLowerCase().split(/\s+/).filter(Boolean)
    return { items: hits().filter((h) => words.every((w) => has(h.programme.title, w))).map(withReminders) }
  })
  server.route('GET', '/api/live-tv/reminders', () => ({ items: reminders, leadMinutes: 5 }))
  server.route('PUT', '/api/live-tv/reminders', ({ body }) => {
    const hit = hits().find((h) => h.programme.id === (body as { programmeId?: string }).programmeId)
    if (!hit) return problem(404, 'no such programme')
    const r: Reminder = { id: `r-${hit.programme.id}`, programmeId: hit.programme.id, channelId: hit.channel.id, number: hit.channel.number, channelName: hit.channel.name,
      title: hit.programme.title, start: hit.programme.start, end: hit.programme.end, state: 'set' }
    reminders = [...reminders.filter((x) => x.programmeId !== r.programmeId), r]
    return r
  })
  server.route('DELETE', '/api/live-tv/reminders/:id', ({ params }) => { reminders = reminders.filter((r) => r.id !== params.id); return undefined })
}
