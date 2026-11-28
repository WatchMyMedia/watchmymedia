import type { components } from '../../src/api/schema'
import { FakePlayer, type OpenOptions } from '../player'
import { AIRED, CONTINUE, MOVIE_ITEMS, SHOW_ITEMS } from './data'
import { FakeServer, problem } from './server'
import { effects, withTiming } from './dialogue'

// The player's side of the fictional server, and a simulated stream for
// the browser preview, where the fake server has no media a <video> could
// open. Decisions carry what the player draws: intro and credits, three
// audio tracks, the file's subtitles and OpenSubtitles, seek thumbnails
// (whose sheets never load: there are no pictures), the next episode, and
// two recordings, one still recording. Every title is made up.
//
// ?fakes=player adds the rest of the player's states: Automatic lowers the
// quality after 20 s, The Paper Kite is stopped by an administrator after
// 15 s, Copperline's episode can't start, and Still watching's clock runs a
// thousand times fast (screens/player/playerSession.ts), so it asks at the
// next episode after a few quiet seconds.

type Decision = components['schemas']['Decision']
type DecisionRequest = components['schemas']['Request']
type SubtitleTrack = components['schemas']['SubtitleTrack']
type UpNext = components['schemas']['UpNext']
type Episode = components['schemas']['Episode']
type Segment = components['schemas']['Segment']

const MIN = 60_000
const at = (ms: number) => new Date(ms).toISOString()

// ------------------------------------------------------------------ the library's files

type FakeFile = {
  id: number
  durationMs: number
  /** An episode's place in its show. */
  episode?: { showId: number; showTitle: string; season: number; number: number; episodeId: number; title: string }
  movieId?: number
  resumeMs: number
}

const EPISODE_TITLES = ['Low Water', 'The Pilot Boat', 'Breakwater', 'Fog Signal', 'Undertow', 'Slipway', 'The Long Dark', 'Ebb', 'Riptide', 'Shoals']
const SEASONS: Record<number, number> = { 202: 4 }
const EPISODES_A_SEASON = 10

/** The file of an episode: Home's own where Home names one, else one made from its place. */
function episodeFileId(showId: number, season: number, number: number): number {
  const known = CONTINUE.find((c) => c.showId === showId && c.seasonNumber === season && c.episodeNumber === number)
    ?? AIRED.find((a) => a.showId === showId && a.seasonNumber === season && a.episodeNumber === number)
  return known?.fileId ?? showId * 1000 + season * 100 + number
}

const runtime = (showId: number, number: number) => 46 + ((showId + number * 3) % 9)

let library: Map<number, FakeFile> | undefined

function files(): Map<number, FakeFile> {
  if (library) return library
  library = new Map()
  for (const show of SHOW_ITEMS) {
    const seasons = SEASONS[show.id] ?? 3
    for (let season = 0; season <= seasons; season++) {
      const count = season === 0 ? 2 : EPISODES_A_SEASON
      for (let number = 1; number <= count; number++) {
        const id = episodeFileId(show.id, season, number)
        const resume = CONTINUE.find((c) => c.fileId === id)?.positionMs ?? 0
        library.set(id, {
          id, durationMs: runtime(show.id, number) * MIN, resumeMs: resume,
          episode: { showId: show.id, showTitle: show.title, season, number, episodeId: show.id * 1000 + season * 100 + number,
            title: season === 0 ? ['Behind the Breakwater', 'The Lighthouse Tapes'][number - 1] : EPISODE_TITLES[(number - 1) % EPISODE_TITLES.length] },
        })
      }
    }
  }
  for (const movie of MOVIE_ITEMS) {
    const id = 1000 + movie.id
    library.set(id, { id, movieId: movie.id, durationMs: (movie.runtimeMin ?? 100) * MIN, resumeMs: CONTINUE.find((c) => c.fileId === id)?.positionMs ?? 0 })
  }
  return library
}

/** Any file id plays: one the fakes don't know is a film of 1 h 40 min (a trailer file the details' fakes name, say). */
const fileOf = (id: number): FakeFile => files().get(id) ?? { id, durationMs: id >= 1900 && id < 2000 ? 151_000 : 100 * MIN, resumeMs: 0 }

