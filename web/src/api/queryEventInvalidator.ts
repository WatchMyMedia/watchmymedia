import type { InvalidateQueryFilters, QueryClient } from '@tanstack/react-query'

/** Reuse an in-flight snapshot, then refresh after the last committed event.
 * Batch notifications neither overlap requests nor replay disabled snapshots. */
export function queryEventInvalidator(qc: QueryClient, filter: InvalidateQueryFilters): () => void {
  let running = false
  let dirty = false
  const flush = async () => {
    try {
      do {
        dirty = false
        const alreadyFetching = qc.getQueryCache().findAll(filter).some((query) => query.isActive() && query.state.fetchStatus === 'fetching')
        await qc.invalidateQueries(filter, { cancelRefetch: false })
        if (alreadyFetching) dirty = true
      } while (dirty)
    } finally {
      running = false
    }
  }
  return () => {
    dirty = true
    if (running) return
    running = true
    queueMicrotask(() => void flush())
  }
}
