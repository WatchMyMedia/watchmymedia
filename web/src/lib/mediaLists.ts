import type { components } from '../api/schema'
import { filterOptions, itemFilter, itemSort, sortOptions, type ItemFilter, type ItemSort } from './browseOptions'
import { formatDate, relativeTime } from './format'
import { screenLibraryId, webLink } from './screenRows'

// Make a list (PLAN-ROWS.md §5, §6.3): what the builder's options become
// in the API, what a list's page says about where it comes from, and the
// state of each title on it.

export type MediaList = components['schemas']['MediaList']
export type MediaListSource = components['schemas']['MediaListSource']
export type MediaListEntry = components['schemas']['MediaListEntry']
export type MediaListGenre = components['schemas']['MediaListGenre']
type UpdateMediaList = components['schemas']['UpdateMediaListInputBody']
type PreviewBody = components['schemas']['PreviewMediaListInputBody']
type LibraryRef = { id: number; type: string; name?: string }

export const MAX_IMPORT_BYTES = 1 << 20
export const MAX_ENTRIES = 250
export const DEFAULT_ENTRIES = 50

/** The kinds the web can make lists from. A kind the server adds later is not offered. */
export type ListKind = 'tmdb' | 'mdblist' | 'library' | 'import'
export const listKinds: readonly ListKind[] = ['tmdb', 'mdblist', 'library', 'import']
export function knownKind(kind: string): kind is ListKind {
  return (listKinds as readonly string[]).includes(kind)
}

export type MediaType = 'movie' | 'tv' | 'all'

/** The builder's options as typed, before they become a MediaListSource. */
export type SourceDraft = {
  kind: ListKind
  mediaType: MediaType
  chart: string
  ref: string
  genres: number[]
  yearFrom: string
  yearTo: string
  minRating: string
  minVotes: string
  language: string
  sortBy: string
  libraryId: number
  sort: ItemSort
  filter: ItemFilter
  genre: string
  decade: number
  text: string
  fileName: string
}

export function emptyDraft(kind: ListKind, over: Partial<SourceDraft> = {}): SourceDraft {
  return {
    kind, mediaType: kind === 'tmdb' ? 'movie' : 'all', chart: 'popular', ref: '', genres: [], yearFrom: '', yearTo: '', minRating: '', minVotes: '', language: '', sortBy: 'popularity',
    libraryId: 0, sort: 'added', filter: '', genre: '', decade: 0, text: '', fileName: '', ...over,
  }
}

/** The media types a kind lets the person choose; a library list takes its library's. */
export function mediaTypeChoices(kind: ListKind): MediaType[] {
  if (kind === 'tmdb') return ['movie', 'tv']
  if (kind === 'library') return []
  return ['all', 'movie', 'tv']
}

export function mediaTypeLabel(t: MediaType): string {
  return t === 'movie' ? 'Movies' : t === 'tv' ? 'TV shows' : 'Both'
}

/** The media type the list is made with. */
export function listMediaType(d: SourceDraft, libraries: readonly LibraryRef[]): MediaType {
  if (d.kind === 'tmdb') return d.mediaType === 'tv' ? 'tv' : 'movie'
  if (d.kind === 'library') return libraries.find((l) => l.id === d.libraryId)?.type === 'tv' ? 'tv' : 'movie'
  return d.mediaType
}

const movieOnly = new Set(['now-playing', 'upcoming', 'collection'])
const tvOnly = new Set(['on-the-air', 'airing-today'])
const defaultCharts = ['trending-day', 'trending-week', 'popular', 'top-rated', 'now-playing', 'upcoming', 'on-the-air', 'airing-today', 'discover', 'list', 'collection']

/** TMDB's charts the server offers that fit the media type, in the server's order. */
export function chartsFor(charts: readonly string[] | undefined, mediaType: MediaType): string[] {
  return (charts?.length ? charts : defaultCharts).filter((c) => (mediaType === 'tv' ? !movieOnly.has(c) : !tvOnly.has(c)))
}

