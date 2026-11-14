// The save rule (PLAN-ADMIN-SETTINGS.md §3.6): a switch or a choice saves at
// once; typed fields and forms whose fields depend on each other are drafts,
// held against what was saved until Save or Discard.

/** Plain-data equality for drafts: objects, arrays and primitives. */
export function sameValue(a: unknown, b: unknown): boolean {
  if (Object.is(a, b)) return true
  if (typeof a !== 'object' || typeof b !== 'object' || a === null || b === null) return false
  if (Array.isArray(a) !== Array.isArray(b)) return false
  if (Array.isArray(a) && Array.isArray(b)) return a.length === b.length && a.every((v, i) => sameValue(v, b[i]))
  const ka = Object.keys(a as object).filter((k) => (a as Record<string, unknown>)[k] !== undefined)
  const kb = Object.keys(b as object).filter((k) => (b as Record<string, unknown>)[k] !== undefined)
  return ka.length === kb.length && ka.every((k) => sameValue((a as Record<string, unknown>)[k], (b as Record<string, unknown>)[k]))
}

/** The fields of a draft that differ from the saved value, for the request body. */
export function changedFields<T extends Record<string, unknown>>(saved: T, draft: T): Partial<T> {
  const out: Partial<T> = {}
  for (const k of Object.keys(draft) as (keyof T)[]) if (!sameValue(saved[k], draft[k])) out[k] = draft[k]
  return out
}

/** The leave check: ask before going to another page with unsaved work.
 * Staying on the same address (a search or hash change) never asks. */
export function shouldAskToLeave(dirty: boolean, from: string, to: string) {
  return dirty && from.replace(/\/+$/, '') !== to.replace(/\/+$/, '')
}