function nextOf(file: FakeFile): UpNext | undefined {
  const e = file.episode
  if (!e || e.season === 0) return undefined
  const seasons = SEASONS[e.showId] ?? 3
  const [season, number] = e.number < EPISODES_A_SEASON ? [e.season, e.number + 1] : e.season < seasons ? [e.season + 1, 1] : [0, 0]
  if (!season) return undefined
  const next = fileOf(episodeFileId(e.showId, season, number)).episode
  return next && { fileId: episodeFileId(e.showId, season, number), episodeId: next.episodeId, showId: e.showId, showTitle: e.showTitle,
    seasonNumber: season, episodeNumber: number, title: next.title, runtimeMin: runtime(e.showId, number), seasonFinale: number === EPISODES_A_SEASON }
}

function segmentsOf(file: FakeFile): Segment[] {
  const d = file.durationMs
  const credits: Segment = { id: file.id * 10 + 2, kind: 'credits', startMs: d - (file.episode ? 145_000 : 300_000), final: true, confidence: 1, source: 'chapter' }
  if (!file.episode) return file.movieId ? [credits] : []
  return [{ id: file.id * 10 + 1, kind: 'intro', startMs: 60_000, endMs: 152_000, final: true, confidence: 1, source: 'fingerprint' }, credits]
}

// ------------------------------------------------------------------ tracks

const AUDIO: Decision['audioTracks'] = [
  { index: 1, codec: 'eac3', channels: 6, default: true, language: 'en' },
  { index: 2, codec: 'aac', channels: 2, default: false, language: 'fr' },
  { index: 3, codec: 'ac3', channels: 2, default: false, language: 'en', title: 'Commentary' },
]

function subtitlesOf(id: number): SubtitleTrack[] {
  return [
    { id: 's1', codec: 'subrip', format: 'webvtt', burnInOnly: false, default: false, external: false, forced: false, language: 'en', url: `/api/files/${id}/subtitles/s1.vtt` },
    { id: 's2', codec: 'subrip', format: 'webvtt', burnInOnly: false, default: false, external: true, forced: false, hearingImpaired: true, language: 'en', title: 'SDH', url: `/api/files/${id}/subtitles/s2.vtt` },
    { id: 's3', codec: 'hdmv_pgs_subtitle', format: 'image', burnInOnly: true, default: false, external: false, forced: false, language: 'es' },
    ...(downloaded.get(id) ?? []),
  ]
}

const downloaded = new Map<number, SubtitleTrack[]>()

const LINES = ['Tide’s turning.', 'Did you hear the bell?', 'The pilot boat is late again.', 'Keep the lamp lit.', 'Nobody goes out in this fog.', 'Then who rang it?', 'Check the logbook.', 'Something’s on the water.']

function vtt(name: string, durationMs: number): string {
  const time = (ms: number) => new Date(ms).toISOString().slice(11, 23)
  let out = 'WEBVTT\n\n'
  for (let t = 4_000, n = 0; t < durationMs; t += 8_000, n++) {
    const line = LINES[n % LINES.length]
    out += `${time(t)} --> ${time(t + 3_500)}\n${name === 's2' && n % 3 === 0 ? `[waves]\n${line}` : line}\n\n`
  }
  return out
}

// ------------------------------------------------------------------ the simulated stream

/** How long each stream address the fakes handed out runs, for the simulated player; a growing recording's grows. */
const lengths = new Map<string, () => number>()

const PICTURE = `data:image/svg+xml,${encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1920 1080"><defs><linearGradient id="g" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#0C1A22"/><stop offset=".44" stop-color="#1B3542"/><stop offset=".46" stop-color="#0A1216"/><stop offset="1" stop-color="#06090B"/></linearGradient><radialGradient id="s" cx=".6" cy=".44" r=".4"><stop offset="0" stop-color="#FFCD78" stop-opacity=".22"/><stop offset="1" stop-color="#FFCD78" stop-opacity="0"/></radialGradient></defs><rect width="1920" height="1080" fill="url(#g)"/><rect width="1920" height="1080" fill="url(#s)"/></svg>')}`

