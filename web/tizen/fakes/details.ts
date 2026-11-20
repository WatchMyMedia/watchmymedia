import type { components } from '../../src/api/schema'
import { setTrailerEmbed } from '../screens/details/trailerPlayer'
import { FAKE_USER, MOVIE_ITEMS, SHOW_ITEMS } from './data'
import { FakeServer, json, problem } from './server'

// The movie and show pages' fictional data (Movie, MovieRelated, ShowPage and
// ShowRelated on the Samsung TV canvas): Dead Reckoning Bay with two versions,
// a collection and More like this; trailers of both kinds; Harbor Lights with
// Specials and a season with missing, downloading and unaired episodes. Marks,
// follows, deletes and monitoring change it for the visit. Every title and
// name is made up, and there are no pictures. `?fakes=member` signs in Sam as
// a member rather than an admin, without Sonarr's actions.

type MovieDetail = components['schemas']['MovieDetail']
type MediaFileDetail = components['schemas']['MediaFileDetail']
type MediaInfo = components['schemas']['MediaInfo']
type ShowDetail = components['schemas']['ShowDetail']
type Season = components['schemas']['Season']
type Episode = components['schemas']['Episode']
type BrowseItem = components['schemas']['BrowseItem']
type Credit = components['schemas']['Credit']
type PlaybackPlan = components['schemas']['PlaybackPlan']

const DAY = 86_400_000
const now = Date.now()
const iso = (offsetDays: number) => new Date(now + offsetDays * DAY).toISOString()
const day = (offsetDays: number) => iso(offsetDays).slice(0, 10)
const GB = 1024 ** 3

const CAST: Credit[] = [
  { name: 'Ida Markham', character: 'Nell Kerr' }, { name: 'Tomas Reyes', character: 'Aldo' }, { name: 'June Okafor', character: 'Harbourmaster' },
  { name: 'Piet Lindqvist', character: 'The buyer' }, { name: 'Rosa Bell', character: 'Coastguard' }, { name: 'Wren Adeyemi', character: 'Diver' },
]

function fileDetail(id: number, relativePath: string, over: Partial<MediaFileDetail>): MediaFileDetail {
  return {
    id, libraryId: 1, path: `/media/movies/${relativePath}`, relativePath, size: 8.1 * GB, modifiedAt: iso(-30), status: 'present', parsed: { kind: 'movie', title: '' },
    firstSeenAt: iso(-30), addedAt: iso(-30), lastSeenAt: iso(-1), matchStatus: 'matched', matchLocked: false, matchIgnored: false,
    container: 'mov,mp4', videoCodec: 'h264', audioCodec: 'aac', width: 1920, height: 1080, durationMs: 100 * 60_000, ...over,
  }
}

const probe = (audio: MediaInfo['audio'], subtitles: MediaInfo['subtitles'], video: MediaInfo['video']): MediaInfo => ({ container: 'matroska', formatName: 'matroska', durationMs: 0, video, audio, subtitles })

