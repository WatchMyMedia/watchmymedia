import type { components } from '../../src/api/schema'
import { FakeServer, problem } from './server'

// Settings' routes for the preview: the account's preferences, each screen's
// rows and the lists they come from, the shows followed and the signed-in
// devices. `mode` "savefail" refuses every change to the preferences;
// "nosettings" doesn't answer for them at all (the not-loaded state).

type Preferences = components['schemas']['Preferences']
type RowLayoutEntry = components['schemas']['RowLayoutEntry']
type MediaList = components['schemas']['MediaList']
type Follow = components['schemas']['Follow']
type Session = components['schemas']['Session']

const ago = (ms: number) => new Date(Date.now() - ms).toISOString()
const H = 3_600_000, D = 24 * H

const row = (id: string, title: string, o: Partial<RowLayoutEntry> = {}): RowLayoutEntry =>
  ({ id, title, style: 'poster', kind: 'builtin', hidden: false, shown: true, ...o })
const list = (listId: number, title: string, owner: 'me' | 'everyone', o: Partial<RowLayoutEntry> = {}): RowLayoutEntry =>
  row(`list:${listId}`, title, { kind: 'list', listId, owner, ...o })

/** The rows each screen starts with; Home's as the design draws them. */
function defaults(): Record<string, RowLayoutEntry[]> {
  return {
    home: [
      row('continue', 'Continue watching', { style: 'continue' }),
      row('live', 'Live TV', { style: 'live' }),
      list(11, 'Best of the 1980s', 'everyone'),
      row('recentlyAddedShows', 'Recently added · TV Shows'),
      row('recentlyAired', 'Recently aired · Episodes', { style: 'episode', hidden: true, shown: false }),
      row('recentlyAddedMovies', 'Recently added · Movies'),
      row('recentlyReleased', 'Recently released · Movies', { style: 'wide' }),
      list(12, 'Sam’s watchlist', 'me'),
      list(13, 'Festival favourites', 'everyone', { shown: false }),
      list(14, 'Trending this week', 'everyone', { hidden: true, shown: false }),
      list(15, 'Unwatched in 4K', 'me', { hidden: true, shown: false }),
    ],
    'library-1': [row('continue', 'Continue watching', { style: 'continue' }), row('recentlyAddedMovies', 'Recently added'), row('recentlyReleased', 'Recently released', { style: 'wide' }), list(11, 'Best of the 1980s', 'everyone')],
    'library-2': [row('continue', 'Continue watching', { style: 'continue' }), row('recentlyAddedShows', 'Recently added'), row('recentlyAired', 'Recently aired', { style: 'episode' })],
  }
}

const mediaList = (id: number, name: string, owner: 'me' | 'everyone', kind: string, inLibrary: number, entries: number): MediaList => ({
  id, name, mediaType: 'all', source: { kind }, owner, canEdit: owner === 'me', screen: 'home', limit: 50, entries, inLibrary, fetching: false, createdAt: ago(30 * D), updatedAt: ago(D),
})

const LISTS: MediaList[] = [
  mediaList(11, 'Best of the 1980s', 'everyone', 'tmdb', 18, 50),
  mediaList(12, 'Sam’s watchlist', 'me', 'import', 9, 24),
  mediaList(13, 'Festival favourites', 'everyone', 'mdblist', 0, 40),
  mediaList(14, 'Trending this week', 'everyone', 'tmdb', 6, 20),
  mediaList(15, 'Unwatched in 4K', 'me', 'library', 7, 7),
]