/**
 * The preview's stand-in for AVPlay on the fake server: a FakePlayer whose
 * time runs by itself and ends at the stream's length, over a still picture
 * on the video plane, so the page's see-through background shows.
 * Development only; it is not an AVPlay test.
 */
export class PreviewPlayer extends FakePlayer {
  private length?: () => number
  private live = false

  constructor(plane?: HTMLVideoElement) {
    super()
    // Shown only while a player holds the video surface (app/surface.ts).
    plane?.setAttribute('poster', PICTURE)
    setInterval(() => this.tick(250), 250)
  }

  private tick(ms: number) {
    if (this.live && this.window) this.window = { startMs: this.window.startMs, endMs: this.window.endMs + ms }
    if (this.state !== 'playing') return
    const end = this.length?.()
    const next = this.positionMs + ms
    if (end !== undefined && !this.live && next >= end) { this.emit({ positionMs: end, state: 'ended' }); return }
    this.emit({ positionMs: next, ...(end !== undefined && { durationMs: end }) })
  }

  override async open(url: string, options: OpenOptions = {}) {
    let path = url
    try { path = new URL(url).pathname } catch { /* kept as it is */ }
    this.length = lengths.get(path)
    this.live = !!options.live
    if (this.live) this.window = { startMs: 0, endMs: 60 * MIN }
    await super.open(url, options)
    const end = this.length?.()
    if (end !== undefined) this.emit({ durationMs: end })
  }
}

export function previewPlayer(plane?: HTMLVideoElement) { return new PreviewPlayer(plane) }

// ------------------------------------------------------------------ recordings

const startedAgo = 25 * MIN
const recordingStart = Date.now() - startedAgo

const RECORDINGS: Record<string, { recording: components['schemas']['LiveTVRecording']; fileId: number; durationMs: () => number; growing?: Decision['growing']; segments: Segment[] }> = {
  'rec-news': {
    recording: recording({ id: 'rec-news', title: 'Harbor Evening News', number: '2.1', channelName: 'HRBR-HD', start: at(recordingStart + MIN), end: at(recordingStart + 31 * MIN), state: 'recording', startedAt: at(recordingStart) }),
    fileId: 990_001,
    durationMs: () => Date.now() - recordingStart,
    growing: { startedAt: at(recordingStart), endsAt: at(recordingStart + 33 * MIN) },
    segments: [],
  },
  'rec-vane': {
    recording: recording({ id: 'rec-vane', title: 'Inspector Vane', number: '4.2', channelName: 'SLTH', start: at(Date.now() - 2 * 86_400_000), end: at(Date.now() - 2 * 86_400_000 + 60 * MIN), state: 'completed', season: 3, episode: 5, episodeTitle: 'Lantern Street' }),
    fileId: 990_002,
    durationMs: () => 63 * MIN,
    segments: [
      { id: 91, kind: 'commercial', startMs: (14 * 60 + 10) * 1000, endMs: (16 * 60 + 40) * 1000, final: true, confidence: .9, source: 'blackframe' },
      { id: 92, kind: 'commercial', startMs: (38 * 60 + 20) * 1000, endMs: (41 * 60 + 30) * 1000, final: true, confidence: .9, source: 'blackframe' },
    ],
  },
}

function recording(r: Partial<components['schemas']['LiveTVRecording']> & Pick<components['schemas']['LiveTVRecording'], 'id' | 'title' | 'number' | 'channelName' | 'start' | 'end' | 'state'>): components['schemas']['LiveTVRecording'] {
  return { bytes: 0, canCancel: false, channelId: `ch-${r.number}`, gaps: [], padAfter: 2, padBefore: 1, series: false, sourceId: 'hdhr', userName: 'Sam', ...r }
}

// ------------------------------------------------------------------ decisions

/** Automatic's step per file once the fake lowered it; a play's first report, for ?fakes=player's timed directives. */
const stepped = new Map<string, string>()
const firstBeat = new Map<string, number>()
let plays = 0

