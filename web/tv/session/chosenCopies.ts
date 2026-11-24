import { platform } from '../platform/current'

// The copy Play uses for a title on several servers, chosen in the version
// panel and kept on this TV: by the title's TMDB id (an episode by its
// show's, season and number), the server and the file. Nothing of it leaves
// the TV. Unreadable or refused storage keeps choices for this run only.

const KEY = platform.storage.copies
/** How many titles' choices are kept; the oldest go first. */
export const MAX_CHOICES = 300

export type ChosenCopy = { server: string; file: number }

/** A title's key: "movie:50102", "episode:60201:2:4". */
export function titleKey(t: { kind: 'movie'; tmdbId: number } | { kind: 'episode'; showTmdbId: number; season: number; episode: number }): string {
  return t.kind === 'movie' ? `movie:${t.tmdbId}` : `episode:${t.showTmdbId}:${t.season}:${t.episode}`
}

export class ChosenCopies {
  private memory?: [string, ChosenCopy][]

  constructor(private storage: Pick<Storage, 'getItem' | 'setItem'> | undefined) {}

  private all(): [string, ChosenCopy][] {
    if (this.memory) return this.memory
    let items: [string, ChosenCopy][] = []
    try {
      const parsed: unknown = JSON.parse(this.storage?.getItem(KEY) ?? '[]')
      if (Array.isArray(parsed)) {
        items = parsed.filter((e): e is [string, ChosenCopy] => Array.isArray(e) && typeof e[0] === 'string' && !!e[1] && typeof e[1].server === 'string' && typeof e[1].file === 'number')
      }
    } catch { /* A broken store is an empty one. */ }
    return this.memory = items
  }

  get(key: string): ChosenCopy | undefined {
    return this.all().find(([k]) => k === key)?.[1]
  }

  /** Keeps a title's choice, the newest last. */
  set(key: string, copy: ChosenCopy) {
    const next = [...this.all().filter(([k]) => k !== key), [key, copy] as [string, ChosenCopy]].slice(-MAX_CHOICES)
    this.memory = next
    try { this.storage?.setItem(KEY, JSON.stringify(next)) } catch { /* Kept for this run only. */ }
  }
}

let shared: ChosenCopies | undefined

/** The TV's own store of chosen copies. */
export function chosenCopies(): ChosenCopies {
  if (!shared) {
    let storage: Storage | undefined
    try { storage = typeof localStorage === 'undefined' ? undefined : localStorage } catch { storage = undefined }
    shared = new ChosenCopies(storage)
  }
  return shared
}