const chartLabels: Record<string, string> = {
  'trending-day': 'Trending today',
  'trending-week': 'Trending this week',
  popular: 'Popular',
  'top-rated': 'Top rated',
  'now-playing': 'Now playing',
  upcoming: 'Upcoming',
  'on-the-air': 'On the air',
  'airing-today': 'Airing today',
  discover: 'Titles that match what you choose',
  list: 'A TMDB list',
  collection: 'A TMDB collection',
}

const chartLines: Record<string, string> = {
  'trending-day': 'What people watch on TMDB today',
  'trending-week': 'The same, over the week',
  popular: 'TMDB’s popularity, updated daily',
  'top-rated': 'Highest rated, all time',
  'now-playing': 'In cinemas now',
  upcoming: 'Coming to cinemas',
  'on-the-air': 'Airing in the next week',
  'airing-today': 'Episodes airing today',
}

/** A chart's line under its name in Make a list. */
export function chartLine(chart: string): string | undefined {
  return chartLines[chart]
}

/** How the builder's TMDB options start: a chart, discover, or a TMDB list or collection. */
export type TMDBMode = 'chart' | 'discover' | 'ref'
export function tmdbMode(chart: string): TMDBMode {
  return chart === 'discover' ? 'discover' : chart === 'list' || chart === 'collection' ? 'ref' : 'chart'
}

/** A chart's name in the builder; one the web doesn't know reads as its words. */
export function chartLabel(chart: string): string {
  const known = chartLabels[chart]
  if (known) return known
  const words = chart.replace(/[-_]+/g, ' ').trim()
  return words ? words[0].toUpperCase() + words.slice(1) : chart
}

export const discoverSorts = [
  { value: 'popularity', label: 'Most popular' },
  { value: 'rating', label: 'Highest rated' },
  { value: 'votes', label: 'Most votes' },
  { value: 'newest', label: 'Newest' },
] as const

export const ratingChoices = ['', '5', '6', '7', '7.5', '8', '8.5'] as const
export const discoverLanguages = ['en', 'fr', 'es', 'de', 'it', 'ja', 'ko', 'zh', 'hi', 'pt', 'ru', 'sv', 'da', 'no', 'nl', 'pl', 'tr', 'ar', 'th', 'fa', 'he'] as const

const whole = (s: string): number | undefined => {
  const t = s.trim()
  if (!t) return undefined
  const n = Number(t)
  return Number.isInteger(n) && n >= 0 ? n : undefined
}
const decimal = (s: string): number | undefined => {
  const t = s.trim()
  if (!t) return undefined
  const n = Number(t)
  return Number.isFinite(n) && n >= 0 ? n : undefined
}

/** Drops empty options, so a source compares and hashes the same however it was built. */
function compact(s: MediaListSource): MediaListSource {
  const out: Record<string, unknown> = {}
  for (const [k, v] of Object.entries(s)) {
    if (v === undefined || v === null || v === '' || v === 0 || (Array.isArray(v) && v.length === 0)) continue
    out[k] = v
  }
  return out as MediaListSource
}

/** The source the API takes: only the options the kind (and TMDB's chart) reads. */
export function sourceOf(d: SourceDraft): MediaListSource {
  switch (d.kind) {
    case 'tmdb':
      if (d.chart === 'discover')
        return compact({
          kind: 'tmdb', chart: 'discover', genres: [...d.genres].sort((a, b) => a - b), yearFrom: whole(d.yearFrom), yearTo: whole(d.yearTo),
          minRating: decimal(d.minRating), minVotes: whole(d.minVotes), language: d.language || undefined, sortBy: d.sortBy || undefined,
        })
      if (d.chart === 'list' || d.chart === 'collection') return compact({ kind: 'tmdb', chart: d.chart, ref: d.ref.trim() })
      return compact({ kind: 'tmdb', chart: d.chart })
    case 'mdblist':
      return compact({ kind: 'mdblist', ref: d.ref.trim() })
    case 'library':
      return compact({ kind: 'library', libraryId: d.libraryId, sort: d.sort, filter: d.filter, genre: d.genre, decade: d.decade })
    case 'import':
      return { kind: 'import', text: d.text }
  }
}

