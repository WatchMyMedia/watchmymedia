import { queryOptions } from '@tanstack/react-query'
import { api, unwrap } from './client'
import type { components } from './schema'

// A cast member's titles in the library (GET /api/people/{personId}, from
// API level 34): their movies and shows with a present file, newest first,
// each with the part they played. A credit carries the person's TMDB id once
// the server has stored it; a credit without one leads nowhere yet.

export type PersonDetail = components['schemas']['PersonDetail']
export type PersonTitle = components['schemas']['PersonTitle']

export const personQuery = (personId: number) =>
  queryOptions({
    queryKey: ['person', personId],
    queryFn: async () => unwrap(await api.GET('/api/people/{personId}', { params: { path: { personId } } })),
  })
