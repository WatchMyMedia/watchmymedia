import type { UseQueryOptions } from '@tanstack/react-query'
import { unwrap, type ApiClient } from '../../src/api/client'
import { fileQuery, movieQuery, movieRelatedQuery, playbackPlansQuery, seasonQuery, showQuery, showRelatedQuery } from '../../src/api/queries'
import { personTitlesQuery } from '../../src/api/people'
import type { components } from '../../src/api/schema'
import type { Source } from './peerRegistry'

// A title page's queries from wherever the title is: the current server's
// are the web's own options, unchanged; another server's ask the same
// routes through that server's own client, under keys that start with
// 'peer' and its id, so nothing of it mixes with the current server's cache
// or is refetched by the current server's events.

type Schemas = components['schemas']
type Options<T> = UseQueryOptions<T, Error, T, readonly unknown[]>

function on<T>(source: Source, current: () => unknown, key: readonly unknown[], ask: (api: ApiClient, signal: AbortSignal) => Promise<T>, extra: Partial<Options<T>> = {}): Options<T> {
  if (!source.peer) return current() as Options<T>
  return { queryKey: ['peer', source.id, ...key], queryFn: ({ signal }) => ask(source.api, signal), ...extra }
}

export const movieOn = (source: Source, id: number): Options<Schemas['MovieDetail']> =>
  on(source, () => movieQuery(id), ['movie', id], async (api, signal) => unwrap(await api.GET('/api/movies/{id}', { params: { path: { id } }, signal })))

export const movieRelatedOn = (source: Source, id: number): Options<Schemas['RelatedRows']> =>
  on(source, () => movieRelatedQuery(id), ['related', 'movie', id], async (api, signal) => unwrap(await api.GET('/api/movies/{id}/related', { params: { path: { id } }, signal })), { staleTime: 5 * 60_000 })

export const showOn = (source: Source, id: number): Options<Schemas['ShowDetail']> =>
  on(source, () => showQuery(id), ['show', id], async (api, signal) => unwrap(await api.GET('/api/shows/{id}', { params: { path: { id } }, signal })))

export const showRelatedOn = (source: Source, id: number): Options<Schemas['RelatedRows']> =>
  on(source, () => showRelatedQuery(id), ['related', 'show', id], async (api, signal) => unwrap(await api.GET('/api/shows/{id}/related', { params: { path: { id } }, signal })), { staleTime: 5 * 60_000 })

export const seasonOn = (source: Source, showId: number, number: number): Options<Schemas['SeasonDetail']> =>
  on(source, () => seasonQuery(showId, number), ['show', showId, 'season', number],
    async (api, signal) => unwrap(await api.GET('/api/shows/{id}/seasons/{number}', { params: { path: { id: showId, number } }, signal })))

export const fileOn = (source: Source, id: number): Options<Schemas['MediaFileDetail']> =>
  on(source, () => fileQuery(id), ['file', id], async (api, signal) => unwrap(await api.GET('/api/files/{id}', { params: { path: { id } }, signal })), { staleTime: 5 * 60_000 })

export const plansOn = (source: Source, fileIds: number[], profile: Schemas['Profile']): Options<Schemas['PlanList']> =>
  on(source, () => playbackPlansQuery(fileIds, profile), ['plans', profile.name, fileIds],
    async (api, signal) => unwrap(await api.POST('/api/playback/plans', { body: { profile, fileIds }, signal })), { enabled: fileIds.length > 0, staleTime: 5 * 60_000 })

export const personOn = (source: Source, personId: number): Options<Schemas['PersonDetail']> =>
  on(source, () => personTitlesQuery(personId), ['person', personId], async (api, signal) => unwrap(await api.GET('/api/people/{personId}', { params: { path: { personId } }, signal })))