const SIZES: Record<string, [number, number]> = { 2160: [3840, 2160], 1080: [1920, 1080], 720: [1280, 720], 480: [854, 480], 360: [640, 360] }

function decide(key: string, length: () => number, body: DecisionRequest | undefined, extra: Partial<Decision>): Decision {
  const quality = body?.quality ?? 'auto'
  const step = quality === 'auto' ? stepped.get(key) ?? 'original' : quality
  const capped = step !== 'original'
  const [width, height] = SIZES[step] ?? [3840, 2160]
  const subtitleId = body?.subtitleId
  const subtitles = extra.subtitles ?? []
  const burn = body?.burnIn ? subtitles.find((s) => s.id === subtitleId) : undefined
  const transcoding = capped || !!burn || !!extra.growing
  const session = transcoding ? `fake-${key.replace(/\W/g, '')}-${++plays}` : undefined
  const url = session ? `/api/playback/${session}/master.m3u8` : extra.url ?? '/api/files/0/stream'
  lengths.set(url, length)
  return {
    protocol: session ? 'hls' : 'file', mode: transcoding ? 'transcode' : 'direct', url, sessionId: session, playlist: session ? (extra.growing ? 'event' : 'vod') : undefined,
    playId: body?.playId ?? `play-${key}-${++plays}`, durationMs: length(), startMs: extra.growing ? 0 : body?.startMs ?? 0, resumeMs: 0, reasons: [],
    // Boost dialogue or Reduce loud sounds on: the sound is converted, and the decision says with what.
    audio: { copy: !transcoding && !effects(), index: body?.audioIndex ?? 1, codec: transcoding ? 'aac' : 'eac3', channels: transcoding ? 2 : 6, ...(effects() && { effects: effects() }) },
    video: { copy: !transcoding, codec: 'hevc', width, height, hdr: capped ? undefined : 'hdr10', toneMap: capped },
    audioTracks: AUDIO, subtitles, subtitleId, burnIn: burn, segments: [], creditsMs: 0, upNextMs: 0, watchedAtMs: Math.round(length() * .9),
    mediaToken: { token: 'fake-media', param: 'access_token', expiresAt: at(Date.now() + 4 * 60 * MIN) },
    ...(quality === 'auto' && { auto: { step: step as NonNullable<Decision['auto']>['step'], reason: capped ? 'slow' : 'start' } }),
    ...extra,
    // Each track with the person's timing, or the one saved for everyone; a burned-in one's applied.
    ...(key.startsWith('file:') && { subtitles: subtitles.map((s) => withTiming(Number(key.slice(5)), s, s.id === burn?.id)) }),
    ...(session && { url }),
  }
}

function fileDecision(file: FakeFile, body: DecisionRequest | undefined): Decision {
  const segments = segmentsOf(file)
  const next = nextOf(file)
  const credits = segments.find((s) => s.kind === 'credits')?.startMs ?? 0
  const count = Math.ceil(file.durationMs / 10_000)
  return decide(`file:${file.id}`, () => file.durationMs, body, {
    url: `/api/files/${file.id}/stream`, resumeMs: file.resumeMs, segments, subtitles: subtitlesOf(file.id),
    creditsMs: credits, upNextMs: next ? credits : 0, nextEpisode: next,
    trickplay: { url: `/api/files/${file.id}/trickplay/320/{sheet}.jpg`, width: 320, height: 180, columns: 10, rows: 10, intervalMs: 10_000, count, sheets: Math.ceil(count / 100) },
  })
}

type Directive = components['schemas']['ActivityDirective']

/** ?fakes=player: the server's directives, by how long the play has been reporting. */
function directive(mode: string, key: string, playId: string, quality: string | undefined): Directive | undefined {
  if (mode !== 'player') return undefined
  const first = firstBeat.get(playId) ?? Date.now()
  firstBeat.set(playId, first)
  const since = Date.now() - first
  if (key === 'file:1101' && since > 15_000) return { action: 'stop', message: 'Dinner’s ready. Pause it and come down.' }
  if (quality === 'auto' && since > 20_000 && !stepped.has(key)) { stepped.set(key, '720'); return { action: 'quality', quality: '720' } }
  return undefined
}

