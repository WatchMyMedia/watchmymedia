import { queryOptions } from '@tanstack/react-query'
import { api, unwrap, type ApiClient } from './client'
import type { components } from './schema'

// A title's copies on a server, found by its TMDB id (API level 34):
// GET /api/movies?tmdbId= and GET /api/shows?tmdbId=, every library's copy
// with a present file, the first library's first; an empty list when the
// server has none. An app signed in to several servers asks each of them, so
// a title on more than one is one card with a copy on each.

export type MovieCopies = components['schemas']['MovieCopies']
export type ShowCopies = components['schemas']['ShowCopies']

/** Asks `client` (the page's own server by default) for its copies of a movie. */
export async function findMovieCopies(tmdbId: number, signal?: AbortSignal, client: ApiClient = api): Promise<MovieCopies> {
  return unwrap(await client.GET('/api/movies', { params: { query: { tmdbId } }, signal }))
}

/** Asks `client` (the page's own server by default) for its copies of a show. */
export async function findShowCopies(tmdbId: number, signal?: AbortSignal, client: ApiClient = api): Promise<ShowCopies> {
  return unwrap(await client.GET('/api/shows', { params: { query: { tmdbId } }, signal }))
}

export const movieCopiesQuery = (tmdbId: number) =>
  queryOptions({
    queryKey: ['copies', 'movie', tmdbId],
    queryFn: ({ signal }) => findMovieCopies(tmdbId, signal),
  })

export const showCopiesQuery = (tmdbId: number) =>
  queryOptions({
    queryKey: ['copies', 'show', tmdbId],
    queryFn: ({ signal }) => findShowCopies(tmdbId, signal),
  })