export function registerSettings(server: FakeServer, mode: string) {
  let prefs: Preferences = {
    autoplayNext: true,
    liveTVReminderMinutes: 5,
    skip: { intro: 'ask', recap: 'ask', credits: 'ask', preview: 'none', commercial: 'ask' },
    audio: { languages: ['es', 'en'] },
    subtitles: { languages: ['es', 'en'], mode: 'forced', foreignAudio: true, automaticDownload: true },
  }
  server.route('GET', '/api/me/preferences', () => mode === 'nosettings' ? problem(503, 'preferences unavailable') : prefs)
  server.route('PUT', '/api/me/preferences', ({ body }) => {
    if (mode === 'savefail') return problem(503, 'try again later')
    const b = body as components['schemas']['UpdatePreferencesInputBody']
    prefs = {
      ...prefs,
      ...(b.autoplayNext !== undefined && { autoplayNext: b.autoplayNext }),
      ...(b.liveTVReminderMinutes !== undefined && { liveTVReminderMinutes: b.liveTVReminderMinutes }),
      skip: { ...prefs.skip, ...b.skip },
      audio: b.audio?.languages ? { languages: b.audio.languages } : prefs.audio,
      subtitles: { ...prefs.subtitles!, ...b.subtitles } as Preferences['subtitles'],
    }
    return prefs
  })

  const layouts = defaults()
  const saved = new Set<string>()
  server.route('GET', '/api/me/rows/:screen', ({ params }) => layouts[params.screen] ? { screen: params.screen, rows: layouts[params.screen], customized: saved.has(params.screen) } : problem(404, 'no such screen'))
  server.route('PUT', '/api/me/rows/:screen', ({ params, body }) => {
    const rows = layouts[params.screen]
    if (!rows) return problem(404, 'no such screen')
    const placed = (body as { rows: { id: string; hidden?: boolean }[] }).rows
    const next = placed.flatMap((p) => { const r = rows.find((x) => x.id === p.id); return r ? [{ ...r, hidden: !!p.hidden, shown: !p.hidden && (r.kind !== 'list' || (LISTS.find((l) => `list:${l.id}` === r.id)?.inLibrary ?? 0) > 0) }] : [] })
    layouts[params.screen] = [...next, ...rows.filter((r) => !next.some((n) => n.id === r.id))]
    saved.add(params.screen)
    return { screen: params.screen, rows: layouts[params.screen], customized: true }
  })
  server.route('DELETE', '/api/me/rows/:screen', ({ params }) => {
    layouts[params.screen] = defaults()[params.screen] ?? []
    saved.delete(params.screen)
    return { screen: params.screen, rows: layouts[params.screen], customized: false }
  })
  server.route('GET', '/api/lists', () => ({ items: LISTS }))

  let follows: Follow[] = [
    { tmdbId: 9201, title: 'Harbor Lights', followedAt: ago(40 * D), show: { id: 201, title: 'Harbor Lights' }, latest: { season: 3, number: 7, arrivedAt: ago(3 * H) } },
    { tmdbId: 9204, title: 'The Quiet Meridian', followedAt: ago(30 * D), show: { id: 204, title: 'The Quiet Meridian' }, latest: { season: 2, number: 1, arrivedAt: ago(D) } },
    { tmdbId: 9999, title: 'The Lantern House', followedAt: ago(20 * D) },
  ]
  server.route('GET', '/api/me/follows', () => ({ follows }))
  server.route('DELETE', '/api/me/follows/:tmdbId', ({ params }) => { follows = follows.filter((f) => f.tmdbId !== Number(params.tmdbId)); return undefined })

  const device = (id: string, name: string, o: Partial<Session>): Session => ({
    id, name, userId: 1, userName: 'Sam', kind: 'device', createdAt: ago(60 * D), lastSeenAt: ago(0), expiresAt: new Date(Date.now() + 300 * D).toISOString(), current: false, ...o,
  })
  let sessions: Session[] = [
    device('fake-this-tv', 'Samsung TV', { current: true, product: 'WatchMyMedia for Samsung TV', platform: 'tizen' }),
    device('fake-ipad', 'Kitchen iPad', { model: 'iPad', product: 'WatchMyMedia for iPad', lastSeenAt: ago(2 * H) }),
    device('fake-apple-tv', 'Bedroom Apple TV', { model: 'Apple TV 4K', lastSeenAt: ago(3 * D) }),
    device('fake-phone', 'Sam’s phone', { model: 'Android', lastSeenAt: ago(D) }),
  ]
  server.route('GET', '/api/auth/sessions', () => ({ items: sessions }))
  server.route('DELETE', '/api/auth/sessions/:id', ({ params }) => {
    if (!sessions.some((s) => s.id === params.id)) return problem(404, 'no such session')
    sessions = sessions.filter((s) => s.id !== params.id)
    return undefined
  })
}
