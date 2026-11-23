import type { components } from '../api/schema'

type PersonTitle = components['schemas']['PersonTitle']
type CrewMember = components['schemas']['CrewMember']

// A person's page, opened from a title's cast: their movies and shows in this
// library, and the crew line a title's cast row carries ("Directed by…").

/** "5 titles in your library: 3 movies and 2 shows", "1 title in your library". */
export function personCountLine(movies: number, shows: number): string {
  const n = movies + shows
  const titles = `${n.toLocaleString()} ${n === 1 ? 'title' : 'titles'} in your library`
  if (!movies || !shows) return titles
  return `${titles}: ${movies.toLocaleString()} ${movies === 1 ? 'movie' : 'movies'} and ${shows.toLocaleString()} ${shows === 1 ? 'show' : 'shows'}`
}

/** "as Tess Arden", or nothing when TMDB names no part. */
export function roleWords(character: string | undefined): string {
  const c = character?.trim()
  return c ? `as ${c}` : ''
}

/** The one title a person is in here, when they are in only one: the page then says so and offers the way back. */
export function onlyTitle(p: { movies: PersonTitle[]; shows: PersonTitle[] }): PersonTitle | undefined {
  const all = [...p.movies, ...p.shows]
  return all.length === 1 ? all[0] : undefined
}

/** "A", "A and B", "A, B and C": at most three names, the rest counted. */
function names(list: string[]): string {
  const shown = list.slice(0, 3)
  const more = list.length - shown.length
  if (more > 0) return `${shown.join(', ')} and ${more} more`
  if (shown.length <= 2) return shown.join(' and ')
  return `${shown.slice(0, -1).join(', ')} and ${shown[shown.length - 1]}`
}

/**
 * The cast row's note: "Directed by Ines Calloway · Written by Oren Mallory",
 * or for a show "Created by …". Screenplay and Writer are one "Written by";
 * Story counts only when nobody is credited with writing. Music and
 * producers stay on TMDB.
 */
export function crewLine(crew: CrewMember[] | undefined): string {
  if (!crew?.length) return ''
  const by = (jobs: string[]) => [...new Set(crew.filter((c) => jobs.includes(c.job)).map((c) => c.name.trim()).filter(Boolean))]
  const writers = by(['Screenplay', 'Writer'])
  const parts: string[] = []
  const created = by(['Creator'])
  if (created.length) parts.push(`Created by ${names(created)}`)
  const directed = by(['Director'])
  if (directed.length) parts.push(`Directed by ${names(directed)}`)
  const written = writers.length ? writers : by(['Story'])
  if (written.length) parts.push(`${writers.length ? 'Written' : 'Story'} by ${names(written)}`)
  return parts.join(' · ')
}
