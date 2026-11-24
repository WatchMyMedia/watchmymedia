import type { QueryClient } from '@tanstack/react-query'
import { api, unwrap } from './client'
import type { components } from './schema'

type ShowTracksInput = components['schemas']['ShowTracksInputBody']

/** Keeps a pick of sound or subtitles for the show (API level 34); its page then shows it. */
export async function setShowTracks(qc: QueryClient, showId: number, body: ShowTracksInput): Promise<void> {
  unwrap(await api.PUT('/api/shows/{id}/tracks', { params: { path: { id: showId } }, body }))
  void qc.invalidateQueries({ queryKey: ['show', showId], exact: true })
}

/** Forgets them: the show's episodes start with the account's language settings again. */
export async function forgetShowTracks(qc: QueryClient, showId: number): Promise<void> {
  unwrap(await api.DELETE('/api/shows/{id}/tracks', { params: { path: { id: showId } } }))
  void qc.invalidateQueries({ queryKey: ['show', showId], exact: true })
}
