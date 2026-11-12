import type { InfiniteData } from '@tanstack/react-query'
import type { components } from '../api/schema'

export type PlexHistoryPage = components['schemas']['PlexHistoryList']
export type PlexHistoryPages = InfiniteData<PlexHistoryPage>

/** Keep the original upper boundary even if its first or last row is deleted. */
export function retainPlexHistoryHead(head: PlexHistoryPage): PlexHistoryPages {
  return { pages: [head], pageParams: [(head.items[0]?.id ?? 0) + 1] }
}

/** Find the first unread range below the live head. A large import can move
 * the head past retained pages; fill that gap before continuing older pages. */
export function plexHistoryCursor(head?: PlexHistoryPage, retained?: PlexHistoryPages): number | undefined {
  let before = head?.next
  if (before === undefined) return undefined
  const ranges = (retained?.pages ?? []).map((page, index) => {
    const param = retained?.pageParams[index]
    return { page, upper: typeof param === 'number' ? param : 0 }
  })
    .sort((a, b) => b.upper - a.upper)
  for (const { page, upper } of ranges) {
    if (upper < before) return before
    if (page.next === undefined) return undefined
    if (page.next < before) before = page.next
  }
  return before
}

/** Fresh head rows win; frozen older rows retain their pagination boundary. */
export function plexHistoryRows(head?: PlexHistoryPage, retained?: PlexHistoryPages) {
  if (head && head.next === undefined) return head.items
  const rows = new Map<number, PlexHistoryPage['items'][number]>()
  const liveIDs = new Set(head?.items.map((item) => item.id))
  const lower = head?.next ?? head?.items.at(-1)?.id
  for (const page of retained?.pages ?? []) for (const item of page.items) {
    // A removed row in the current head must not return from an old snapshot.
    if (lower !== undefined && item.id >= lower && !liveIDs.has(item.id)) continue
    rows.set(item.id, item)
  }
  for (const item of head?.items ?? []) rows.set(item.id, item)
  return [...rows.values()].sort((a, b) => b.id - a.id)
}
