import { infiniteQueryOptions, keepPreviousData, queryOptions, useMutation, useQueryClient } from '@tanstack/react-query'
import { QUALITIES } from '../lib/quality'
import { higherStep } from '../lib/play/recovery'
import { api, unwrap } from './client'
import type { components } from './schema'

export type LivePlay = components['schemas']['ActivityLivePlay']
export type LiveActivity = components['schemas']['LiveActivity']
export type HistoryRow = components['schemas']['ActivityHistoryRow']
export type HistoryPage = components['schemas']['ActivityPage']
export type PlayItem = components['schemas']['ActivityItem']
export type MediaSide = components['schemas']['ActivityMediaInfo']
export type StatItem = components['schemas']['ActivityStatItem']
export type Cards = components['schemas']['ActivityCards']
export type Series = components['schemas']['ActivitySeries']
export type Heatmap = components['schemas']['ActivityHeatmap']
export type UserSummary = components['schemas']['ActivityUserSummary']
export type UserDetail = components['schemas']['ActivityUserDetail']
export type PlayAuto = components['schemas']['ActivityPlayAuto']

/** "720p · 4 Mbps" for a quality step, as the player's menu names it. */
export function qualityLabel(id?: string): string {
  if (!id || id === 'original') return 'Original'
  return QUALITIES.find((q) => q.id === id)?.label ?? `${id}p`
}

/** A play's quality: a fixed step, or Automatic and the step it is at. */
export function streamQuality(quality: string | undefined, auto?: PlayAuto): string {
  if (quality !== 'auto') return qualityLabel(quality)
  return `Automatic · ${qualityLabel(auto?.step)}${auto?.floor ? ', the lowest, and still stalling' : ''}`
}

/** Automatic's confirmed changes and in-flight outcomes as detail rows. */
export function autoRows(auto?: PlayAuto): [string, string][] {
  if (!auto) return []
  return auto.switches.map((s) => {
    const at = new Date(s.at).toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' })
    const step = `${qualityLabel(s.from)} → ${qualityLabel(s.to)} at ${at} (${s.reason})`
    if (!auto.on) return ['Automatic', `would have lowered ${step}`]
    const direction = higherStep(s.from, s.to) ? 'Raised' : 'Lowered'
    const status = s.outcome === 'proposed' ? 'Proposed' : s.outcome === 'failed' ? 'Failed' : s.outcome === 'canceled' ? 'Canceled' : direction
    return [status, step]
  })
}

/** What the player measured: its link, Automatic's estimate, stalls and
 * startup time. */
export function connectionRows(p: LivePlay): [string, string][] {
  const { throughput = 0, stalls = 0, stallMs = 0, startupMs = 0 } = p
  const estimate = p.auto?.estimate ?? 0
  const parts: string[] = []
  if (throughput > 0) parts.push(`${formatRate(throughput)} measured by the player`)
  if (estimate > 0 && estimate !== throughput) parts.push(`${formatRate(estimate)} estimated`)
  if (stalls > 0) parts.push(`${stalls} ${stalls === 1 ? 'stall' : 'stalls'}${stallMs >= 1000 ? `, ${Math.round(stallMs / 1000)} s` : ''}`)
  if (startupMs > 0) parts.push(`started in ${(startupMs / 1000).toFixed(1)} s`)
  return parts.length > 0 ? [['Connection', parts.join(' · ')]] : []
}

/** Everything under ['activity'] refetches when a play ends. */
export const liveActivityQuery = queryOptions({
  queryKey: ['activity', 'live'],
  queryFn: async () => unwrap(await api.GET('/api/activity')),
  // Server events say when plays change; positions move on their own, so
  // poll gently too.
  refetchInterval: 15_000,
})

/** History filters, as they appear in the URL. */
export type HistoryFilters = {
  q?: string
  user?: number
  device?: number
  type?: 'movie' | 'episode' | 'file'
  mode?: 'direct' | 'remux' | 'audio' | 'transcode'
  network?: 'local' | 'tailnet' | 'remote'
  from?: string
  to?: string
  completed?: 'true' | 'false'
  movie?: number
  show?: number
  episode?: number
  grouped?: boolean
}

export const historyQuery = (f: HistoryFilters, limit = 50) =>
  infiniteQueryOptions({
    queryKey: ['activity', 'history', f, limit],
    // Typing aborts the previous request (the query key changes and the
    // signal fires); the old rows stay on screen until the new ones arrive.
    queryFn: async ({ pageParam, signal }) =>
      unwrap(
        await api.GET('/api/history', {
          params: { query: { ...f, grouped: f.grouped ?? true, limit, cursor: pageParam || undefined } },
          signal,
        }),
      ),
    initialPageParam: '',
    getNextPageParam: (last) => last.nextCursor || undefined,
    placeholderData: keepPreviousData,
  })

export const historyGroupQuery = (groupId: number) =>
  queryOptions({
    queryKey: ['activity', 'group', groupId],
    queryFn: async () => unwrap(await api.GET('/api/history/{groupId}', { params: { path: { groupId } } })),
  })

