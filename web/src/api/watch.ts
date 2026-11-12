import { useMutation, useQueryClient, type QueryClient } from '@tanstack/react-query'
import { api, unwrap } from './client'
import type { components } from './schema'
import { queryEventInvalidator } from './queryEventInvalidator'

export type Progress = components['schemas']['Progress']
export type PlayState = 'playing' | 'paused' | 'buffering' | 'stopped' | 'closed'
export type QualityDirective = components['schemas']['ActivityDirective']

/** A heartbeat: the play, its state, and what the player measured (PLAN-AUTO-QUALITY.md §3.1). */
export type Beat = {
  renditionHeight?: number
  renditionBitrate?: number
  playId?: string
  state: PlayState
  bufferedMs?: number
  /** hls.js's estimate of the link; absent where there is none. */
  throughputBps?: number
  throughputAgeMs?: number
  switchGeneration?: number
  switchFailedGeneration?: number
  stalls?: number
  stallMs?: number
  /** Sent once. */
  startupMs?: number
}

const ms = (n: number | undefined) => (n === undefined || !Number.isFinite(n) ? undefined : Math.max(0, Math.round(n)))

/**
 * Reports a position, and with a play ID a heartbeat for activity: the reply
 * may tell the player to stop or to lower its quality, or give it a new play
 * ID to use. keepalive so the last report survives page unload.
 */
export async function reportProgress(fileId: number, positionMs: number, durationMs: number, beat?: Beat, recordingId?: string): Promise<Progress | undefined> {
  try {
    const res = await fetch(recordingId ? `/api/live-tv/recordings/${encodeURIComponent(recordingId)}/progress` : `/api/files/${fileId}/progress`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        positionMs: Math.max(0, Math.floor(positionMs)),
        durationMs: Math.max(0, Math.floor(durationMs)),
        ...(beat?.playId
          ? {
              playId: beat.playId,
              state: beat.state,
              bufferedMs: Math.max(0, Math.floor(beat.bufferedMs ?? 0)),
              throughputBps: beat.throughputBps ? ms(beat.throughputBps) : undefined,
              throughputAgeMs: ms(beat.throughputAgeMs),
              renditionHeight: ms(beat.renditionHeight),
              renditionBitrate: ms(beat.renditionBitrate),
              switchGeneration: ms(beat.switchGeneration),
              switchFailedGeneration: ms(beat.switchFailedGeneration),
              stalls: ms(beat.stalls),
              stallMs: ms(beat.stallMs),
              startupMs: ms(beat.startupMs),
            }
          : {}),
      }),
      keepalive: true,
      credentials: 'same-origin',
    })
    return res.ok ? ((await res.json()) as Progress) : undefined
  } catch {
    return undefined
  }
}

const watchKeys = new Set(['home', 'items', 'rows', 'lists', 'movie', 'show', 'search'])

/** Refetches what shows progress and watched marks: rows, lists and detail pages. */
export function invalidateWatch(qc: QueryClient) {
  void qc.invalidateQueries({ predicate: ({ queryKey }) => watchKeys.has(String(queryKey[0])) })
}

/** Imports commit in batches and can publish many events while a page is
 * loading. Reuse its current request, then refresh after the last commit so
 * its earlier snapshot cannot win. Ordinary local mutations remain immediate. */
export function watchEventInvalidator(qc: QueryClient): () => void {
  return queryEventInvalidator(qc, { predicate: ({ queryKey }) => watchKeys.has(String(queryKey[0])) })
}

export function useMarkWatched() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async ({ type, id, watched }: { type: 'movie' | 'episode'; id: number; watched: boolean }) =>
      watched
        ? unwrap(await api.POST('/api/watch/{type}/{id}/watched', { params: { path: { type, id } } }))
        : unwrap(await api.DELETE('/api/watch/{type}/{id}/watched', { params: { path: { type, id } } })),
    onSuccess: () => invalidateWatch(qc),
  })
}

export function useMarkSeasonWatched() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async ({ showId, number, watched }: { showId: number; number: number; watched: boolean }) =>
      watched
        ? unwrap(await api.POST('/api/shows/{id}/seasons/{number}/watched', { params: { path: { id: showId, number } } }))
        : unwrap(await api.DELETE('/api/shows/{id}/seasons/{number}/watched', { params: { path: { id: showId, number } } })),
    onSuccess: () => invalidateWatch(qc),
  })
}

/** Every episode of the show on disk, as the season's mark does for one season. */
export function useMarkShowWatched() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async ({ showId, watched }: { showId: number; watched: boolean }) =>
      watched
        ? unwrap(await api.POST('/api/shows/{id}/watched', { params: { path: { id: showId } } }))
        : unwrap(await api.DELETE('/api/shows/{id}/watched', { params: { path: { id: showId } } })),
    onSuccess: () => invalidateWatch(qc),
  })
}