/** UTF-8 bytes, as the server counts the 1 MB an import may be. */
export function byteLength(text: string): number {
  return new TextEncoder().encode(text).length
}

/** What the options still need before the list can be previewed or made; undefined when nothing. */
export function draftProblem(d: SourceDraft): string | undefined {
  switch (d.kind) {
    case 'tmdb': {
      if (!d.chart) return 'Choose what the list is.'
      if (d.chart === 'list' && !d.ref.trim()) return 'Enter the TMDB list’s link or ID.'
      if (d.chart === 'collection' && !d.ref.trim()) return 'Enter the TMDB collection’s link or ID.'
      if (d.chart !== 'discover') return undefined
      const from = whole(d.yearFrom)
      const to = whole(d.yearTo)
      const bad = (s: string, n: number | undefined) => s.trim() !== '' && (n === undefined || n < 1870 || n > 3000)
      if (bad(d.yearFrom, from) || bad(d.yearTo, to)) return 'Enter years as four digits, such as 1985.'
      if (from && to && from > to) return 'The first year is after the last.'
      if (d.minVotes.trim() && whole(d.minVotes) === undefined) return 'Enter the number of votes as a whole number.'
      return undefined
    }
    case 'mdblist':
      return d.ref.trim() ? undefined : 'Enter the list’s link, its ID, or user/name.'
    case 'library':
      return d.libraryId ? undefined : 'Choose a library.'
    case 'import':
      if (!d.text.trim()) return 'Paste a list or choose a file.'
      return byteLength(d.text) > MAX_IMPORT_BYTES ? 'That’s more than 1 MB. Split it into smaller lists.' : undefined
  }
}

/** The entry limit as typed: a whole number from 1 to 250, else undefined. */
export function entryLimit(s: string): number | undefined {
  const n = whole(s)
  return n !== undefined && n >= 1 && n <= MAX_ENTRIES ? n : undefined
}

/** A short hash of an import's text, so the preview's cache key isn't the whole file. */
export function hashText(text: string): string {
  let h = 0x811c9dc5
  for (let i = 0; i < text.length; i++) {
    h ^= text.charCodeAt(i)
    h = Math.imul(h, 0x01000193)
  }
  return `${(h >>> 0).toString(16)}:${text.length}`
}

/** The preview's cache key: the body, with an import's text as its hash. */
export function previewKey(body: PreviewBody): string {
  const { text, ...rest } = body.source
  return JSON.stringify({ ...body, source: compact(rest as MediaListSource), text: text ? hashText(text) : undefined })
}

/** Whether two sources fetch the same list (an import's text and format aside). */
export function sameSource(a: MediaListSource, b: MediaListSource): boolean {
  const key = (s: MediaListSource) => {
    const c = compact({ ...s, text: undefined, format: undefined, genres: s.genres ? [...s.genres].sort((x, y) => x - y) : undefined }) as Record<string, unknown>
    return JSON.stringify(Object.keys(c).sort().map((k) => [k, c[k]]))
  }
  return key(a) === key(b)
}

