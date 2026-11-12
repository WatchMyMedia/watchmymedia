import { queryOptions, useMutation, useQueryClient } from '@tanstack/react-query'
import { api, unwrap } from './client'
import type { components } from './schema'

// Change poster (PLAN-POSTERS.md): admins choose a movie's, show's or
// season's poster. The same four routes exist per kind.

export type PosterKind = 'movie' | 'show' | 'season'
export type PosterOptions = components['schemas']['PosterOptions']
export type PosterChoice = components['schemas']['PosterChoice']
export type PosterResult = components['schemas']['PosterResult']

export const posterOptionsQuery = (kind: PosterKind, id: number) =>
  queryOptions({
    queryKey: ['posters', kind, id],
    queryFn: async () => {
      const params = { path: { id } }
      return unwrap(
        kind === 'movie'
          ? await api.GET('/api/movies/{id}/posters', { params })
          : kind === 'show'
            ? await api.GET('/api/shows/{id}/posters', { params })
            : await api.GET('/api/seasons/{id}/posters', { params }),
      )
    },
    staleTime: 60_000,
  })

/** What Save sends: one of TMDB's posters, a link, a file, or back to the match's own. */
export type PosterChange = { tmdbPath: string } | { url: string } | { file: File } | { reset: true }

async function change(kind: PosterKind, id: number, c: PosterChange): Promise<PosterResult> {
  const params = { path: { id } }
  if ('reset' in c) {
    return unwrap(
      kind === 'movie'
        ? await api.DELETE('/api/movies/{id}/poster', { params })
        : kind === 'show'
          ? await api.DELETE('/api/shows/{id}/poster', { params })
          : await api.DELETE('/api/seasons/{id}/poster', { params }),
    )
  }
  if ('file' in c) {
    // The body is the picture itself. The server reads its bytes, not its
    // type, so one content type does for all three.
    const upload = {
      params,
      body: c.file as unknown as string,
      bodySerializer: (body: unknown) => body as BodyInit,
      headers: { 'Content-Type': 'application/octet-stream' },
    }
    return unwrap(
      kind === 'movie'
        ? await api.POST('/api/movies/{id}/poster/upload', upload)
        : kind === 'show'
          ? await api.POST('/api/shows/{id}/poster/upload', upload)
          : await api.POST('/api/seasons/{id}/poster/upload', upload),
    )
  }
  const body = c
  return unwrap(
    kind === 'movie'
      ? await api.PUT('/api/movies/{id}/poster', { params, body })
      : kind === 'show'
        ? await api.PUT('/api/shows/{id}/poster', { params, body })
        : await api.PUT('/api/seasons/{id}/poster', { params, body }),
  )
}

export function useChangePoster() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ kind, id, change: c }: { kind: PosterKind; id: number; change: PosterChange }) => change(kind, id, c),
    // Posters are drawn on nearly every page.
    onSuccess: () => void qc.invalidateQueries(),
  })
}
