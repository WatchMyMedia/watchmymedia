import type { components } from '../../../src/api/schema'
import { formatRuntime, plural } from '../../../src/lib/format'
import { duration, episodeLine, isOn } from '../../../src/lib/liveGuide'
import { searchWhen } from '../../../src/lib/liveReminders'
import { homeRows } from '../../../src/lib/screenRows'
import { leftWords } from '../../ui/timeWords'
import type { IconName } from '../../ui/Icon'

// Search's results on the TV: the best match with your place in it, each
// group's cards and their lines, On live TV's times and what OK does there,
// and Not in your library, set apart at the end.

type SearchResults = components['schemas']['SearchResults']
type Movie = components['schemas']['Movie']
type Show = components['schemas']['Show']
type SearchEpisode = components['schemas']['SearchEpisode']
type Requestable = components['schemas']['Requestable']
type LiveHit = components['schemas']['LiveTVSearchHit']
type HomeData = components['schemas']['HomeData']
type ContinueItem = components['schemas']['ContinueItem']

/** Names in a sentence: "Cabin server", "Cabin server and Den server", "A, B and C". */
function named(names: readonly string[]): string {
  return names.length <= 1 ? names[0] ?? '' : `${names.slice(0, -1).join(', ')} and ${names[names.length - 1]}`
}

/** Before anything is typed: what Search looks in. With two or more servers, it says it looks in all of them. */
export function emptyWords(server: string, servers: number, liveTV: boolean): { title: string; body: string } {
  const where = servers > 1 ? `your ${servers} servers` : server
  return { title: 'Search', body: `Movies, shows and episodes on ${where}${liveTV ? ', and what is on live TV' : ''}.` }
}

/** Nothing found, once every server asked has answered: `answered` counts those that did; the second line says live TV had nothing either, when it was asked. */
export function noMatchWords(q: string, answered: number, liveTV: boolean): { title: string; body?: string } {
  const title = answered > 1 ? `Nothing on your ${answered} servers matches “${q}”` : `Nothing in your library matches “${q}”`
  return { title, body: liveTV ? 'Nothing on live TV either.' : undefined }
}

/** The library's best match for what was typed: the same title, then one starting with it, then one with a word starting with it, then the first found; shows before movies on a tie. Only this server's titles are a best match. */
export function bestMatch<S extends { id: number; title: string }, M extends { id: number; title: string }>(
  shows: readonly { item: S; server?: unknown }[], movies: readonly { item: M; server?: unknown }[], q: string,
): { kind: 'show'; item: S } | { kind: 'movie'; item: M } | undefined {
  const typed = q.trim().toLowerCase()
  const rank = (title: string) => {
    const t = title.toLowerCase()
    if (t === typed) return 0
    if (t.startsWith(typed)) return 1
    if (t.split(/[^\p{L}\p{N}]+/u).some((w) => w.startsWith(typed))) return 2
    return 3
  }
  let best: { kind: 'show'; item: S } | { kind: 'movie'; item: M } | undefined
  let score = Infinity
  for (const t of shows) if (!t.server && rank(t.item.title) < score) { best = { kind: 'show', item: t.item }; score = rank(t.item.title) }
  for (const t of movies) if (!t.server && rank(t.item.title) < score) { best = { kind: 'movie', item: t.item }; score = rank(t.item.title) }
  return best
}

/** Continue watching's titles, from Home's reply. */
export function continueItems(home: HomeData | undefined): ContinueItem[] {
  if (!home) return []
  return homeRows(home).find((r) => r.style === 'continue')?.continue ?? []
}

/** Where you are in the best match, from Continue watching: its item and the line under the title ("You are on S3 E7 · 29 min left"). */
export function placeIn(match: { kind: 'show' | 'movie'; item: { id: number } } | undefined, items: readonly ContinueItem[]): { item: ContinueItem; line: string } | undefined {
  if (!match) return undefined
  const item = items.find((c) => match.kind === 'movie' ? c.kind === 'movie' && c.movieId === match.item.id : c.kind === 'episode' && c.showId === match.item.id)
  if (!item) return undefined
  const left = leftWords(Math.max(0, item.durationMs - item.positionMs))
  const at = item.kind === 'episode' && item.seasonNumber !== undefined && item.episodeNumber !== undefined ? `S${item.seasonNumber} E${item.episodeNumber}` : undefined
  return { item, line: at ? `You are on ${at} · ${left}` : `You are partway through · ${left}` }
}

/** A title's kind and a fact, under its name: "Movie · 2018", "TV show · 24 episodes". */
export function kindLine(kind: 'show' | 'movie', t: { year?: number; episodeCount?: number }): string {
  return kind === 'movie' ? ['Movie', t.year].filter(Boolean).join(' · ') : ['TV show', t.episodeCount ? plural(t.episodeCount, 'episode') : t.year].filter(Boolean).join(' · ')
}

/** Under Not in your library's title, when the library had nothing: why asking helps. */
export const ASK_LINE = 'Asking sends a request to whoever looks after this server.'

/** At the end of the results while a server is still out after a second. */
export const stillAsking = (names: readonly string[]) => `Still asking ${named(names)}…`

/** In place of a server's line while Try again asks it. */
export const askingAgain = (name: string) => `Asking ${name} again…`

