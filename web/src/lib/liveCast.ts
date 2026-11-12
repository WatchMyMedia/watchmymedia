import type { LiveChannel, LiveProgramme } from '../api/liveTV'
import { FEATURE_LIVE, PROTOCOL_VERSION, type LiveInfo, type LiveProgrammeInfo, type LoadData, type StateMessage } from '../cast/protocol'
import { duration, episodeLine, nowAndNext, progress } from './liveGuide'
import { liveFailure, liveQuality } from './liveTV'
import { QUALITIES, type Quality } from './quality'

/**
 * Casting a Live TV channel from this browser (PLAN-LIVE-TV.md §4.1, L10c):
 * when to offer it, what the remote shows, and the LOAD it sends. The
 * receiver plays the channel on the sender's sign-in; this page is a remote.
 */

/** The server level whose cast tokens may play Live TV (L10a). */
export const LIVE_CAST_LEVEL = 14

/** The server lets this person cast a channel. */
export function serverCastsLive(apiLevel: number | undefined, capabilities: { cast?: boolean } | undefined): boolean {
  return (apiLevel ?? 0) >= LIVE_CAST_LEVEL && !!capabilities?.cast
}

/** The connected receiver plays Live TV; undefined until its first state message. */
export function receiverPlaysLive(state: StateMessage | undefined): boolean | undefined {
  if (!state) return undefined
  return !!state.features?.includes(FEATURE_LIVE)
}

/**
 * How the live player behaves: plays here (local), waits for the TV to
 * say what it can do (waiting), plays here and says the TV can't play
 * Live TV yet (notLive), or becomes the TV's remote (remote).
 */
export type LiveCastMode = 'local' | 'waiting' | 'notLive' | 'remote'

export function liveCastMode(o: { connected: boolean; server: boolean; receiver?: StateMessage; waitedOut?: boolean }): LiveCastMode {
  if (!o.connected || !o.server) return 'local'
  const plays = receiverPlaysLive(o.receiver)
  if (plays === undefined) return o.waitedOut ? 'notLive' : 'waiting'
  return plays ? 'remote' : 'notLive'
}

/** How long the live player waits for a connected TV's first state before playing here. */
export const RECEIVER_WAIT_MS = 3000

const unreachable = (host: string) =>
  host === 'localhost' || host.endsWith('.localhost') || host.endsWith('.ts.net') || /^127\.|^\[?::1\]?$/.test(host) || /^100\.(6[4-9]|[7-9]\d|1[01]\d|12[0-7])\./.test(host)

/** Addresses the receiver tries: the cast token's, then this page's own unless a TV can't reach it. */
export function castServerUrls(tokenUrls: string[], origin: string, hostname: string): string[] {
  const urls = [...tokenUrls]
  if (!urls.includes(origin) && !unreachable(hostname)) urls.push(origin)
  return urls
}

/** A channel's LOAD customData: the channel and the sender's Live TV quality instead of a file. */
export function liveLoadData(o: {
  serverId: string
  urls: string[]
  token: string
  tokenExpiresAt?: string
  channelId: string
  quality: string | null | undefined
  deviceName?: string
  senderName: string
}): LoadData {
  return {
    v: PROTOCOL_VERSION,
    server: { id: o.serverId, urls: o.urls },
    token: o.token,
    tokenExpiresAt: o.tokenExpiresAt,
    live: { channelId: o.channelId, quality: liveQuality(o.quality ?? null) },
    device: { name: o.deviceName },
    sender: { name: o.senderName },
  }
}

/** The receiver's channel, when it is on one: playing, tuning, or refused. */
const tvChannel = (r: StateMessage | undefined) => (r?.live && r.phase !== 'idle' && r.phase !== 'ended' ? r.live.channelId : undefined)

/**
 * After a state message: whether the channel this browser asked for is
 * still on its way. It is no longer once the TV is on it, the TV moved to
 * another channel, or the TV newly failed.
 */
export function settlePending(pending: string | undefined, prev: StateMessage | undefined, next: StateMessage): string | undefined {
  if (!pending) return undefined
  const on = next.live?.channelId
  if (on === pending && next.phase !== 'error') return undefined
  if (next.phase === 'error' && prev?.phase !== 'error') return undefined
  if (on && prev?.live?.channelId && on !== prev.live.channelId) return undefined
  return pending
}

/**
 * What the remote does for the channel in the address (route):
 * - nothing while the TV is on it or it is on its way;
 * - cast it when this page hasn't driven it yet (the viewer chose it, as
 *   Watch in the guide does);
 * - follow the TV when it moved on from the channel this page drives
 *   (Ch+ or Ch−, the channel list, another sender).
 * The target is the channel this page last cast, followed or found the TV on.
 */