/** The builder's options for a list being edited. */
export function draftFromList(list: Pick<MediaList, 'source' | 'mediaType'>): SourceDraft {
  const s = list.source
  const kind = knownKind(s.kind) ? s.kind : 'tmdb'
  const mediaType: MediaType = list.mediaType === 'movie' || list.mediaType === 'tv' ? list.mediaType : 'all'
  const text = (n: number | undefined) => (n ? String(n) : '')
  return emptyDraft(kind, {
    mediaType, chart: s.chart ?? (kind === 'tmdb' ? 'popular' : ''), ref: s.ref ?? '', genres: s.genres ?? [], yearFrom: text(s.yearFrom), yearTo: text(s.yearTo),
    minRating: text(s.minRating), minVotes: text(s.minVotes), language: s.language ?? '', sortBy: s.sortBy ?? 'popularity',
    libraryId: s.libraryId ?? 0, sort: itemSort(s.sort) ?? 'added', filter: itemFilter(s.filter) ?? '', genre: s.genre ?? '', decade: s.decade ?? 0,
  })
}

export type ListDetails = { name: string; limit: number; shared: boolean }

/** What an edit changes, for updateMediaList; undefined when nothing does. An import's titles can't be changed. */
export function listPatch(list: MediaList, d: SourceDraft, details: ListDetails, admin: boolean): UpdateMediaList | undefined {
  const patch: UpdateMediaList = {}
  if (details.name.trim() && details.name.trim() !== list.name) patch.name = details.name.trim()
  if (details.limit !== list.limit) patch.limit = details.limit
  if (admin && details.shared !== (list.owner === 'everyone')) patch.shared = details.shared
  if (list.source.kind !== 'import' && knownKind(list.source.kind)) {
    const source = sourceOf(d)
    if (!sameSource(source, list.source)) patch.source = source
  }
  return Object.keys(patch).length ? patch : undefined
}

const capital = (s: string) => (s ? s[0].toUpperCase() + s.slice(1) : s)
const media = (t: MediaType) => (t === 'tv' ? 'TV shows' : t === 'movie' ? 'movies' : 'titles')

/** A name for the list from its options, until the person types their own. */
export function suggestName(d: SourceDraft, o: { genres?: readonly MediaListGenre[]; libraries?: readonly LibraryRef[]; languageName?: (code: string) => string } = {}): string {
  const libraries = o.libraries ?? []
  const type = listMediaType(d, libraries)
  const m = media(type)
  let name = ''
  switch (d.kind) {
    case 'tmdb':
      switch (d.chart) {
        case 'trending-day': name = `Trending ${m} today`; break
        case 'trending-week': name = `Trending ${m} this week`; break
        case 'popular': name = `Popular ${m}`; break
        case 'top-rated': name = `Top rated ${m}`; break
        case 'now-playing': name = 'Now playing in theaters'; break
        case 'upcoming': name = 'Upcoming movies'; break
        case 'on-the-air': name = 'On the air this week'; break
        case 'airing-today': name = 'Airing today'; break
        case 'list': name = 'TMDB list'; break
        case 'collection': name = 'TMDB collection'; break
        case 'discover': {
          const genres = d.genres.map((id) => o.genres?.find((g) => g.id === id)?.name).filter(Boolean).join(' & ')
          const from = whole(d.yearFrom)
          const to = whole(d.yearTo)
          const years = from && to ? (from === to ? `${from}` : `${from}–${to}`) : from ? `since ${from}` : to ? `up to ${to}` : ''
          const language = d.language ? `in ${o.languageName ? o.languageName(d.language) : d.language}` : ''
          name = [genres ? `${genres} ${m}` : capital(m), [language, years].filter(Boolean).join(', ')].filter(Boolean).join(', ')
          break
        }
        default: name = `${chartLabel(d.chart)} ${m}`
      }
      break
    case 'mdblist': {
      const slug = /^(?:https?:\/\/[^/]+\/lists\/)?[\w.-]+\/([\w.-]+)\/?$/.exec(d.ref.trim())?.[1]
      name = slug ? slug.replace(/[-_]+/g, ' ') : 'MDBList list'
      break
    }
    case 'library': {
      const filter = filterOptions(type === 'tv' ? 'tv' : 'movie').find((f) => f.value === d.filter && f.value !== '')?.label
      const what = [filter, d.genre, m].filter(Boolean).join(' ')
      const decade = d.decade ? ` from the ${d.decade}s` : ''
      if (filter || d.genre || d.decade) name = `${what}${decade}`
      else name = `${capital(m)} by ${(sortOptions(type === 'tv' ? 'tv' : 'movie').find((s) => s.value === d.sort)?.label ?? 'title').toLowerCase()}`
      break
    }
    case 'import':
      name = d.fileName.replace(/\.[^.]+$/, '').trim() || 'Imported list'
      break
  }
  return capital(name).slice(0, 80)
}