/** A movie's page, from its Home card: Dead Reckoning Bay as the board draws it, the rest plainer. */
function movieDetail(item: BrowseItem, index: number): MovieDetail {
  const base = item.id % 1000
  const key = `fakeTrailer${String(base).padStart(3, '0')}`
  const versions = base === 102
    ? [
      fileDetail(item.id * 10 + 1, 'Dead Reckoning Bay (2026)/Dead.Reckoning.Bay.2026.2160p.DV.mkv', {
        container: 'matroska', videoCodec: 'hevc', audioCodec: 'truehd', width: 3840, height: 2160, hdr: 'dolbyvision', dvProfile: 8, size: 48.2 * GB,
        probe: probe([
          { index: 1, codec: 'truehd', channels: 8, channelLayout: '7.1', language: 'eng', default: true },
          { index: 2, codec: 'ac3', channels: 2, channelLayout: 'stereo', language: 'eng', title: 'Commentary', default: false },
        ], [
          { index: 3, codec: 'subrip', language: 'eng', title: 'SDH', default: false, forced: false, image: false },
          { index: 4, codec: 'hdmv_pgs_subtitle', language: 'eng', default: false, forced: false, image: true },
        ], [{ index: 0, codec: 'hevc', width: 3840, height: 2160, default: true, hdr: 'dolbyvision', dvProfile: 8 }]),
      }),
      fileDetail(item.id * 10 + 2, 'Dead Reckoning Bay (2026)/Dead.Reckoning.Bay.2026.1080p.mp4', {
        probe: probe([
          { index: 1, codec: 'aac', channels: 6, channelLayout: '5.1', language: 'eng', default: true },
          { index: 2, codec: 'aac', channels: 2, channelLayout: 'stereo', language: 'spa', default: false },
        ], [{ index: 3, codec: 'subrip', language: 'eng', default: false, forced: false, image: false }], [{ index: 0, codec: 'h264', width: 1920, height: 1080, default: true }]),
      }),
    ]
    : [fileDetail(item.id * 10 + 1, `${item.title} (${item.year})/${item.title.replace(/ /g, '.')}.${item.year}.1080p.mp4`, {
      probe: probe([{ index: 1, codec: 'aac', channels: 6, channelLayout: '5.1', language: 'eng', default: true }], [], [{ index: 0, codec: 'h264', width: 1920, height: 1080, default: true }]),
    })]
  // Trailers for each state of the trailer screen: one that plays, a file, one kept on YouTube then a teaser, only kept ones, and one that never answers.
  const trailers: MovieDetail['trailers'] =
    base === 101 ? [{ source: 'file', fileId: 9101, name: 'Official Trailer', durationMs: 151_000 }]
      : base === 103 ? [{ source: 'youtube', key: 'fakeRefused103', name: 'Official Trailer' }, { source: 'youtube', key: 'fakeTeaser0103', name: 'Teaser' }]
        : base === 104 ? [{ source: 'youtube', key: 'fakeRefused104', name: 'Official Trailer' }]
          : base === 105 ? [{ source: 'youtube', key: 'fakeSilent0105', name: 'Official Trailer' }]
            : base === 106 ? []
              : [{ source: 'youtube', key, name: 'Official Trailer' }]
  const runtime = base === 102 ? 134 : item.runtimeMin ?? 100
  return {
    id: item.id, libraryId: 1, tmdbId: 50_000 + item.id, title: item.title, year: item.year,
    overview: base === 102 ? 'A salvage diver takes a cash job to raise a yacht that sank in calm water, and finds the manifest was written after it went down.' : item.overview,
    genres: base === 102 ? ['Thriller'] : item.genres, runtimeMin: runtime, contentRating: index % 3 === 0 ? 'R' : 'PG-13', rating: base === 102 ? 7.6 : item.rating,
    quality: base === 102 ? '4K' : '1080p', hdr: base === 102, files: versions, fileCount: versions.length, totalSize: versions.reduce((n, v) => n + v.size, 0),
    credits: base === 102 ? CAST : CAST.slice(index % 3, index % 3 + 4), trailers, managedBy: 'Radarr',
    watch: watchOf(`movie:${item.id}`, versions[0].id, runtime * 60_000),
  }
}

/** The films of Dead Reckoning's collection the library has, in release order. */
const COLLECTION: BrowseItem[] = [
  ['Dead Reckoning', 2017, { watched: true }], ['Dead Reckoning: Low Tide', 2020, { watched: true }], ['Dead Reckoning: Slack Water', 2023, { progress: 0.35 }],
].map(([title, year, over], i) => ({
  kind: 'movie', id: 151 + i, libraryId: 1, libraryType: 'movie', title: title as string, year: year as number, overview: `A made-up film of the collection (${i + 1}).`,
  genres: ['Thriller'], hdr: false, addedAt: iso(-200), isNew: false, totalSize: 6 * GB, watched: false, runtimeMin: 110, rating: 7, ...(over as object),
}))

/** The movies the fakes know: Home's, and the collection's. */
const MOVIES = [...MOVIE_ITEMS, ...COLLECTION]

// --- watching, for the visit ---