export type RemoteAction = { kind: 'none' } | { kind: 'adopt' } | { kind: 'cast' } | { kind: 'follow'; channelId: string }

export function liveRemoteAction(o: { route: string; receiver?: StateMessage; pending?: string; target?: string }): RemoteAction {
  const on = tvChannel(o.receiver)
  if (on === o.route) return o.target === o.route ? { kind: 'none' } : { kind: 'adopt' }
  if (o.pending === o.route) return { kind: 'none' }
  if (o.target !== o.route) return { kind: 'cast' }
  if (on && !o.pending) return { kind: 'follow', channelId: on }
  return { kind: 'none' }
}

/** The remote's screens, from the LiveCast board's states. */
export type RemoteView =
  | { kind: 'tuning'; channelId: string }
  | { kind: 'playing'; channelId: string; paused: boolean; pauseLeftMinutes?: number }
  | { kind: 'stillWatching'; channelId: string }
  | { kind: 'stopped'; channelId: string }
  | { kind: 'failed'; channelId: string; reason?: string; message?: string }
  | { kind: 'ended'; channelId: string }

export function liveRemoteView(o: { connected: boolean; route: string; receiver?: StateMessage; pending?: string; target?: string; error?: string }): RemoteView {
  const r = o.receiver
  if (!o.connected) return { kind: 'ended', channelId: o.route }
  // An ask on its way, Try again after a refusal included.
  if (o.pending && (o.pending !== tvChannel(r) || (r?.phase !== 'loading' && r?.phase !== 'playing'))) return { kind: 'tuning', channelId: o.pending }
  if (o.error && r?.phase !== 'loading' && r?.phase !== 'playing') return { kind: 'failed', channelId: o.route, message: o.error }
  const channelId = r?.live?.channelId ?? o.route
  switch (r?.phase) {
    case 'loading':
      if (r.live) return { kind: 'tuning', channelId }
      break
    case 'playing':
      if (!r.live) break
      if (r.stillWatching) return { kind: 'stillWatching', channelId }
      return { kind: 'playing', channelId, paused: !!r.live.paused, pauseLeftMinutes: r.live.paused && r.live.pauseLeftSeconds != null ? Math.max(1, Math.ceil(r.live.pauseLeftSeconds / 60)) : undefined }
    case 'stopped':
      if (r.live) return { kind: 'stopped', channelId }
      break
    case 'error':
      // An older receiver drops the channel when its start is refused.
      if (r.live || o.target === o.route) return { kind: 'failed', channelId, reason: r.live?.reason ?? undefined, message: r.error ?? undefined }
      break
  }
  // Nothing on the TV: this page's channel ended there, or it is about to be cast.
  return o.target === o.route ? { kind: 'ended', channelId: o.route } : { kind: 'tuning', channelId: o.route }
}

/** One of the remote's cards: its words and which buttons it has. */
export type RemoteCard = {
  title: string
  body: string
  message?: string
  /** The cast icon in gold, for casting ended and still watching; otherwise the warning in orange. */
  gold?: boolean
  castIcon?: boolean
  retry?: boolean
  keep?: boolean
  watchHere?: boolean
  /** Back to the guide as the primary button. */
  guideFirst?: boolean
  guide?: boolean
  stop?: boolean
}

/** The LiveCast board's cards, for a channel ("7.1") and the TV's name. `recording`: the server records, so a recording can take a tuner. */
export function remoteCard(view: RemoteView, o: { number: string; name: string; device: string; stopMessage?: string | null; recording?: boolean }): RemoteCard | undefined {
  const ch = o.number || 'this channel'
  switch (view.kind) {
    case 'stillWatching':
      return { title: `Still watching ${ch} on ${o.device}?`, body: 'Nothing has been pressed for 4 hours, so the TV is asking before it keeps the tuner.', keep: true, stop: true, gold: true }
    case 'stopped':
      return { title: 'Stopped from Activity', body: `An admin stopped Live TV on ${o.device} and freed its tuner.`, message: o.stopMessage || undefined, guideFirst: true, stop: true }
    case 'ended':
      return { title: 'Casting ended', body: `${[o.number, o.name].filter(Boolean).join(' ') || 'The channel'} stopped on ${o.device}, and its tuner is free.`, watchHere: true, guide: true, castIcon: true, gold: true }
    case 'failed': {
      if (view.reason === 'tuners_busy') return { title: 'All tuners are in use', body: `${o.device} can’t start ${ch} until a tuner is free.${o.recording ? ' A scheduled recording can release a tuner someone is watching on.' : ''}`, retry: true, guide: true }
      if (view.reason === 'not_allowed' || view.reason === 'access_changed' || view.reason === 'access_revoked') return { title: 'Live TV isn’t available to you', body: 'An admin can let everyone watch Live TV on this server.', guide: true }
      if (view.reason) {
        // The TV can't play a recording still being written (PLAN-LIVE-TV-DVR.md §10).
        const f = liveFailure(view.reason, { watchRecording: false })
        return { title: f.title, body: f.detail, retry: true, guide: true }
      }
      return { title: `${o.device} couldn’t play ${ch}`, body: view.message || 'Try again, or choose another channel.', retry: true, guide: true }
    }
  }
  return undefined
}