/** "From TMDB", "From your library": where a list comes from, in the list page's header. */
export function sourceName(s: Pick<MediaListSource, 'kind' | 'format'>): string {
  switch (s.kind) {
    case 'tmdb': return 'TMDB'
    case 'mdblist': return 'MDBList'
    case 'library': return 'your library'
    case 'import':
      return s.format === 'imdb' ? 'an IMDb list export' : s.format === 'letterboxd' ? 'a Letterboxd export' : s.format === 'lines' ? 'a pasted list' : 'an import'
    default: return s.kind
  }
}

/** What an import was read as, in the builder's preview. */
export function importFormatLabel(format: string | undefined): string | undefined {
  if (format === 'imdb') return 'an IMDb list export'
  if (format === 'letterboxd') return 'a Letterboxd export'
  if (format === 'lines') return 'one title a line'
  return undefined
}

const onHost = (url: string, host: string) => {
  const h = new URL(url).hostname
  return h === host || h.endsWith(`.${host}`)
}

const chartPages: Record<string, { movie?: string; tv?: string }> = {
  popular: { movie: 'movie', tv: 'tv' },
  'top-rated': { movie: 'movie/top-rated', tv: 'tv/top-rated' },
  'now-playing': { movie: 'movie/now-playing' },
  upcoming: { movie: 'movie/upcoming' },
  'on-the-air': { tv: 'tv/on-the-air' },
  'airing-today': { tv: 'tv/airing-today' },
}

/** The list on its source's site, when it has a page there. Links point only at TMDB and MDBList. */
export function sourceLink(s: MediaListSource, mediaType: string): string | undefined {
  const ref = s.ref?.trim() ?? ''
  if (s.kind === 'tmdb') {
    if (s.chart === 'list' || s.chart === 'collection') {
      const url = webLink(ref)
      if (url) return onHost(url, 'themoviedb.org') ? url : undefined
      return /^\d+$/.test(ref) ? `https://www.themoviedb.org/${s.chart}/${ref}` : undefined
    }
    const page = chartPages[s.chart ?? '']?.[mediaType === 'tv' ? 'tv' : 'movie']
    return page ? `https://www.themoviedb.org/${page}` : undefined
  }
  if (s.kind === 'mdblist' && ref) {
    const url = webLink(ref)
    if (url) return onHost(url, 'mdblist.com') ? url : undefined
    const m = /^([\w.-]+)\/([\w.-]+)$/.exec(ref)
    return m ? `https://mdblist.com/lists/${m[1]}/${m[2]}` : undefined
  }
  return undefined
}

/** The list page's line on fetching: when it last worked, and when it failed and why. */
export function refreshWords(list: Pick<MediaList, 'source' | 'fetchedAt' | 'nextFetchAt' | 'error' | 'errorAt' | 'fetching' | 'createdAt'>): { fetched?: string; error?: string } {
  const kind = list.source.kind
  let fetched: string | undefined
  if (list.fetching) fetched = 'Fetching now…'
  else if (kind === 'library') fetched = 'Follows your library as it changes'
  else if (kind === 'import') fetched = `Imported ${relativeTime(list.createdAt)}`
  else if (list.fetchedAt) fetched = `Updated ${relativeTime(list.fetchedAt)}`
  else fetched = 'Not fetched yet'
  const error = list.error ? `Couldn’t update${list.errorAt ? ` ${relativeTime(list.errorAt)}` : ''}: ${list.error}` : undefined
  return { fetched, error }
}

