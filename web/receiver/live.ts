import type { Client } from 'openapi-fetch'
import type { paths } from '../src/api/schema'
import type { LiveChannel, LiveProgramme, LiveSession } from '../src/api/liveTV'
import type { LiveInfo, LiveProgrammeInfo } from '../src/cast/protocol'
import type { LiveTransport } from '../src/lib/liveTVController'
import { compatibleLiveProfile, liveFailure } from '../src/lib/liveTV'
import { withRequestTimeout } from '../src/lib/requestTimeout'

/**
 * Casting Live TV (PLAN-LIVE-TV.md §4.1): what the receiver needs beyond
 * main.ts's file plays, kept free of CAF so it can be tested.
 */

/** The server level whose cast tokens may play Live TV. */
export const LIVE_CAST_LEVEL = 14

/** How long the channel banner shows after a start or a change. */
export const BANNER_MS = 8000

/** How often a playing channel's session is kept; the lease is a minute. */
export const HEARTBEAT_MS = 10_000

/** How long a channel may take to start before the receiver gives up. */
export const START_TIMEOUT_MS = 30_000

/** A session's answer as an error the receiver can word: its reason. */
export class LiveRefused extends Error {
  constructor(readonly reason: string, message?: string) {
    super(message || reason)
  }
}

/**
 * The live session routes on the cast token, through the receiver's own
 * client: the session is the sender's (it is their sign-in), named after
 * the TV for Activity.
 */
export function castLiveTransport(api: Client<paths>, clientName: string, options: { profile: boolean; automatic: boolean; adaptive?: boolean } = { profile: false, automatic: false }): LiveTransport {
  // The server's refusals name a reason (tuners_busy, server_busy) or
  // say no in words: a 403 is no access, another 503 an offline tuner.
  const refused = (error: unknown, status: number) => {
    const detail = (error as { detail?: string } | undefined)?.detail ?? ''
    const reason = status === 403 ? 'not_allowed' : /^[a-z_]+$/.test(detail) ? detail : status === 503 ? 'tuner_unavailable' : ''
    return new LiveRefused(reason, detail || `The server refused (${status}).`)
  }
  return {
    async start(channelId, quality, requestId) {
      const selected = quality === 'auto' && !options.automatic ? '1080' : quality
      const res = await withRequestTimeout((signal) => api.POST('/api/live-tv/sessions', { body: {
        channelId, quality: selected, requestId, clientName,
        ...(options.adaptive && selected === "auto" ? { adaptive: true } : {}),
        ...(options.profile ? { profile: options.adaptive && selected === "auto" ? { ...compatibleLiveProfile, segmentFormats: ["fmp4", "mpegts"] } : compatibleLiveProfile } : {}),
        ...(selected === 'auto' && options.automatic ? { autoRecovery: true } : {}),
      }, signal }))
      if (!res.data) throw refused(res.error, res.response.status)
      return res.data
    },
    async stop(id) {
      // keepalive: a page going away still gives the tuner back.
      const res = await withRequestTimeout((signal) => api.DELETE('/api/live-tv/sessions/{id}', { params: { path: { id } }, keepalive: true, signal }))
      if (!res.response.ok) throw refused(res.error, res.response.status)
    },
    async status(id) {
      const res = await withRequestTimeout((signal) => api.GET('/api/live-tv/sessions/{id}', { params: { path: { id } }, signal }))
      if (!res.data) throw refused(res.error, res.response.status)
      return res.data
    },
    async heartbeat(id, paused, interacted, measurement) {
      const res = await withRequestTimeout((signal) => api.PUT('/api/live-tv/sessions/{id}', { params: { path: { id } }, body: { paused, interacted, ...measurement }, signal }))
      if (!res.data) throw refused(res.error, res.response.status)
      return res.data
    },
  }
}

/**
 * The URL with its access_token set to token. A cast token renews at half
 * its life, and the player fetches the playlist again every few seconds:
 * without this the playlist, and the segments it names with the token that
 * fetched it, would stop working when the first token ran out.
 */
export function withCurrentToken(url: string, token: string): string {
  const at = url.indexOf('access_token=')
  if (at < 0) return url
  const end = url.indexOf('&', at)
  return `${url.slice(0, at)}access_token=${encodeURIComponent(token)}${end < 0 ? '' : url.slice(end)}`
}

const programmeInfo = (p?: LiveProgramme | null): LiveProgrammeInfo | null =>
  p ? { title: p.title, episodeTitle: p.episodeTitle || undefined, start: p.start, end: p.end } : null

/** What the channel's state message says about it. */
export function liveInfo(channel: LiveChannel | undefined, channelId: string, quality: string, session: LiveSession | undefined, pausedAt: number | undefined, pauseSeconds: number, now: number): LiveInfo {
  const paused = pausedAt !== undefined
  return {
    channelId,
    number: channel?.number ?? '',
    name: channel?.name ?? '',
    quality,
    now: programmeInfo(channel?.now),
    next: programmeInfo(channel?.next),
    paused,
    // The server's count when it has one: it is what gives the tuner back.
    pauseLeftSeconds: paused ? (session?.pauseRemainingSeconds ?? Math.max(0, Math.ceil(pauseSeconds - (now - pausedAt) / 1000))) : null,
    reason: session?.reason || null,
  }
}

/**
 * What the state says about a channel whose start was refused, until the
 * next LOAD: the session is gone, but senders word why (tuners busy, no
 * signal) for that channel.
 */
export function refusedLiveInfo(channel: LiveChannel | undefined, channelId: string, quality: string, reason: string): LiveInfo {
  return { ...liveInfo(channel, channelId, quality, undefined, undefined, 0, 0), paused: false, pauseLeftSeconds: null, reason: reason || null }
}

/** When the channel list's Now has ended and the list should be asked for again. */
export function nowEnds(channel: LiveChannel | undefined): number | undefined {
  const end = channel?.now?.end
  return end ? Date.parse(end) : undefined
}

/** A failure on the TV, which can't be pressed: each says where to act. */
export type ReceiverFailure = { title: string; body: string; small: string }

/**
 * The TV's words for why a channel can't play (the web canvas's
 * LiveReceiver board), the web player's (liveFailure) for the rest.
 */
export function receiverFailure(reason: string, number: string): ReceiverFailure {
  const where = 'from your phone or browser'
  switch (reason) {
    case 'tuners_busy':
      return { title: 'All tuners are in use', body: `${number || 'This channel'} can’t start until a tuner is free.`, small: `Try again, or choose another channel, ${where}.` }
    case 'no_signal':
      return { title: number ? `No signal on ${number}` : 'No signal on this channel', body: 'The tuner couldn’t pick up this channel. Reception changes with the weather and the antenna’s position.', small: `Choose another channel ${where}.` }
    case 'tuner_unavailable':
      return { title: 'The tuner isn’t answering', body: 'This server couldn’t reach the antenna tuner. Live TV comes back when it does.', small: `Try again ${where}.` }
    case 'not_allowed': case 'access_changed': case 'access_revoked':
      return { title: 'Live TV isn’t available to you', body: 'An admin can let everyone watch Live TV on this server.', small: '' }
    default: {
      // Casting a recording still being written is not supported (PLAN-LIVE-TV-DVR.md §10).
      const f = liveFailure(reason, { watchRecording: false })
      return { title: f.title, body: f.detail, small: `Try again ${where}.` }
    }
  }
}