/** A programme the TV names, as the guide's type, for a channel this page's list doesn't have. */
const programme = (p: LiveProgrammeInfo | null | undefined, channelId: string): LiveProgramme | undefined =>
  p ? { id: `${channelId}@${p.start}`, title: p.title, episodeTitle: p.episodeTitle, start: p.start, end: p.end } : undefined

/**
 * The channel the TV plays, for the banner: this page's own channel list
 * (it has the logo, pictures and episode numbers), else what the TV says.
 */
export function remoteChannel(channelId: string, live: LiveInfo | null | undefined, channels: LiveChannel[] | undefined): LiveChannel {
  const listed = channels?.find((c) => c.id === channelId)
  if (listed) return listed
  const info = live?.channelId === channelId ? live : undefined
  return { id: channelId, sourceId: '', number: info?.number ?? '', name: info?.name ?? '', enabled: true, available: true, now: programme(info?.now, channelId), next: programme(info?.next, channelId) }
}

/** The quality menu on a capable receiver, labelled as the file player's. */
export const LIVE_CAST_QUALITIES: Quality[] = (['auto', '1080', '720', '480', '360'] as const).map((id) => QUALITIES.find((q) => q.id === id) ?? { id, label: `${id}p` })

/** Seconds the TV is behind the live edge, from MEDIA_STATUS's live seekable range. */
export function behindLive(range: { start?: number; end?: number } | undefined, currentTime: number): number {
  if (range?.end === undefined) return 0
  return Math.max(0, Math.round(range.end - currentTime))
}

/** Where the TV is in the live window, for the remote's bar: 100 at live, never under 8 so it stays visible. */
export function livePosition(behind: number, windowSeconds: number): number {
  if (behind <= 0) return 100
  return Math.max(8, 100 - Math.round((behind / Math.max(1, windowSeconds)) * 100))
}

/** How far behind the live edge still counts as live, as in the web player. */
export const AT_LIVE_SECONDS = 7

/** "0:42 behind live". */
export function behindText(seconds: number): string {
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')} behind live`
}

/** The casting bar's live form (the LiveCast board's bar view). */
export type LiveBar = { title: string; sub: string; pct: number; badge: boolean; ask: boolean; controls: boolean }

export function liveBar(o: { receiver: StateMessage; channel: LiveChannel; device: string; now: number }): LiveBar | undefined {
  const r = o.receiver
  if (!r.live || (r.phase !== 'playing' && r.phase !== 'loading')) return undefined
  const { now: p } = nowAndNext(o.channel, o.now)
  const title = [o.channel.number, o.channel.name].filter(Boolean).join(' ') + (p ? ` · ${p.title}` : '')
  const on = `on ${o.device}`
  if (r.phase === 'loading') return { title, sub: `Tuning on ${o.device}…`, pct: 0, badge: false, ask: false, controls: false }
  if (r.stillWatching) return { title, sub: `${o.device} asks: still watching?`, pct: 0, badge: false, ask: true, controls: false }
  if (r.live.paused) {
    const left = r.live.pauseLeftSeconds != null ? Math.max(1, Math.ceil(r.live.pauseLeftSeconds / 60)) : undefined
    return { title, sub: `Paused${left ? ` · the tuner goes back in ${left} min` : ''} · ${on}`, pct: p ? progress(p, o.now) : 0, badge: true, ask: false, controls: true }
  }
  const ep = p ? episodeLine(p) : ''
  const sub = [ep, p ? `${duration(Date.parse(p.end) - o.now)} left` : '', on].filter(Boolean).join(' · ')
  return { title, sub, pct: p ? progress(p, o.now) : 0, badge: true, ask: false, controls: true }
}
