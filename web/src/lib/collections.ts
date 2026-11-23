import type { components } from '../api/schema'

type LibraryCollection = components['schemas']['LibraryCollection']
type MovieCollection = components['schemas']['MovieCollection']

// A movie library's TMDB collections (API level 34): the Collections tab,
// one collection's page, and a movie's place in its collection.

const WORDS = ['zero', 'one', 'two', 'three', 'four', 'five', 'six', 'seven', 'eight', 'nine', 'ten']
const say = (n: number) => WORDS[n] ?? n.toLocaleString()

/** "1995–2003", "2019", or nothing. */
export function yearsWords(first?: number, last?: number): string {
  if (!first && !last) return ''
  if (!first || !last || first === last) return String(first || last)
  return `${first}–${last}`
}

/** A collection card's line: "2 of 3 · 1995–2003", or "4 films · 1981–1989" when TMDB's size isn't known. */
export function collectionLine(c: Pick<LibraryCollection, 'count' | 'total' | 'firstYear' | 'lastYear'>): string {
  const have = c.total ? `${c.count.toLocaleString()} of ${c.total.toLocaleString()}` : `${c.count.toLocaleString()} ${c.count === 1 ? 'film' : 'films'}`
  return [have, yearsWords(c.firstYear, c.lastYear)].filter(Boolean).join(' · ')
}

/** A collection page's line: "You have all three", "You have 2 of 3". */
export function collectionHave(c: Pick<LibraryCollection, 'count' | 'total'>): string {
  if (c.total && c.count >= c.total) return c.total === 2 ? 'You have both' : `You have all ${say(c.total)}`
  if (c.total) return `You have ${c.count.toLocaleString()} of ${c.total.toLocaleString()}`
  return `You have ${c.count.toLocaleString()} ${c.count === 1 ? 'film' : 'films'}`
}

/** A movie's collection row's note: "Part 2 of 3 · you have all three". */
export function collectionPartWords(c: MovieCollection | undefined): string {
  if (!c) return ''
  const part = c.part && c.total ? `Part ${c.part.toLocaleString()} of ${c.total.toLocaleString()}` : ''
  const have = c.total ? collectionHave({ count: c.have, total: c.total }) : ''
  return [part, have.charAt(0).toLowerCase() + have.slice(1)].filter(Boolean).join(' · ')
}
