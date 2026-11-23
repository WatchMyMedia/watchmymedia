import { useSyncExternalStore } from 'react'
import { useQuery } from '@tanstack/react-query'
import { myRequestsQuery, requestOptionsQuery } from '../api/requests'
import { arrivedRequests, parseSeen, seenAfter } from '../lib/arrivals'
import { useNow } from './useNow'

// The ids of arrived requests this browser has seen, kept in its storage and
// shared by the account menu and Home's notices.

const KEY = 'wmm.arrivals.seen'
const listeners = new Set<() => void>()
let cache: { raw: string | null; ids: ReadonlySet<number> } | undefined

function read(): ReadonlySet<number> {
  let raw: string | null = null
  try {
    raw = localStorage.getItem(KEY)
  } catch {
    // Storage refused: nothing is remembered.
  }
  if (!cache || cache.raw !== raw) cache = { raw, ids: new Set(parseSeen(raw)) }
  return cache.ids
}

function subscribe(fn: () => void) {
  listeners.add(fn)
  return () => listeners.delete(fn)
}

/** Marks requests seen, so they leave the menu's count and Home's notices. */
export function markArrivalsSeen(ids: readonly number[]) {
  if (!ids.length) return
  try {
    localStorage.setItem(KEY, JSON.stringify(seenAfter([...read()], ids)))
  } catch {
    // Storage refused: they show again next time.
  }
  for (const fn of listeners) fn()
}

/** The person's requests that arrived and haven't been seen here, when requests are offered. */
export function useArrivals() {
  const options = useQuery(requestOptionsQuery)
  const offered = !!options.data && (options.data.movies || options.data.shows)
  const mine = useQuery({ ...myRequestsQuery, enabled: offered })
  const seen = useSyncExternalStore(subscribe, read)
  const now = useNow(60_000)
  const arrived = offered && mine.data ? arrivedRequests(mine.data.items, seen, now) : []
  return { offered, arrived, inbox: options.data?.inbox ?? 0, manage: options.data?.manage ?? false }
}