/** The CSV export URL for the same filters. */
export function historyCSV(f: HistoryFilters): string {
  const p = new URLSearchParams()
  for (const [k, v] of Object.entries({ ...f, grouped: f.grouped ?? true })) {
    if (v !== undefined && v !== '') p.set(k, String(v))
  }
  return `/api/history.csv?${p}`
}

export type StatsParams = { days: number; by: 'plays' | 'time'; user?: number; library?: number }

export const cardsQuery = (p: StatsParams) =>
  queryOptions({
    queryKey: ['activity', 'cards', p],
    queryFn: async () => unwrap(await api.GET('/api/stats/cards', { params: { query: p } })),
    placeholderData: keepPreviousData,
  })

export const seriesQuery = (p: StatsParams & { split: 'type' | 'mode' | 'user' | 'network'; bucket: 'day' | 'month' }) =>
  queryOptions({
    queryKey: ['activity', 'series', p],
    queryFn: async () => unwrap(await api.GET('/api/stats/series', { params: { query: { days: p.days, by: p.by, split: p.split, bucket: p.bucket, user: p.user } } })),
    placeholderData: keepPreviousData,
  })

export const heatmapQuery = (p: { days: number; by: 'plays' | 'time'; user?: number }) =>
  queryOptions({
    queryKey: ['activity', 'heatmap', p],
    queryFn: async () => unwrap(await api.GET('/api/stats/heatmap', { params: { query: p } })),
    placeholderData: keepPreviousData,
  })

export const activityUsersQuery = queryOptions({
  queryKey: ['activity', 'users'],
  queryFn: async () => unwrap(await api.GET('/api/activity/users')),
})

export const activityUserQuery = (id: number) =>
  queryOptions({
    queryKey: ['activity', 'users', id],
    queryFn: async () => unwrap(await api.GET('/api/activity/users/{id}', { params: { path: { id } } })),
  })

export function useStopPlay() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async ({ playId, message }: { playId: string; message: string }) =>
      unwrap(await api.POST('/api/activity/{playId}/stop', { params: { path: { playId } }, body: { message } })),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['activity'] }),
  })
}

export function useDeleteHistory() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async (groups: number[]) => unwrap(await api.DELETE('/api/history', { body: { groups } })),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['activity'] }),
  })
}

export function useSetRecordHistory() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async ({ id, on }: { id: number; on: boolean }) => unwrap(await api.PATCH('/api/users/{id}', { params: { path: { id } }, body: { recordHistory: on } })),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['users'] })
      void qc.invalidateQueries({ queryKey: ['activity', 'users'] })
    },
  })
}

export const MODE_LABELS: Record<string, string> = {
  direct: 'Direct play',
  remux: 'Direct stream',
  audio: 'Audio transcode',
  transcode: 'Transcode',
}

export const NETWORK_LABELS: Record<string, string> = { local: 'Home', tailnet: 'Tailnet', remote: 'Internet' }

/** "Show · S01E02 Title" for an episode, else "Title (Year)". */
export function itemTitle(it: PlayItem): { title: string; subtitle: string } {
  if (it.type === 'episode') {
    const se = it.seasonNumber != null && it.episodeNumber != null ? `S${String(it.seasonNumber).padStart(2, '0')}E${String(it.episodeNumber).padStart(2, '0')}` : ''
    return { title: it.showTitle || it.title, subtitle: [se, it.title].filter(Boolean).join(' · ') }
  }
  return { title: it.title, subtitle: it.year ? String(it.year) : it.type === 'file' ? 'Not matched' : '' }
}

/** Where an item's page is, if it has one: a movie's, or an episode's show. */
export function itemLink(it: PlayItem): { to: '/movies/$movieId'; params: { movieId: string } } | { to: '/shows/$showId'; params: { showId: string } } | null {
  if (it.movieId) return { to: '/movies/$movieId', params: { movieId: String(it.movieId) } }
  if (it.showId) return { to: '/shows/$showId', params: { showId: String(it.showId) } }
  return null
}

/** An episode's own page, for its "S01E02 · Title" line (PLAN-EPISODE-PAGE.md decision 1). */
export function episodeLink(it: PlayItem) {
  if (it.type !== 'episode' || !it.showId || it.seasonNumber == null || !it.episodeId) return null
  return {
    to: '/shows/$showId/seasons/$number/episodes/$episodeId' as const,
    params: { showId: String(it.showId), number: String(it.seasonNumber), episodeId: String(it.episodeId) },
  }
}

/** Bits per second as "12.3 Mbps". */
export function formatRate(bps: number): string {
  if (bps <= 0) return '0 Mbps'
  if (bps < 1_000_000) return `${Math.round(bps / 1000)} kbps`
  return `${(bps / 1_000_000).toFixed(bps < 10_000_000 ? 1 : 0)} Mbps`
}

/** Milliseconds as "3 h 12 min" or "45 min". */
export function formatPlayed(ms: number): string {
  const min = Math.round(ms / 60_000)
  if (min < 1) return ms > 0 ? '<1 min' : '0 min'
  const d = Math.floor(min / 1440)
  const h = Math.floor((min % 1440) / 60)
  const m = min % 60
  if (d > 0) return `${d} d ${h} h`
  return h > 0 ? `${h} h${m ? ` ${m} min` : ''}` : `${m} min`
}