// ------------------------------------------------------------------ routes

export function registerPlayer(server: FakeServer, mode = '') {
  const qualities = new Map<string, string>()
  server.route('POST', '/api/files/:id/playback', ({ params, body }) => {
    const id = Number(params.id)
    if (mode === 'player' && id === 3201) return problem(503, 'unavailable')
    const request = body as DecisionRequest | undefined
    const d = fileDecision(fileOf(id), request)
    qualities.set(d.playId, request?.quality ?? 'auto')
    return d
  })
  server.route('PUT', '/api/files/:id/progress', ({ params, body }) => {
    const b = body as { playId: string; positionMs: number; durationMs: number }
    const key = `file:${params.id}`
    return { completed: b.positionMs >= b.durationMs * .9, positionMs: b.positionMs, durationMs: b.durationMs, updatedAt: at(Date.now()), playId: b.playId, directive: directive(mode, key, b.playId, qualities.get(b.playId)) }
  })
  server.route('DELETE', '/api/playback/:session', () => undefined)
  server.route('GET', '/api/files/:id/subtitles/search', () => ({
    available: true, canDownload: true, languages: ['en', 'es'], provider: 'opensubtitles', attribution: 'Subtitles provided by OpenSubtitles.com',
    quota: { remaining: 17, signedIn: true },
    results: [
      { id: 'os-en', language: 'en', release: 'WEB-DL', hashMatch: true, hearingImpaired: false, forced: false, aiTranslated: false, downloads: 5310, trusted: true },
      { id: 'os-es', language: 'es', release: '1080p.BluRay', hashMatch: false, hearingImpaired: true, forced: false, aiTranslated: false, downloads: 1204, trusted: true },
      { id: 'os-pt', language: 'pt-BR', release: 'WEB-DL', hashMatch: false, hearingImpaired: false, forced: false, aiTranslated: true, downloads: 88, trusted: false },
    ],
  }))
  server.route('POST', '/api/files/:id/subtitles/download', async ({ params, body }) => {
    const id = Number(params.id)
    const resultId = (body as { resultId: string }).resultId
    await new Promise((resolve) => setTimeout(resolve, 1500))
    const track: SubtitleTrack = { id: `os-${resultId}`, codec: 'subrip', format: 'webvtt', burnInOnly: false, default: false, external: true, forced: false,
      source: 'opensubtitles', language: resultId === 'os-es' ? 'es' : resultId === 'os-pt' ? 'pt' : 'en', url: `/api/files/${id}/subtitles/os-${resultId}.vtt` }
    downloaded.set(id, [...(downloaded.get(id) ?? []).filter((t) => t.id !== track.id), track])
    return track
  })
  server.route('POST', '/api/files/:id/subtitles/automatic', () => ({}))
  server.route('GET', '/api/files/:id/subtitles/:name', ({ params }) => new Response(vtt(params.name.replace(/\.vtt$/, ''), fileOf(Number(params.id)).durationMs), { headers: { 'Content-Type': 'text/vtt' } }))
  // A file's place in the library, for the Episodes panel.
  server.route('GET', '/api/files/:id', ({ params }) => {
    const f = fileOf(Number(params.id))
    return { id: f.id, libraryId: f.episode ? 2 : 1, path: `/media/${f.id}.mkv`, relativePath: `${f.id}.mkv`, size: 2e9, status: 'present', matchStatus: 'matched',
      matchIgnored: false, matchLocked: false, addedAt: at(0), firstSeenAt: at(0), lastSeenAt: at(0), modifiedAt: at(0), durationMs: f.durationMs, parsed: { kind: f.episode ? 'episode' : 'movie', title: f.episode?.showTitle ?? '' },
      movieId: f.movieId, showId: f.episode?.showId, seasonNumber: f.episode?.season, episodeNumber: f.episode?.number, episodeId: f.episode?.episodeId }
  })
  // The show and its seasons, made to match the files above. The details screens' fakes may answer these instead.
  server.route('GET', '/api/shows/:id', ({ params }) => {
    const show = SHOW_ITEMS.find((s) => s.id === Number(params.id))
    if (!show) return problem(404, 'no such show')
    const seasons = Array.from({ length: (SEASONS[show.id] ?? 3) + 1 }, (_, n) => ({ id: show.id * 10 + n, showId: show.id, number: n, name: n ? `Season ${n}` : 'Specials',
      overview: '', episodeCount: n ? EPISODES_A_SEASON : 2, availableCount: n ? EPISODES_A_SEASON : 2, watchedCount: 0 }))
    return { ...show, credits: [], genres: show.genres ?? [], fileCount: 32, episodeCount: 32, totalEpisodes: 32, totalSize: 3e10, hdr: false, tmdbId: show.id, libraryId: 2, overview: show.overview ?? '', seasons }
  })
  server.route('GET', '/api/shows/:id/seasons/:number', ({ params }) => {
    const show = SHOW_ITEMS.find((s) => s.id === Number(params.id))
    const number = Number(params.number)
    if (!show) return problem(404, 'no such show')
    const playing = CONTINUE.find((c) => c.showId === show.id)
    const episodes: Episode[] = Array.from({ length: number ? EPISODES_A_SEASON : 2 }, (_, i) => {
      const f = fileOf(episodeFileId(show.id, number, i + 1))
      const e = f.episode!
      const before = !!playing && (number < (playing.seasonNumber ?? 0) || (number === playing.seasonNumber && e.number < (playing.episodeNumber ?? 0)))
      return { id: e.episodeId, seasonId: show.id * 10 + number, number: e.number, title: e.title, overview: '', aired: true, isNew: false, hasFile: true, runtimeMin: f.durationMs / MIN,
        files: [{ id: f.id, libraryId: 2, path: '', relativePath: '', size: 2e9, status: 'present', matchStatus: 'matched', matchIgnored: false, matchLocked: false,
          addedAt: at(0), firstSeenAt: at(0), lastSeenAt: at(0), modifiedAt: at(0), durationMs: f.durationMs, parsed: { kind: 'episode', title: show.title } }],
        watch: before ? { completed: true, positionMs: f.durationMs, durationMs: f.durationMs, updatedAt: at(0) } : f.resumeMs ? { completed: false, positionMs: f.resumeMs, durationMs: f.durationMs, fileId: f.id, updatedAt: at(0) } : undefined }
    })
    return { id: show.id * 10 + number, showId: show.id, number, name: number ? `Season ${number}` : 'Specials', overview: '', episodeCount: episodes.length, availableCount: episodes.length, watchedCount: 0,
      episodes, show: { id: show.id, title: show.title, credits: [], genres: [], episodeCount: 32, libraryId: 2, overview: '', tmdbId: show.id } }
  })
  // Recordings: Harbor Evening News still recording, Inspector Vane recorded with two ad breaks.
  server.route('GET', '/api/live-tv/recordings/:id', ({ params }) => RECORDINGS[params.id]?.recording ?? problem(404, 'no such recording'))
  server.route('POST', '/api/live-tv/recordings/:id/playback', ({ params, body }) => {
    // The Live TV fakes' own recordings (one still recording when every tuner is busy, Recordings' rows)
    // play as the one still recording here, so watching one from its start can be tried in the preview.
    const r = RECORDINGS[params.id] ?? RECORDINGS['rec-news']
    return decide(`recording:${params.id}`, r.durationMs, body as DecisionRequest | undefined, { growing: r.growing, segments: r.segments, url: `/api/files/${r.fileId}/stream` })
  })
  server.route('PUT', '/api/live-tv/recordings/:id/progress', ({ body }) => {
    const b = body as { playId: string; positionMs: number; durationMs: number }
    return { completed: false, positionMs: b.positionMs, durationMs: b.durationMs, updatedAt: at(Date.now()), playId: b.playId }
  })
}