/** Positions and marks, by "movie:<id>" or "episode:<id>"; seeded with what Continue watching shows. */
const watching = new Map<string, { positionMs: number; completed: boolean }>([
  ['movie:102', { positionMs: 62 * 60_000, completed: false }],
  ['movie:101', { positionMs: 66 * 60_000, completed: false }],
  ['movie:151', { positionMs: 0, completed: true }], ['movie:152', { positionMs: 0, completed: true }], ['movie:153', { positionMs: 39 * 60_000, completed: false }],
])
for (const m of MOVIE_ITEMS) if (m.watched) watching.set(`movie:${m.id}`, { positionMs: 0, completed: true })

function watchOf(key: string, fileId: number, durationMs: number): MovieDetail['watch'] {
  const w = watching.get(key)
  return w ? { positionMs: w.positionMs, durationMs, completed: w.completed, updatedAt: iso(-1), fileId } : undefined
}

// --- shows ---

type FakeEpisode = { title: string; aired: boolean; airDate: string; file: boolean; runtime: number; monitored?: boolean; download?: Episode['download']; isNew?: boolean }
type FakeSeason = { number: number; airDate: string; monitored?: boolean; episodes: FakeEpisode[] }

const NAMES = ['Low Water', 'The Pilot Boat', 'Breakwater', 'Fog Signal', 'Neap', 'Slack Water', 'The Long Dark', 'Ebb', 'Spring Tide', 'Moorings', 'Lantern Hours', 'Dead Reckoning', 'Undertow', 'Salt Road', 'The Keeper', 'Crosscurrent', 'Harbour Master', 'Riptide', 'Shoals', 'Leeward', 'Night Ferry', 'Last Light']
const OVERVIEWS = [
  'A storm closes the harbour for the first time in forty years, and Maren has one night to find out who moved the channel markers before the ferry comes in.',
  'Hal takes the pilot boat out alone against orders, and comes back with a logbook that should have gone down with the Aldine.',
  'The town meeting turns on Maren when the harbour board votes to sell the lighthouse keeper’s cottage.',
  'Dr. Okafor finds the same paint on two wrecks thirty years apart, and asks the wrong person what it means.',
]

const plain = (n: number, count: number, airedDaysAgo: number, file = true): FakeEpisode[] =>
  Array.from({ length: count }, (_, i) => ({ title: NAMES[(n * 5 + i) % NAMES.length], aired: true, airDate: day(-airedDaysAgo + i * 7), file, runtime: 47 + ((n * 7 + i * 3) % 7) }))

/** Harbor Lights as the boards draw it, and the other shows plainer. */
function seasonsOf(showId: number): FakeSeason[] {
  const base = showId % 1000
  if (base === 201) return [
    { number: 1, airDate: day(-1460), monitored: false, episodes: plain(1, 8, 1460, false) },
    { number: 2, airDate: day(-700), episodes: plain(2, 10, 700) },
    { number: 3, airDate: day(-60), episodes: [
      ...plain(3, 4, 60),
      { title: 'Neap', aired: true, airDate: day(-32), file: false, runtime: 50, monitored: true },
      { title: 'Slack Water', aired: true, airDate: day(-25), file: false, runtime: 49, monitored: true, download: { state: 'downloading', progress: 0.62, finishesAt: iso(8 / 1440) } },
      { title: 'The Long Dark', aired: true, airDate: day(-18), file: true, runtime: 52 },
      { title: 'Ebb', aired: true, airDate: day(-1), file: true, runtime: 51, isNew: true },
      { title: 'Spring Tide', aired: false, airDate: day(2), file: false, runtime: 48 },
      { title: 'Moorings', aired: false, airDate: day(9), file: false, runtime: 50 },
    ] },
    { number: 0, airDate: day(-500), episodes: [{ title: 'Behind the Breakwater', aired: true, airDate: day(-500), file: true, runtime: 24 }, { title: 'The Lighthouse Tapes', aired: true, airDate: day(-400), file: true, runtime: 31 }] },
  ]
  const item = SHOW_ITEMS.find((s) => s.id === base)
  const count = base === 205 ? 1 : base === 202 ? 4 : 2
  const per = Math.max(4, Math.ceil((item?.episodeCount ?? 10) / count))
  return Array.from({ length: count }, (_, k) => ({ number: k + 1, airDate: day(-(count - k) * 365), episodes: plain(base + k, per, (count - k) * 365) }))
}

