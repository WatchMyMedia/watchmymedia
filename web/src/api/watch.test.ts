import { QueryClient, QueryObserver } from '@tanstack/react-query'
import { expect, it } from 'vitest'
import { watchEventInvalidator } from './watch'

const turn = () => new Promise<void>((resolve) => setTimeout(resolve, 0))

it('coalesces batch events, bounds concurrency and fetches a snapshot after the final commit', async () => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: Infinity, staleTime: Infinity } } })
  let committed = 1
  let requests = 0
  let inFlight = 0
  let peak = 0
  const finish: (() => void)[] = []
  const observer = new QueryObserver(client, {
    queryKey: ['home'],
    initialData: 0,
    queryFn: () => {
      requests++
      peak = Math.max(peak, ++inFlight)
      const snapshot = committed
      return new Promise<number>((resolve) => finish.push(() => { inFlight--; resolve(snapshot) }))
    },
  })
  const stop = observer.subscribe(() => {})
  const changed = watchEventInvalidator(client)
  try {
    const first = observer.refetch()
    for (committed = 2; committed <= 31; committed++) changed()
    committed = 31
    await turn()
    expect(requests).toBe(1)
    finish.shift()!()
    await first
    await turn()
    expect(requests).toBe(2)
    expect(observer.getCurrentResult().data).toBe(1)

    // More commits during the trailing request need one further refresh,
    // even though its in-flight snapshot did not include these changes.
    for (committed = 32; committed <= 61; committed++) changed()
    committed = 61
    await turn()
    expect(requests).toBe(2)
    finish.shift()!()
    await turn()
    expect(requests).toBe(3)
    finish.shift()!()
    await turn()
    expect(observer.getCurrentResult().data).toBe(61)
    expect(peak).toBe(1)
    expect(requests).toBe(3)
  } finally {
    stop()
    client.clear()
  }
})

it('handles a same-turn event burst with one request and marks inactive pages stale without fetching them', async () => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: Infinity, staleTime: Infinity } } })
  let requests = 0
  const observer = new QueryObserver(client, { queryKey: ['home'], initialData: 0, queryFn: async () => ++requests })
  client.setQueryData(['movie', 7], { watched: false })
  const stop = observer.subscribe(() => {})
  try {
    const changed = watchEventInvalidator(client)
    for (let event = 0; event < 30; event++) changed()
    await turn()
    expect(requests).toBe(1)
    expect(observer.getCurrentResult().data).toBe(1)
    expect(client.getQueryState(['movie', 7])?.isInvalidated).toBe(true)
  } finally {
    stop()
    client.clear()
  }
})