/** Whether the list can be fetched again now: TMDB and MDBList lists, not while a fetch runs. */
export function canRefresh(list: Pick<MediaList, 'source' | 'fetching' | 'canEdit'>): boolean {
  return list.canEdit && (list.source.kind === 'tmdb' || list.source.kind === 'mdblist') && !list.fetching
}

/** Whether the web may edit the list: the person may, and it knows the source's kind. */
export function canEditList(list: Pick<MediaList, 'source' | 'canEdit'>): boolean {
  return list.canEdit && knownKind(list.source.kind)
}

export type EntryView =
  | { kind: 'library' }
  | { kind: 'notFound' }
  | { kind: 'missing'; request: boolean; label: string }

const requestLabels: Record<string, string> = { requested: 'Requested', downloading: 'Downloading', available: 'Available' }

/**
 * A title on a list's page: the library's card when it has it; dimmed with
 * Request when the person may ask for it, or where its request stands;
 * Not found for an import TMDB couldn't find.
 */
export function entryView(e: MediaListEntry): EntryView {
  if (e.item) return { kind: 'library' }
  if (!e.found) return { kind: 'notFound' }
  if (e.requestState === 'requestable') {
    const can = !!e.tmdbId && (e.mediaType === 'movie' || e.mediaType === 'tv')
    return { kind: 'missing', request: can, label: can ? 'Request' : 'Not in your library' }
  }
  return { kind: 'missing', request: false, label: (e.requestState && requestLabels[e.requestState]) || 'Not in your library' }
}

/** "23 of 50 in your library". */
export function inLibraryWords(inLibrary: number, total: number): string {
  return `${inLibrary.toLocaleString()} of ${total.toLocaleString()} in your library`
}

/** Where a screen is, for the list page: "Home", or the library's name. */
export function screenName(screen: string, libraries: readonly LibraryRef[]): string {
  const id = screenLibraryId(screen)
  if (id === undefined) return 'Home'
  const lib = libraries.find((l) => l.id === id)
  return lib?.name || (lib?.type === 'tv' ? 'TV Shows' : 'Movies')
}

/** "From TMDB", "From your library", "Imported": a list's source in the editor and Add a row. */
export function sourceWords(s: Pick<MediaListSource, 'kind'>): string {
  switch (s.kind) {
    case 'tmdb': return 'From TMDB'
    case 'mdblist': return 'From MDBList'
    case 'library': return 'From your library'
    case 'import': return 'Imported'
    default: return `From ${s.kind}`
  }
}

/** "18 of 50", or "18 of 50 in your library"; a library list holds only what the library has: "26 titles". */
export function holdsWords(l: Pick<MediaList, 'source' | 'entries' | 'inLibrary'>, long = false): string {
  if (l.source.kind === 'library') return `${l.entries.toLocaleString()} ${l.entries === 1 ? 'title' : 'titles'}`
  return `${l.inLibrary.toLocaleString()} of ${l.entries.toLocaleString()}${long ? ' in your library' : ''}`
}

/** "3 titles weren’t found on TMDB". */
export function notFoundWords(n: number): string {
  return n === 1 ? '1 title wasn’t found on TMDB' : `${n.toLocaleString()} titles weren’t found on TMDB`
}

type EntryRef = { id: string; kind: string; owner?: string; style: string; hidden: boolean }

/**
 * The line under a row in Edit rows: "Built-in", or a list's owner, where
 * it was made when that is another screen, how much the library has and
 * where it comes from ("Your list · made on Movies · 9 of 50 · From TMDB").
 */
