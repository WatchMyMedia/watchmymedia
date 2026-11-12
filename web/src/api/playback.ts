import { api, unwrap } from './client'
import type { components } from './schema'
import { profile } from '../lib/capabilities'
import { applyQuality, type Quality } from '../lib/quality'
import { clientInfo } from '../lib/device'
import { requestedPlaylist } from '../lib/play/stream'

export type Decision = components['schemas']['Decision']
export type PlaybackRequest = components['schemas']['Request']

/** A recording that is just starting: its playback answers 409 for a few seconds. */
export class RecordingNotReady extends Error {
  constructor(message: string, readonly retryAfter: string | null) { super(message) }
}

export async function startPlayback(
  fileId: number,
  opts: { startMs?: number; audioIndex?: number; subtitleId?: string; subtitleMode?: 'auto' | 'off'; burnIn?: boolean; quality?: Quality; playId?: string; native?: boolean; recordingId?: string; adaptive?: boolean; autoRecovery?: boolean; switchGeneration?: number } = {},
): Promise<Decision> {
  const { quality, native, recordingId, ...rest } = opts
  const base = await profile(native)
  // Completed files use a seekable VOD timeline in every browser. A growing
  // recording keeps the event playlist until its duration is final.
  const body: PlaybackRequest = { profile: quality ? applyQuality(base, quality) : base, quality: quality?.id, client: clientInfo(), playlist: requestedPlaylist(recordingId), ...rest }
  if (recordingId) {
    const res = await api.POST('/api/live-tv/recordings/{id}/playback', { params: { path: { id: recordingId } }, body })
    if (res.response.status === 409) {
      const detail = typeof res.error === 'object' && res.error && 'detail' in res.error ? String((res.error as { detail: unknown }).detail) : 'The recording is just starting; try again in a moment'
      throw new RecordingNotReady(detail, res.response.headers.get('Retry-After'))
    }
    return unwrap(res)
  }
  return unwrap(await api.POST('/api/files/{id}/playback', { params: { path: { id: fileId } }, body }))
}

export function stopPlayback(sessionId: string) {
  return api.DELETE('/api/playback/{session}', { params: { path: { session: sessionId } } })
}

export type SubtitleSearch = components['schemas']['SubtitleSearch']
export type OnlineSubtitle = components['schemas']['OnlineSubtitle']
export type SubtitleTrack = components['schemas']['SubtitleTrack']

/** Subtitles found online for a file (cached on the server for a day, so cheap to repeat). */
export async function searchSubtitles(fileId: number): Promise<SubtitleSearch> {
  return unwrap(await api.GET('/api/files/{id}/subtitles/search', { params: { path: { id: fileId } } }))
}

/** Downloads an online result for the file (free if it was before) and returns its track. */
export async function downloadSubtitle(fileId: number, resultId: string): Promise<SubtitleTrack> {
  return unwrap(await api.POST('/api/files/{id}/subtitles/download', { params: { path: { id: fileId } }, body: { resultId } }))
}

/** Background-only; the server bounds and deduplicates this active play's attempt. */
export async function automaticSubtitle(fileId: number, playId: string, cancel = false) {
  return unwrap(await api.POST('/api/files/{id}/subtitles/automatic', { params: { path: { id: fileId } }, body: { playId, cancel } }))
}