const epId = (showId: number, season: number, n: number) => showId * 10_000 + season * 100 + n
const fileOf = (episodeId: number) => episodeId * 10 + 1

/** Episodes watched as the show starts: Harbor Lights to its season 3 E04, and the Continue watching ones partway. */
function seedShows() {
  for (const s of seasonsOf(201)) s.episodes.forEach((_, i) => { if (s.number === 2 || (s.number === 3 && i < 4)) watching.set(`episode:${epId(201, s.number, i + 1)}`, { positionMs: 0, completed: true }) })
  watching.set(`episode:${epId(201, 3, 7)}`, { positionMs: 23 * 60_000 + 10_000, completed: false })
  const partway: [number, number, number, number][] = [[204, 2, 4, 28], [205, 1, 9, 40], [202, 4, 1, 6], [203, 1, 2, 3]]
  for (const [show, season, n, min] of partway) {
    for (let k = 1; k < season; k++) for (let i = 1; i <= 20; i++) watching.set(`episode:${epId(show, k, i)}`, { positionMs: 0, completed: true })
    for (let i = 1; i < n; i++) watching.set(`episode:${epId(show, season, i)}`, { positionMs: 0, completed: true })
    watching.set(`episode:${epId(show, season, n)}`, { positionMs: min * 60_000, completed: false })
  }
}
seedShows()

const follows = new Set<number>()
const monitored = new Map<string, boolean>()
const deleted = new Set<number>()

function episodeOf(showId: number, season: FakeSeason, e: FakeEpisode, i: number): Episode {
  const id = epId(showId, season.number, i + 1)
  const file = e.file && !deleted.has(id)
  const w = watching.get(`episode:${id}`)
  const mon = monitored.get(`${showId}:${season.number}`)
  return {
    id, seasonId: showId * 100 + season.number, number: i + 1, title: e.title, overview: OVERVIEWS[(season.number + i) % OVERVIEWS.length],
    hasFile: file, aired: e.aired, airDate: e.airDate, runtimeMin: e.runtime, rating: e.aired ? 8 + ((i * 7 + season.number) % 9) / 10 : undefined,
    isNew: !!e.isNew && !w?.completed, monitored: deleted.has(id) ? false : mon ?? e.monitored, download: file ? undefined : e.download, addedAt: iso(-3),
    files: file ? [{
      id: fileOf(id), libraryId: 2, path: `/media/tv/show/${id}.mkv`, relativePath: `Season ${String(season.number).padStart(2, '0')}/S${String(season.number).padStart(2, '0')}E${String(i + 1).padStart(2, '0')}.${e.title.replace(/[’' ]+/g, '.')}.2160p.mkv`,
      size: 4.2 * GB, modifiedAt: iso(-20), status: 'present', parsed: { kind: 'episode', title: '' }, firstSeenAt: iso(-20), addedAt: iso(-20), lastSeenAt: iso(-1),
      matchStatus: 'matched', matchLocked: false, matchIgnored: false, container: 'matroska', videoCodec: 'hevc', audioCodec: 'eac3', width: 3840, height: 2160, hdr: 'hdr10', durationMs: e.runtime * 60_000,
    }] : [],
    watch: file && w ? { positionMs: w.positionMs, durationMs: e.runtime * 60_000, completed: w.completed, updatedAt: iso(-1), fileId: fileOf(id) } : undefined,
  }
}

function seasonSummary(showId: number, s: FakeSeason): Season {
  const eps = s.episodes.map((e, i) => episodeOf(showId, s, e, i))
  return {
    id: showId * 100 + s.number, showId, number: s.number, name: s.number === 0 ? 'Specials' : `Season ${s.number}`, overview: '', airDate: s.airDate,
    episodeCount: eps.length, availableCount: eps.filter((e) => e.hasFile).length, watchedCount: eps.filter((e) => e.hasFile && e.watch?.completed).length,
    monitored: monitored.get(`${showId}:${s.number}`) ?? s.monitored,
  }
}