export function editorLine(r: EntryRef, list: MediaList | undefined, screen: string, libraries: readonly LibraryRef[]): string {
  if (r.kind !== 'list') return r.style === 'live' ? 'Built-in · drawn in the TV and phone apps, not on the web yet' : 'Built-in'
  const owner = r.owner === 'everyone' ? 'For everyone' : r.owner === 'me' ? 'Your list' : 'List'
  if (!list) return owner
  const made = list.screen !== screen ? `made on ${screenName(list.screen, libraries)}` : ''
  return [owner, made, holdsWords(list), sourceWords(list.source)].filter(Boolean).join(' · ')
}

/** The line under a row in Add a row ("Made on Movies · From TMDB · 9 of 50 in your library"). */
export function catalogueLine(r: EntryRef, list: MediaList | undefined, screen: string, libraries: readonly LibraryRef[]): string {
  if (r.kind !== 'list') return r.style === 'live' ? 'Built-in · drawn in the TV and phone apps' : 'Built-in · hidden by you'
  if (!list) return ''
  const made = list.screen !== screen ? `Made on ${screenName(list.screen, libraries)}` : ''
  const chart = list.source.kind === 'tmdb' && list.source.chart && !['discover', 'list', 'collection'].includes(list.source.chart) ? chartLabel(list.source.chart).toLowerCase() : ''
  return [made, sourceWords(list.source), chart, holdsWords(list, true)].filter(Boolean).join(' · ')
}

const sortWords: Record<string, string> = { popularity: 'most popular first', rating: 'highest rated first', votes: 'most votes first', newest: 'newest first' }

/**
 * The preview's line on what the list is: "From TMDB · Popular · movies",
 * "From TMDB · Science Fiction · 1980–1989 · rated 7+ · most votes first",
 * "From your library · Movies · unwatched · Comedy · 1990s · by rating",
 * "Imported · one title a line · 3 not found".
 */
export function previewFromLine(d: SourceDraft, o: { genres?: readonly MediaListGenre[]; libraries?: readonly LibraryRef[]; format?: string; notFound?: number; languageName?: (code: string) => string } = {}): string {
  const libraries = o.libraries ?? []
  const type = listMediaType(d, libraries)
  switch (d.kind) {
    case 'tmdb': {
      if (d.chart === 'discover') {
        const genres = d.genres.map((id) => o.genres?.find((g) => g.id === id)?.name).filter(Boolean).join(' & ')
        const from = whole(d.yearFrom)
        const to = whole(d.yearTo)
        const years = from && to ? (from === to ? `${from}` : `${from}–${to}`) : from ? `since ${from}` : to ? `up to ${to}` : ''
        const rating = decimal(d.minRating) ? `rated ${decimal(d.minRating)}+` : ''
        const votes = whole(d.minVotes) ? `${whole(d.minVotes)?.toLocaleString()}+ votes` : ''
        const language = d.language ? (o.languageName ? o.languageName(d.language) : d.language) : ''
        return ['From TMDB', genres || media(type), years, rating, votes, language, sortWords[d.sortBy] ?? ''].filter(Boolean).join(' · ')
      }
      if (d.chart === 'list' || d.chart === 'collection') return ['From TMDB', d.chart === 'list' ? 'a TMDB list' : 'a collection'].join(' · ')
      return ['From TMDB', chartLabel(d.chart), media(type)].join(' · ')
    }
    case 'mdblist':
      return ['From MDBList', d.ref.trim()].filter(Boolean).join(' · ')
    case 'library': {
      const lib = libraries.find((l) => l.id === d.libraryId)
      const t = type === 'tv' ? 'tv' : 'movie'
      const filter = filterOptions(t).find((f) => f.value === d.filter && f.value !== '')?.label
      const sort = sortOptions(t).find((x) => x.value === d.sort)?.label
      return ['From your library', lib?.name, filter && (filter === '4K' || filter === 'HDR' ? filter : filter.toLowerCase()), d.genre, d.decade ? `${d.decade}s` : '', sort && `by ${sort.toLowerCase()}`]
        .filter(Boolean)
        .join(' · ')
    }
    case 'import':
      return ['Imported', importFormatLabel(o.format), o.notFound ? `${o.notFound.toLocaleString()} not found` : ''].filter(Boolean).join(' · ')
  }
}

