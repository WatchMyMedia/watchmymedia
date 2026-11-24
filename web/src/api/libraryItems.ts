import { queryOptions } from '@tanstack/react-query'
import { api, unwrap, type ApiClient } from './client'
import type { ItemFilter, ItemSort } from '../lib/browseOptions'

// A library's grid with API level 34's choices: several filters at once
// (`filter` and `filters`), In progress and Following, the Rated filter
// (`contentRating`) and the sort's direction. The server's lists are sent
// comma separated, as it reads them. Older servers ignore what they don't
// know, so ask them only with `itemsQuery`'s choices.

/** Every filter the server takes from level 34. */
export type LibraryFilter = Exclude<ItemFilter, ''> | 'inProgress' | 'following'

export type LibraryItemsParams = {
  sort: ItemSort
  /** Each must hold; the first goes as `filter`, the rest as `filters`. */
  filters: readonly LibraryFilter[]
  /** TMDB's US certifications to keep, or `unrated`. */
  contentRating?: readonly string[]
  order?: 'asc' | 'desc'
  genre?: string
  decade?: number
  q?: string
  limit: number
  offset: number
}

/** A page of a library's grid from `client` (the page's own server by default). */
export async function fetchLibraryItems(id: number, p: LibraryItemsParams, signal?: AbortSignal, client: ApiClient = api) {
  return unwrap(
    await client.GET('/api/libraries/{id}/items', {
      params: {
        path: { id },
        query: {
          sort: p.sort,
          filter: p.filters[0],
          filters: p.filters.length > 1 ? [...p.filters.slice(1)] : undefined,
          contentRating: p.contentRating?.length ? [...p.contentRating] : undefined,
          order: p.order,
          genre: p.genre || undefined,
          decade: p.decade || undefined,
          q: p.q || undefined,
          limit: p.limit,
          offset: p.offset,
        },
      },
      querySerializer: { array: { style: 'form', explode: false } },
      signal,
    }),
  )
}

export const libraryItemsQuery = (id: number, p: LibraryItemsParams) =>
  queryOptions({
    queryKey: ['items', id, p],
    queryFn: ({ signal }) => fetchLibraryItems(id, p, signal),
    placeholderData: (prev) => prev,
  })
