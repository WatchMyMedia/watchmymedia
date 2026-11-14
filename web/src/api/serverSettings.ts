import { queryOptions } from '@tanstack/react-query'
import { api, unwrap } from './client'

// Queries the Transcoding, Backups, General and Watch history pages add to
// the ones in settings.ts (PLAN-ADMIN-SETTINGS.md §5.7–§5.9).

/** How many plays a shorter Keep history for would delete. Counting changes nothing. */
export const playsOlderThanQuery = (days: number) =>
  queryOptions({
    queryKey: ['activity', 'older-than', days],
    queryFn: async () => unwrap(await api.GET('/api/settings/activity/older-than', { params: { query: { days } } })),
    staleTime: 60_000,
  })