function showDetail(item: BrowseItem): ShowDetail {
  const seasons = seasonsOf(item.id)
  const all = seasons.flatMap((s) => s.episodes.map((e, i) => ({ s, e: episodeOf(item.id, s, e, i) })))
  const next = all.filter(({ s }) => s.number > 0).find(({ e }) => e.hasFile && !e.watch?.completed)
  const upcoming = all.find(({ e }) => !e.aired)
  const summaries = seasons.map((s) => seasonSummary(item.id, s))
  return {
    id: item.id, libraryId: 2, tmdbId: 60_000 + item.id, title: item.title, year: item.year, overview: item.id % 1000 === 201
      ? 'A harbour pilot in a fading fishing town starts to suspect that the lights guiding ships home have been moved on purpose, and that the people who moved them still live on her street.'
      : item.overview,
    genres: item.genres, status: item.status, rating: 8.1, seasons: summaries, episodeCount: summaries.reduce((n, s) => n + s.availableCount, 0),
    totalEpisodes: summaries.reduce((n, s) => n + s.episodeCount, 0), fileCount: summaries.reduce((n, s) => n + s.availableCount, 0), totalSize: 120 * GB, hdr: true,
    credits: CAST.slice(1, 6), managedBy: 'Sonarr', lastAirDate: day(-1), nextAirDate: upcoming?.e.airDate, following: follows.has(item.id),
    nextEpisode: next && { episodeId: next.e.id, seasonNumber: next.s.number, episodeNumber: next.e.number, title: next.e.title, fileId: next.e.files[0].id },
  }
}

// --- the requests the pages make ---

const findMovie = (id: number) => { const i = MOVIES.findIndex((m) => m.id === id % 1000); return i < 0 ? undefined : { item: { ...MOVIES[i], id }, index: i } }
const findShow = (id: number) => { const s = SHOW_ITEMS.find((x) => x.id === id % 1000); return s && { ...s, id } }
const withWatch = (item: BrowseItem): BrowseItem => {
  const w = watching.get(`${item.kind}:${item.id}`)
  return { ...item, watched: w?.completed ?? item.watched, progress: w && !w.completed && w.positionMs ? 0.4 : item.progress }
}
const options = { movies: true, shows: true, needsApproval: true, problems: true, manage: false, backend: 'arr', inbox: 0, delete: false }

/** Episodes of a show, by id, for marks and deletes. */
function episodeById(id: number): { showId: number; season: FakeSeason; index: number } | undefined {
  const showId = Math.floor(id / 10_000)
  const season = seasonsOf(showId).find((s) => s.number === Math.floor((id % 10_000) / 100))
  const index = (id % 100) - 1
  return season && season.episodes[index] ? { showId, season, index } : undefined
}

