import { withConfirm } from './withConfirm'
import { api, unwrap } from './client'
import type { components } from './schema'
import type { LiveTransport } from '../lib/liveTVController'
import { compatibleLiveProfile } from '../lib/liveTV'
import { prefersNativeHls } from '../lib/airplay'
import { withRequestTimeout } from '../lib/requestTimeout'

export type LiveChannel = components['schemas']['LiveTVChannel']
export type LiveSession = components['schemas']['LiveTVSessionView']
export type LiveSource = components['schemas']['LiveTVSource']
export type LiveQuality = 'auto' | '1080' | '720' | '480' | '360'
export type LiveProfile = components['schemas']['LiveTVLiveProfile']
export type LiveProgramme = components['schemas']['LiveTVProgramme']
export type LiveGuide = components['schemas']['LiveTVGuide']
export type LiveGuideChannel = components['schemas']['LiveTVGuideChannel']
export type LiveGuideSettings = components['schemas']['LiveTVGuideSettings']
export type LiveGuideMatch = components['schemas']['LiveTVGuideChannelMatch']

export const testLiveSource = (address: string) => withConfirm(async () => api.POST('/api/settings/live-tv/test', { body: { address } }).then(unwrap), 'test this tuner')
export const addLiveSource = (address: string, name: string) => withConfirm(async () => api.POST('/api/settings/live-tv/sources', { body: { address, name } }).then(unwrap), 'add this tuner')
export const updateLiveSource = (source: LiveSource, changes: Partial<Pick<LiveSource, 'address' | 'name' | 'enabled'>>) =>
  withConfirm(async () => api.PUT('/api/settings/live-tv/sources/{id}', { params: { path: { id: source.id } }, body: { address: source.address, name: source.name, enabled: source.enabled, ...changes } }).then(unwrap), 'move this tuner')
export const refreshLiveSource = (id: string) => api.POST('/api/settings/live-tv/sources/{id}/refresh', { params: { path: { id } } }).then(unwrap)
export const removeLiveSource = (id: string) => api.DELETE('/api/settings/live-tv/sources/{id}', { params: { path: { id } } }).then(unwrap)
export const enableLiveChannel = (id: string, enabled: boolean) => api.PUT('/api/settings/live-tv/channels/{id}', { params: { path: { id } }, body: { enabled } }).then(unwrap)
export const setLiveAccess = (access: 'none' | 'admins' | 'everyone') => api.PUT('/api/settings/live-tv', { body: { access } }).then(unwrap)
export const setLiveGuide = (enabled: boolean, tmdb: boolean, email: string) => api.PUT('/api/settings/live-tv/guide', { body: { enabled, tmdb, email } }).then(unwrap)
export const refreshLiveGuide = () => api.POST('/api/settings/live-tv/guide/refresh').then(unwrap)
export const setLiveGuideChannel = (id: string, listings: boolean) => api.PUT('/api/settings/live-tv/guide/channels/{id}', { params: { path: { id } }, body: { listings } }).then(unwrap)
export const stopLiveAsAdmin = (id: string) => api.DELETE('/api/live-tv/activity/{id}', { params: { path: { id } } }).then(unwrap)

export const liveTransport: LiveTransport = {
  async start(channelId, quality, requestId) {
    // A network retry keeps the original request ID. An ambiguous response
    // must never allocate a second tuner for the same start.
    const adaptive = quality === "auto" && !prefersNativeHls()
    const start = () => withRequestTimeout(async (signal) => unwrap(await api.POST('/api/live-tv/sessions', { body: {
      adaptive, channelId, quality, requestId, clientName: 'Web browser', profile: adaptive ? { ...compatibleLiveProfile, segmentFormats: ["fmp4", "mpegts"] } : compatibleLiveProfile,
      ...(quality === 'auto' ? { autoRecovery: true } : {}),
    }, signal })))
    try { return await start() } catch (error) {
      if (error instanceof TypeError || (error instanceof DOMException && error.name === 'TimeoutError')) return start()
      throw error
    }
  },
  stop: (id) => withRequestTimeout(async (signal) => { unwrap(await api.DELETE('/api/live-tv/sessions/{id}', { params: { path: { id } }, keepalive: true, signal })) }),
  status: (id) => withRequestTimeout((signal) => api.GET('/api/live-tv/sessions/{id}', { params: { path: { id } }, signal }).then(unwrap)),
  heartbeat: (id, paused, interacted, measurement) => withRequestTimeout((signal) => api.PUT('/api/live-tv/sessions/{id}', { params: { path: { id } }, body: { paused, interacted, ...measurement }, signal }).then(unwrap)),
}
