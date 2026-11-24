import type { components } from '../../../src/api/schema'
import { plural } from '../../../src/lib/format'
import type { Route } from '../../app/routes'

// A cast member's page (route `person`, from API level 34): the line under
// their name, each title's line, and what OK on a cast card does.

type Credit = components['schemas']['Credit']
type PersonTitle = components['schemas']['PersonTitle']

/** "3 movies and 2 shows on Living room server", "1 movie on Cabin". */
export function personLine(movies: number, shows: number, server: string): string {
  const parts = [movies ? plural(movies, 'movie') : '', shows ? plural(shows, 'show') : ''].filter(Boolean)
  return parts.length ? `${parts.join(' and ')} on ${server}` : `Nothing of theirs is on ${server}`
}

/** The line under a title's poster: "As Nell Kerr · 2026", or the year alone. */
export function personTitleLine(t: Pick<PersonTitle, 'character' | 'year'>): string | undefined {
  return [t.character ? `As ${t.character}` : '', t.year ? String(t.year) : ''].filter(Boolean).join(' · ') || undefined
}

/** Said when a cast card the server doesn't know yet is pressed: it is still looking up who each credit is. */
export const PERSON_UNKNOWN = 'Their other titles aren’t known yet. The server is still looking them up.'

/**
 * What OK on a cast card does: open their titles when the server can say
 * who they are; 'unknown' when it answers people (level 34) but hasn't
 * stored this credit's person yet; nothing below level 34, as before.
 */
export function castPress(c: Pick<Credit, 'personId' | 'name'>, level34: boolean): { kind: 'open'; route: Route } | { kind: 'unknown' } | undefined {
  if (!level34) return undefined
  if (!c.personId) return { kind: 'unknown' }
  return { kind: 'open', route: { name: 'person', personId: c.personId, who: c.name } }
}

/** A title's route from a person's page. */
export function personTitleRoute(t: Pick<PersonTitle, 'kind' | 'id'>): Route | undefined {
  if (t.kind === 'movie') return { name: 'movie', id: t.id }
  if (t.kind === 'show') return { name: 'show', id: t.id }
  return undefined
}
