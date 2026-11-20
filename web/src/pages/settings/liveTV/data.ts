import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { liveTVActivityQuery, liveTVGuideSettingsQuery, liveTVRecordingSettingsQuery, liveTVSettingsQuery, librariesQuery, serverQuery } from '../../../api/queries'
import type { LiveGuideSettings } from '../../../api/liveTV'
import { liveGuideStatus } from '../../../lib/liveTVSettingsRules'
import { GUIDE_API_LEVEL } from '../../../lib/liveGuide'
import { RECORDING_API_LEVEL } from '../../../lib/liveRecordings'
import { settingsHref } from '../../../lib/settingsRoutes'

/** An address under Settings › Live TV. */
export const tvHref = (...segments: (string | undefined)[]) => settingsHref('live-tv', ...segments)

/** Every write refreshes navigation, setup and settings, including a failed lineup refresh. */
export function useLiveMutation<T, V>(mutationFn: (variables: V) => Promise<T>) {
  const client = useQueryClient()
  return useMutation({ mutationFn, onSettled: () => client.invalidateQueries({ queryKey: ['live-tv'] }) })
}

/** Saves the listings' settings and keeps the reply, so the next step reads them at once. */
export function useGuideMutation<V>(mutationFn: (variables: V) => Promise<LiveGuideSettings>) {
  const client = useQueryClient()
  return useMutation({
    mutationFn,
    onSuccess: (data) => client.setQueryData(liveTVGuideSettingsQuery.queryKey, data),
    onSettled: () => client.invalidateQueries({ queryKey: ['live-tv'] }),
  })
}

/** What the server can do: listings from API 13, recordings from API 19. */
export function useLiveLevels() {
  const server = useQuery(serverQuery)
  const level = server.data?.apiLevel ?? 0
  return { guide: level >= GUIDE_API_LEVEL, recordings: level >= RECORDING_API_LEVEL, pending: server.isPending }
}

/** The guide's settings, asked again every few seconds while a fetch runs. */
export function useGuideSettings(enabled = true) {
  return useQuery({ ...liveTVGuideSettingsQuery, enabled, refetchInterval: (query) => (liveGuideStatus(query.state.data?.status.state ?? '').busy ? 3000 : false) })
}

export function useLiveSettings() {
  return useQuery(liveTVSettingsQuery)
}

/** People watching and recordings running now (Activity, which admins already read). */
export function useLiveActivity() {
  const activity = useQuery(liveTVActivityQuery)
  return { sessions: activity.data?.items ?? [], recordings: activity.data?.recordings ?? [], query: activity }
}

export function useRecordingSettings(enabled = true) {
  return useQuery({ ...liveTVRecordingSettingsQuery, enabled })
}

export function useLibraries() {
  const libraries = useQuery(librariesQuery)
  const items = libraries.data?.items ?? []
  return { query: libraries, tv: items.filter((l) => l.type === 'tv'), movies: items.filter((l) => l.type === 'movie') }
}

// A one-step task ends with a banner on the page it returns to. The
// banner waits here for that page, which shows it once.
type Flash = { title: string; body?: string }
const flashes = new Map<string, Flash>()
export function setFlash(page: string, flash: Flash) { flashes.set(page, flash) }
export function peekFlash(page: string) { return flashes.get(page) ?? null }
export function clearFlash(page: string) { flashes.delete(page) }