export function registerDetails(server: FakeServer, mode = '') {
  if (mode === 'member') {
    // A member: no Sonarr actions, requests that wait for an admin. Registered before Home's, so its options answer first.
    FAKE_USER.isAdmin = false
    server.route('GET', '/api/requests/options', () => options)
  }
  if (typeof location !== 'undefined') setTrailerEmbed({ origin: location.origin, srcdoc: fakeYouTube })

  server.route('GET', '/api/movies/:id', ({ params }) => {
    const found = findMovie(Number(params.id))
    return found ? movieDetail(found.item, found.index) : problem(404, 'no such movie')
  })
  server.route('GET', '/api/movies/:id/related', ({ params }) => {
    const id = Number(params.id)
    const similar = MOVIE_ITEMS.filter((m) => m.id !== id % 1000).map(withWatch)
    return { rows: [
      ...(id % 1000 === 102 ? [{ id: 'collection', style: 'poster', title: 'Dead Reckoning Collection', items: COLLECTION.map(withWatch) }] : []),
      { id: 'similar', style: 'poster', title: 'More like this', items: similar.slice(0, 12) },
    ] }
  })
  server.route('POST', '/api/playback/plans', ({ body }) => {
    const ids = (body as { fileIds?: number[] })?.fileIds ?? []
    // The 4K Dolby Vision file's TrueHD goes to EAC3 in a remux on this TV; the rest play directly.
    const items: PlaybackPlan[] = ids.map((fileId) => fileId === 1021
      ? { fileId, mode: 'audio', protocol: 'hls', reasons: ['audio codec truehd'], video: { copy: true, toneMap: false }, audio: { index: 1, copy: false, codec: 'eac3' } }
      : { fileId, mode: 'direct', protocol: 'file', reasons: [], video: { copy: true, toneMap: false }, audio: { index: 1, copy: true } })
    return { items }
  })
  for (const method of ['POST', 'DELETE']) {
    server.route(method, '/api/watch/:type/:id/watched', ({ params }) => {
      watching.set(`${params.type}:${params.id}`, { positionMs: 0, completed: method === 'POST' })
      return { completed: method === 'POST' }
    })
    server.route(method, '/api/shows/:id/seasons/:number/watched', ({ params }) => {
      const id = Number(params.id)
      const s = seasonsOf(id).find((x) => x.number === Number(params.number))
      s?.episodes.forEach((e, i) => { const ep = episodeOf(id, s, e, i); if (ep.hasFile) watching.set(`episode:${ep.id}`, { positionMs: 0, completed: method === 'POST' }) })
      return new Response(null, { status: 204 })
    })
    server.route(method, '/api/shows/:id/watched', ({ params }) => {
      const id = Number(params.id)
      for (const s of seasonsOf(id)) s.episodes.forEach((e, i) => { const ep = episodeOf(id, s, e, i); if (ep.hasFile) watching.set(`episode:${ep.id}`, { positionMs: 0, completed: method === 'POST' }) })
      return new Response(null, { status: 204 })
    })
  }
  server.route('POST', '/api/problems', ({ body }) => json({ id: 7, reportedBy: FAKE_USER.name, note: '', details: '', title: '', createdAt: iso(0), ...(body as object) }, 201))

  server.route('GET', '/api/shows/:id', ({ params }) => {
    const show = findShow(Number(params.id))
    return show ? showDetail(show) : problem(404, 'no such show')
  })
  server.route('GET', '/api/shows/:id/seasons/:number', ({ params }) => {
    const show = findShow(Number(params.id))
    const s = show && seasonsOf(show.id).find((x) => x.number === Number(params.number))
    if (!show || !s) return problem(404, 'no such season')
    return { ...seasonSummary(show.id, s), show: { id: show.id, title: show.title }, episodes: s.episodes.map((e, i) => episodeOf(show.id, s, e, i)) }
  })
  server.route('GET', '/api/shows/:id/related', ({ params }) => ({ rows: [
    { id: 'similar', style: 'poster', title: 'More like this', items: SHOW_ITEMS.filter((s) => s.id !== Number(params.id) % 1000).map(withWatch) },
  ] }))
  server.route('PUT', '/api/shows/:id/follow', ({ params }) => { follows.add(Number(params.id)); return new Response(null, { status: 204 }) })
  server.route('DELETE', '/api/shows/:id/follow', ({ params }) => { follows.delete(Number(params.id)); return new Response(null, { status: 204 }) })
  server.route('POST', '/api/shows/:id/search', () => new Response(null, { status: 202 }))
  server.route('POST', '/api/shows/:id/monitor', ({ params, body }) => {
    const b = body as { season: number; monitored: boolean }
    monitored.set(`${params.id}:${b.season}`, b.monitored)
    return new Response(null, { status: 204 })
  })
  server.route('GET', '/api/files/:id', ({ params }) => {
    const id = Number(params.id)
    const at = episodeById(Math.floor(id / 10))
    if (!at) return problem(404, 'no such file')
    const ep = episodeOf(at.showId, at.season, at.season.episodes[at.index], at.index)
    return { ...ep.files[0], probe: probe([{ index: 1, codec: 'eac3', channels: 6, channelLayout: '5.1(side)', language: 'eng', default: true }], [
      { index: 2, codec: 'subrip', language: 'eng', default: false, forced: false, image: false }, { index: 3, codec: 'subrip', language: 'spa', default: false, forced: false, image: false },
    ], [{ index: 0, codec: 'hevc', width: 3840, height: 2160, default: true, hdr: 'hdr10' }]) }
  })
  server.route('GET', '/api/delete/preview', ({ url }) => {
    const id = Number(url.searchParams.get('id'))
    const at = episodeById(id)
    if (url.searchParams.get('type') !== 'episode' || !at) return problem(404, 'nothing to delete')
    const s = String(at.season.number).padStart(2, '0')
    return {
      managed: true, files: 1, size: 4.2 * GB, kept: 0, choices: { keep: false, exclude: false, keepMonitored: true, airing: false },
      apps: [{ connectionId: 1, connection: 'Sonarr', kind: 'sonarr', folder: `/tv/Show/Season ${s}`, localFolder: `/media/tv/Show/Season ${s}`, files: 1, size: 4.2 * GB, extra: 0, recycleBin: '/recycle', recycleDays: 7 }],
    }
  })
  server.route('POST', '/api/episodes/:id/delete', ({ params }) => {
    deleted.add(Number(params.id))
    return { apps: [{ connectionId: 1, connection: 'Sonarr', files: 1, size: 4.2 * GB }], requests: 0 }
  })
  server.route('POST', '/api/files/:id/replace', () => ({ connection: 'Sonarr', blocklisted: true }))
}