/** The preview's headline: "23 of 50 in your library"; a library list holds only what the library has. */
export function previewHaveWords(kind: ListKind, inLibrary: number, total: number): string {
  if (kind === 'library') return `${total.toLocaleString()} ${total === 1 ? 'title' : 'titles'}, all in your library`
  return inLibraryWords(inLibrary, total)
}

/** Under the preview's first 20: "and 30 more." */
export function previewRestWords(shown: number, total: number, kind: ListKind): string {
  const rest = total - shown
  if (rest <= 0) return ''
  return kind === 'library' ? `and ${rest.toLocaleString()} more.` : `and ${rest.toLocaleString()} more. The list’s page shows every title, with Request for the missing ones.`
}

/** "Retries in 6 hours", from when the server will fetch it again. */
export function retryWords(nextFetchAt: string | undefined, now = Date.now()): string {
  if (!nextFetchAt) return ''
  const min = Math.round((new Date(nextFetchAt).getTime() - now) / 60_000)
  if (min <= 1) return 'It tries again soon.'
  if (min < 60) return `It tries again in ${min} minutes.`
  const h = Math.round(min / 60)
  if (h < 36) return `It tries again in ${h} ${h === 1 ? 'hour' : 'hours'}.`
  return `It tries again in ${Math.round(h / 24)} days.`
}

/** "From TMDB · discover", "From your library · Movies": a household list's source in Settings › Lists. */
export function listFromLine(l: Pick<MediaList, 'source'>, libraries: readonly LibraryRef[]): string {
  const s = l.source
  if (s.kind === 'tmdb' && s.chart) return `From TMDB · ${s.chart === 'discover' ? 'discover' : chartLabel(s.chart).toLowerCase()}`
  if (s.kind === 'library') return ['From your library', libraries.find((x) => x.id === s.libraryId)?.name].filter(Boolean).join(' · ')
  return sourceWords(s)
}

/**
 * The list page's line under its name: where it comes from (linked when
 * the source has a page), when it was last updated, and what it holds.
 */
export function listMeta(l: Pick<MediaList, 'source' | 'mediaType' | 'fetchedAt' | 'fetching' | 'createdAt'>): { from: string; href?: string; updated: string; type: string } {
  const type = l.mediaType === 'movie' ? 'Movies' : l.mediaType === 'tv' ? 'TV shows' : 'Movies and TV shows'
  if (l.source.kind === 'import') {
    const read = importFormatLabel(l.source.format)
    return { from: `Imported ${formatDate(l.createdAt)}`, updated: read ? capital(read) : 'Read once', type }
  }
  if (l.source.kind === 'library') return { from: 'From your library', updated: 'Follows the library', type }
  const updated = l.fetching ? 'Fetching now…' : l.fetchedAt ? `Updated ${relativeTime(l.fetchedAt)}` : 'Not fetched yet'
  return { from: sourceWords(l.source), href: sourceLink(l.source, l.mediaType), updated, type }
}

/** "50 titles in the list’s order · 18 in your library · 29 missing · 3 not found". */
export function listCountWords(total: number, inLibrary: number, notFound = 0): string {
  const missing = Math.max(0, total - inLibrary - notFound)
  return [
    `${total.toLocaleString()} ${total === 1 ? 'title' : 'titles'} in the list’s order`,
    `${inLibrary.toLocaleString()} in your library`,
    missing ? `${missing.toLocaleString()} missing` : '',
    notFound ? `${notFound.toLocaleString()} not found` : '',
  ]
    .filter(Boolean)
    .join(' · ')
}

/** A server's reason as a sentence, so words after it don't run on: "An admin can add a key." */
export function sentence(text: string | undefined): string {
  const t = (text ?? '').trim()
  return !t || /[.!?…]$/.test(t) ? t : `${t}.`
}
