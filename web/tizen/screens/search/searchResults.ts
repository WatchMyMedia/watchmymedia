import type { components } from '../../../src/api/schema'
import { formatRuntime, plural } from '../../../src/lib/format'
import { duration, episodeLine, isOn } from '../../../src/lib/liveGuide'
import { searchWhen } from '../../../src/lib/liveReminders'
import { suggestions } from '../../../src/lib/searchSuggestions'
import type { IconName } from '../../ui/Icon'

// Search's results on the TV: the suggestions under
// the box, each section's cards and their lines, On Live TV's times and
// what OK does there, and Not in your library.

type SearchResults = components['schemas']['SearchResults']
type Movie = components['schemas']['Movie']
type Show = components['schemas']['Show']
type SearchEpisode = components['schemas']['SearchEpisode']
type Requestable = components['schemas']['Requestable']
type LiveHit = components['schemas']['LiveTVSearchHit']

/** A suggestion under the box: a title, with the part typed in bold. */
export type Chip = { key: string; title: string; parts: [string, string, string] }

/** Where `typed` is in `title`, ignoring case, for the bold part: at the start, else at a word's start, else anywhere; none when it isn't. */
export function highlight(title: string, typed: string): [string, string, string] {
  const q = typed.trim().toLowerCase()
  const t = title.toLowerCase()
  if (!q || t.length !== title.length) return [title, '', '']
  let at = t.startsWith(q) ? 0 : -1
  for (let i = t.indexOf(q); at < 0 && i >= 0; i = t.indexOf(q, i + 1)) if (!/[\p{L}\p{N}]/u.test(t[i - 1] ?? ' ')) at = i
  if (at < 0) at = t.indexOf(q)
  return at < 0 ? [title, '', ''] : [title.slice(0, at), title.slice(at, at + q.length), title.slice(at + q.length)]
}

/** Up to three titles that complete what is typed, best first (the web's suggestions, without episodes). */
export function suggestionChips(results: SearchResults | undefined, typed: string, max = 3): Chip[] {
  if (!results) return []
  const seen = new Set<string>()
  return suggestions(results, typed).filter((s) => s.kind !== 'episode').filter((s) => {
    const key = s.title.toLowerCase()
    if (seen.has(key) || key === typed.trim().toLowerCase()) return false
    seen.add(key)
    return true
  }).slice(0, max).map((s) => ({ key: `${s.kind}-${s.id}`, title: s.title, parts: highlight(s.title, typed) }))
}

/** Names in a sentence: "Cabin server", "Cabin server and Den server", "A, B and C". */
function named(names: readonly string[]): string {
  return names.length <= 1 ? names[0] ?? '' : `${names.slice(0, -1).join(', ')} and ${names[names.length - 1]}`
}

/** Before anything is typed. With two or more servers to look in, it says Search looks in all of them. */
export function emptyWords(servers: number, liveTV: boolean): { title: string; body: string } {
  if (servers > 1) return { title: 'Search your servers', body: `Find a show, a movie or an episode on any of your ${servers} servers.` }
  return { title: 'Find anything in your library', body: `Type part of a title. Shows, movies and episodes appear here as you type${liveTV ? ', with what’s on Live TV' : ''}.` }
}

/** Nothing found, once every server asked has answered: `answered` counts those that did. */
export function noMatchWords(q: string, answered: number): { title: string; body: string } {
  if (answered > 1) return { title: `No matches on your ${answered} servers`, body: `Nothing is called “${q}” on any of them. Check the spelling, or try fewer words.` }
  return { title: 'Nothing matches', body: `No titles in your library match “${q}”.` }
}

/** At the end of the results while a server is still out after a second. */
export const stillAsking = (names: readonly string[]) => `Still asking ${named(names)}…`

/** In place of a server's line while Try again asks it. */
export const askingAgain = (name: string) => `Asking ${name} again…`

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

/**
 * Shows and movies side by side when they fit one line of the results (as
 * the board draws a few), else a row each.
 */
export function libraryBand(shows: number, movies: number, fit = 4): 'band' | 'rows' {
  return shows + movies <= fit ? 'band' : 'rows'
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
    default: return { label: 'Request', gold: false }
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