/** A server this TV is signed out of, or one too old for this app: a line at the end of the results, with nothing to try. */
export function leftOutLine(name: string, reason: 'signedOut' | 'tooOld'): string {
  return reason === 'tooOld' ? `${name} is too old for this app, so its titles aren’t listed.` : `You’re signed out of ${name} here, so its titles aren’t listed.`
}

/** A server that failed, at the end of the results, beside Try again. */
export const didNotAnswer = (name: string) => `${name} didn’t answer, so its titles aren’t listed.`

/** A movie's line: "2018 · 1 h 52 min". */
export function movieSub(m: Pick<Movie, 'year' | 'runtimeMin'>): string {
  return [m.year, m.runtimeMin ? formatRuntime(m.runtimeMin) : undefined].filter(Boolean).join(' · ')
}

/** A show's line: "2022– · 24 episodes", without the dash once it has ended. */
export function showSub(s: Pick<Show, 'year' | 'status' | 'episodeCount'>): string {
  const years = s.year ? `${s.year}${s.status === 'Ended' || s.status === 'Canceled' ? '' : '–'}` : undefined
  return [years, s.episodeCount ? plural(s.episodeCount, 'episode') : undefined].filter(Boolean).join(' · ')
}

/** An episode's line: "S3 E7 · The Long Dark". */
export function episodeSub(e: Pick<SearchEpisode, 'seasonNumber' | 'number' | 'title'>): string {
  return [`S${e.seasonNumber} E${e.number}`, e.title].filter(Boolean).join(' · ')
}

/** Not in your library: what search found on TMDB (or Seerr) that isn't among the library's results. */
export function notInLibrary(items: readonly Requestable[] | undefined, results: Pick<SearchResults, 'movies' | 'shows'> | undefined): Requestable[] {
  if (!items) return []
  const shows = new Set(results?.shows.map((s) => s.id))
  const movies = new Set(results?.movies.map((m) => m.id))
  return items.filter((r) => !(r.showId !== undefined && shows.has(r.showId)) && !(r.movieId !== undefined && movies.has(r.movieId)))
}

/** A requestable's badge on its poster: Request, Requested, Downloading, In your library. */
export function requestBadge(state: Requestable['state']): { label: string; icon?: IconName; gold: boolean } {
  switch (state) {
    case 'requested': return { label: 'Requested', icon: 'clock', gold: false }
    case 'downloading': return { label: 'Downloading', icon: 'down', gold: true }
    case 'available': return { label: 'In your library', icon: 'check', gold: true }
    default: return { label: 'Ask for it', gold: false }
  }
}

/** The button beside a title the library lacks: Ask for it, Open once it is in the library, or why there is nothing to press. */
export function askButton(r: Pick<Requestable, 'state' | 'showId' | 'movieId'>): { label: string; why?: string } {
  switch (r.state) {
    case 'requestable': return { label: 'Ask for it' }
    case 'available': return { label: 'Open' }
    case 'downloading': return { label: 'On its way', why: 'It’s downloading. It appears in your library when it’s done.' }
    default: return { label: 'Asked for', why: 'It’s been asked for. It appears in your library once it’s added.' }
  }
}

/** "TV show · 2021", "Movie · 2024". */
export function requestableSub(r: Pick<Requestable, 'mediaType' | 'year'>): string {
  return [r.mediaType === 'tv' ? 'TV show' : 'Movie', r.year].filter(Boolean).join(' · ')
}

/** What OK on a requestable does: ask for it, open it in the library, or nothing while it is on its way. */
export function requestableAction(r: Pick<Requestable, 'state' | 'showId' | 'movieId'>): { kind: 'request' } | { kind: 'show'; id: number } | { kind: 'movie'; id: number } | undefined {
  if (r.state === 'requestable') return { kind: 'request' }
  if (r.state === 'available' && r.showId) return { kind: 'show', id: r.showId }
  if (r.state === 'available' && r.movieId) return { kind: 'movie', id: r.movieId }
  return undefined
}

/** A Live TV result's time: "Now on 20.1 · 33 min left", "9:00 PM on 7.1", "Tue 9:00 PM on 4.1 · Reminder set". */
export function liveWhen(hit: LiveHit, now: number, locale?: string): string {
  const p = hit.programme
  if (isOn(p, now)) return `Now on ${hit.channel.number} · ${duration(Date.parse(p.end) - now)} left`
  const at = searchWhen(p, hit.channel.number, now, locale).replace(/^Today /, '')
  return p.reminder ? `${at} · Reminder set` : at
}

/** The line under its title: the episode, else the kind ("News", "Movie · 1949"). */
export function liveLine(hit: LiveHit): string {
  return episodeLine(hit.programme) || (hit.programme.categories ?? []).slice(0, 2).join(' · ')
}

/** OK on a Live TV result: watch what's on now, else open it in the guide at its start. */
export function liveAction(hit: LiveHit, now: number): { kind: 'watch'; channelId: string } | { kind: 'guide'; channelId: string; at: number } {
  return isOn(hit.programme, now) && hit.channel.available
    ? { kind: 'watch', channelId: hit.channel.id }
    : { kind: 'guide', channelId: hit.channel.id, at: Date.parse(hit.programme.start) }
}
