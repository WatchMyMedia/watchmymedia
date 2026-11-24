import { choicesFrom, DEFAULT_CHOICES, type GridChoices } from './libraryView'

// A library's filter and sort, kept on this TV: Movies opens the way it was
// left next time. Per server and library, in the TV's own storage; nothing
// follows the person to another device.

const PREFIX = 'wmm.tv.grid.'

function key(serverId: string, libraryId: number) { return `${PREFIX}${serverId}.${libraryId}` }

/** The choices kept for a library, or the defaults. Anything unknown is dropped. */
export function keptChoices(serverId: string, libraryId: number, storage: Pick<Storage, 'getItem'> | undefined = safeStorage()): GridChoices {
  try {
    const raw = storage?.getItem(key(serverId, libraryId))
    if (!raw) return DEFAULT_CHOICES
    const v = JSON.parse(raw) as Record<string, unknown>
    // `filter` is how a TV kept its one filter before API level 34's filters combined.
    return choicesFrom({ sort: str(v.sort), filter: str(v.filter), filters: Array.isArray(v.filters) ? v.filters : undefined, genre: str(v.genre), decade: typeof v.decade === 'number' ? v.decade : undefined, rating: str(v.rating) })
  } catch {
    return DEFAULT_CHOICES
  }
}

/** Keeps a library's choices for next time. */
export function keepChoices(serverId: string, libraryId: number, c: GridChoices, storage: Pick<Storage, 'setItem'> | undefined = safeStorage()) {
  try { storage?.setItem(key(serverId, libraryId), JSON.stringify(c)) } catch { /* kept for this run only */ }
}

function str(v: unknown) { return typeof v === 'string' ? v : undefined }

function safeStorage(): Storage | undefined {
  try { return typeof localStorage === 'undefined' ? undefined : localStorage } catch { return undefined }
}
