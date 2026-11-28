import { api, unwrap, type ApiClient } from './client'
import type { TimingSaver } from '../lib/play/subtitleTiming'

/**
 * Saves a subtitle track's timing on the server (API level 35): the
 * person's own, or for an admin, everyone's; Reset removes the person's
 * own. The TV app passes its own client for another server.
 */
export function subtitleTimingSaver(client: () => ApiClient = () => api): TimingSaver {
  return {
    save: async (t, timingMs, everyone) => unwrap(await client().PUT('/api/files/{id}/subtitles/{track}/timing', {
      params: { path: { id: t.fileId, track: t.trackId } },
      body: everyone ? { timingMs, everyone: true } : { timingMs },
    })),
    reset: async (t) => unwrap(await client().DELETE('/api/files/{id}/subtitles/{track}/timing', {
      params: { path: { id: t.fileId, track: t.trackId } },
    })),
  }
}
