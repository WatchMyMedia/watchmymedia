import type { components } from '../api/schema'

type MediaRequest = components['schemas']['MediaRequest']

// Requests that have arrived: a person's own requests the server now has,
// which the app bar's account menu and Home's notices point to until they are
// seen. A request carries no time it arrived, only when it was asked for, so
// only requests asked for in the last ARRIVAL_DAYS count, and each is shown
// until this browser has seen it.

export const ARRIVAL_DAYS = 30

/** The person's arrived requests not yet seen here, newest first. */
export function arrivedRequests(items: readonly MediaRequest[], seen: ReadonlySet<number>, now: number): MediaRequest[] {
  const since = now - ARRIVAL_DAYS * 86_400_000
  return items
    .filter((r) => r.mine && r.status === 'available' && !seen.has(r.id) && Date.parse(r.createdAt) >= since)
    .sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt))
}

/** "1 arrived", "3 arrived"; undefined for none. */
export function arrivedWords(count: number): string | undefined {
  return count > 0 ? `${count} arrived` : undefined
}

/** The seen ids as stored: a list, newest last, kept short. */
export function seenAfter(previous: readonly number[], ids: readonly number[], keep = 200): number[] {
  const next = [...previous.filter((id) => !ids.includes(id)), ...ids]
  return next.slice(-keep)
}

/** Stored seen ids read back; anything else is none. */
export function parseSeen(stored: string | null): number[] {
  if (!stored) return []
  try {
    const v: unknown = JSON.parse(stored)
    return Array.isArray(v) ? v.filter((n): n is number => typeof n === 'number') : []
  } catch {
    return []
  }
}
