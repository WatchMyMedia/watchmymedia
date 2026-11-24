import { platform } from '../../platform/current'

// The last few searches someone made, kept on this TV for each server and
// account, so Search can offer them again above the keyboard. A TV is
// shared, so they belong to the person signed in, not the TV, and they
// never leave it.

const KEY = platform.storage.recentSearches

/** How many Search again offers. */
export const RECENT_MAX = 3

/** The list after a search is used: it goes first, once, whatever its case; the oldest drops off. */
export function addRecent(list: readonly string[], words: string, max = RECENT_MAX): string[] {
  const q = words.trim().replace(/\s+/g, ' ')
  if (!q) return [...list]
  return [q, ...list.filter((s) => s.toLowerCase() !== q.toLowerCase())].slice(0, max)
}

/** One person's searches on one server. */
export const recentScope = (serverId: string, userId: number) => `${serverId}/${userId}`

function readAll(storage: Pick<Storage, 'getItem'> | undefined): Record<string, string[]> {
  try {
    const raw = JSON.parse(storage?.getItem(KEY) ?? '{}') as unknown
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return {}
    const out: Record<string, string[]> = {}
    for (const [scope, list] of Object.entries(raw as Record<string, unknown>)) {
      if (Array.isArray(list)) out[scope] = list.filter((s): s is string => typeof s === 'string' && s.length > 0).slice(0, RECENT_MAX)
    }
    return out
  } catch {
    return {}
  }
}

export function readRecent(storage: Pick<Storage, 'getItem'> | undefined, scope: string): string[] {
  return readAll(storage)[scope] ?? []
}

/** Keeps `words` as this person's latest search; returns the new list. */
export function rememberSearch(storage: Pick<Storage, 'getItem' | 'setItem'> | undefined, scope: string, words: string): string[] {
  const all = readAll(storage)
  const list = addRecent(all[scope] ?? [], words)
  all[scope] = list
  try { storage?.setItem(KEY, JSON.stringify(all)) } catch { /* kept for this visit only */ }
  return list
}