/**
 * The preview's stand-in for YouTube's player: a page that speaks the IFrame
 * API's messages (listening, onReady, onStateChange, infoDelivery, onError)
 * and plays nothing. A key with "Refused" refuses as an owner's video does
 * (150); "Silent" never answers, so the screen gives up after 20 s.
 */
function fakeYouTube(key: string): string {
  const mode = /Refused/.test(key) ? 'refused' : /Silent/.test(key) ? 'silent' : 'plays'
  return `<!doctype html><html><head><meta charset="utf-8"><style>
html,body{margin:0;height:100%;background:#101828;color:#e5e5e5;font:28px system-ui,sans-serif;overflow:hidden}
body{display:grid;place-items:center;background:radial-gradient(60% 50% at 50% 40%,#2b2f4a,#0b0d16)}
#bar{position:fixed;left:56px;right:56px;bottom:56px;height:8px;border-radius:4px;background:rgba(255,255,255,.25)}
#bar span{display:block;height:100%;width:0;background:#e5e5e5;border-radius:4px}
p{margin:0;text-align:center}small{display:block;margin-top:12px;font-size:20px;color:#a3a3a3}
</style></head><body><p>The preview’s stand-in for YouTube’s player<small id="t">0:00 / 2:31</small></p><div id="bar"><span id="b"></span></div><script>
var mode=${JSON.stringify(mode)},state=-1,t=0,d=151,heard=false;
function post(ev,info){parent.postMessage(JSON.stringify({event:ev,info:info,id:1,channel:'widget'}),'*')}
function clock(s){s=Math.floor(s);return Math.floor(s/60)+':'+('0'+s%60).slice(-2)}
function draw(){document.getElementById('t').textContent=clock(t)+' / '+clock(d)+(state===2?' · paused':'');document.getElementById('b').style.width=(100*t/d)+'%'}
function deliver(){post('infoDelivery',{currentTime:t,duration:d,playerState:state})}
function set(s){state=s;post('onStateChange',s);deliver();draw()}
addEventListener('message',function(e){var m;try{m=JSON.parse(e.data)}catch(x){return}
if(m.event==='listening'){if(mode==='silent'||heard)return;heard=true;post('initialDelivery',{duration:d,currentTime:0,playerState:-1});set(5);post('onReady',{});
if(mode==='refused')setTimeout(function(){post('onError',150)},700);return}
if(m.event!=='command')return;
if(m.func==='playVideo'&&mode==='plays')set(1);else if(m.func==='pauseVideo'&&state===1)set(2);
else if(m.func==='seekTo'){t=Math.max(0,Math.min(d,m.args[0]));deliver();draw()}});
setInterval(function(){if(state!==1)return;t=Math.min(d,t+.25);if(t>=d)set(0);else{deliver();draw()}},250);
</script></body></html>`
}
