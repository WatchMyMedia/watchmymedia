import { useSyncExternalStore } from 'react'
import { api, unwrap } from '../api/client'
import { castServerUrls, liveLoadData, settlePending } from '../lib/liveCast'
import { liveEdge } from '../lib/liveTV'
import { CREDENTIALS_TYPE, NAMESPACE, PROTOCOL_VERSION, contentId, liveContentId, type CommandMessage, type LoadData, type StateMessage } from './protocol'

/**
 * Casting from this browser to a Google Cast receiver (PLAN-CAST.md §5.1).
 * The receiver runs the play; this page is a remote. Chrome's Cast sender
 * SDK loads only when it can work: a secure context (HTTPS or localhost),
 * a Chromium browser, and a server with a receiver App ID.
 */

export type CastStatus = 'unavailable' | 'noDevices' | 'available' | 'connecting' | 'connected'

export type CastSnapshot = {
  status: CastStatus
  /** The TV's name while connected. */
  deviceName?: string
  /** The last TV this browser was connected to, for "Casting ended" once it isn't. */
  lastDeviceName?: string
  /** From MEDIA_STATUS: player time in seconds. */
  currentTime: number
  paused: boolean
  /** The receiver has media loaded (or is loading it). */
  mediaLoaded: boolean
  /** The receiver's latest state message. */
  receiver?: StateMessage
  /** The file this browser last asked the TV to play. */
  castingFileId?: number
  /** A Live TV channel this browser asked for that the TV isn't on yet (lib/liveCast settlePending). */
  castingChannelId?: string
  /** The channel the live remote drives: the one it cast, followed or found the TV on. */
  liveTarget?: string
  /** From MEDIA_STATUS while a channel plays: what of the live window the TV can seek to, in player seconds. */
  liveRange?: { start?: number; end?: number }
  error?: string
}

const SDK_URL = 'https://www.gstatic.com/cv/js/sender/v1/cast_sender.js?loadCastFramework=1'

let snapshot: CastSnapshot = { status: 'unavailable', currentTime: 0, paused: true, mediaLoaded: false }
const listeners = new Set<() => void>()
let started = false
let player: cast.framework.RemotePlayer | null = null
let controller: cast.framework.RemotePlayerController | null = null
let listenedSession: cast.framework.CastSession | null = null

function set(patch: Partial<CastSnapshot>) {
  snapshot = { ...snapshot, ...patch }
  for (const l of listeners) l()
}

// Development only: lets the remote UI be driven from the console without a TV.
if (import.meta.env.DEV) Object.assign(window, { __wmmCast: { set, get: () => snapshot } })

function subscribe(l: () => void) {
  listeners.add(l)
  return () => listeners.delete(l)
}

/** The cast state, kept current. */
export function useCast(): CastSnapshot {
  return useSyncExternalStore(subscribe, () => snapshot)
}

export function castSnapshot() {
  return snapshot
}

/** Whether this browser could cast at all: Chromium on a secure page. */
export function castSupported(): boolean {
  return typeof window !== 'undefined' && window.isSecureContext && 'chrome' in window && /Chrome|Chromium|CriOS|Edg\//.test(navigator.userAgent) && !/iPhone|iPad/.test(navigator.userAgent)
}

/** Loads the sender SDK once, for the receiver application appId. */
export function startCasting(appId: string) {
  if (started || !appId || !castSupported()) return
  started = true
  window.__onGCastApiAvailable = (available) => {
    // Chrome can report the API while the framework script failed to load.
    if (available && window.cast?.framework) setUp(appId)
  }
  const s = document.createElement('script')
  s.src = SDK_URL
  s.async = true
  document.head.appendChild(s)
}

function setUp(appId: string) {
  const ctx = cast.framework.CastContext.getInstance()
  ctx.setOptions({
    receiverApplicationId: appId,
    autoJoinPolicy: chrome.cast.AutoJoinPolicy.ORIGIN_SCOPED,
    // Google TVs with the WatchMyMedia app play in it (Cast Connect).
    androidReceiverCompatible: true,
  })
  const onCastState = () => {
    const st = ctx.getCastState()
    const status: CastStatus =
      st === cast.framework.CastState.CONNECTED ? 'connected'
      : st === cast.framework.CastState.CONNECTING ? 'connecting'
      : st === cast.framework.CastState.NOT_CONNECTED ? 'available'
      : 'noDevices'
    const session = ctx.getCurrentSession()
    if (session && session !== listenedSession) listen(session)
    if (!session) {
      listenedSession = null
      set({ status, deviceName: undefined, receiver: undefined, mediaLoaded: false, castingFileId: undefined, castingChannelId: undefined, liveTarget: undefined, liveRange: undefined })
      return
    }
    const deviceName = session.getCastDevice().friendlyName
    set({ status, deviceName, lastDeviceName: deviceName })
  }
  ctx.addEventListener(cast.framework.CastContextEventType.CAST_STATE_CHANGED, onCastState)
  ctx.addEventListener(cast.framework.CastContextEventType.SESSION_STATE_CHANGED, onCastState)
  player = new cast.framework.RemotePlayer()
  controller = new cast.framework.RemotePlayerController(player)
  controller.addEventListener(cast.framework.RemotePlayerEventType.ANY_CHANGE, () => {
    if (!player) return
    const range = player.liveSeekableRange
    set({ currentTime: player.currentTime, paused: player.isPaused, mediaLoaded: player.isMediaLoaded, liveRange: range ? { start: range.start, end: range.end } : undefined })
  })
  onCastState()
}

function listen(session: cast.framework.CastSession) {
  listenedSession = session
  session.addMessageListener(NAMESPACE, (_ns, message) => {
    try {
      const msg = JSON.parse(message) as StateMessage
      if (msg.type === 'state') set({ receiver: msg, castingChannelId: settlePending(snapshot.castingChannelId, snapshot.receiver, msg) })
    } catch {
      // Not ours, or from a newer receiver: ignore it.
    }
  })
  void send({ type: 'getState' })
}

/** Opens Chrome's device picker. */
export async function chooseDevice(): Promise<boolean> {
  try {
    const err = await cast.framework.CastContext.getInstance().requestSession()
    return !err
  } catch {
    return false
  }
}

/** Stops the play on the TV and leaves it. */
export function stopCasting() {
  const ctx = cast.framework.CastContext.getInstance()
  ctx.endCurrentSession(true)
}

export function send(msg: CommandMessage): Promise<unknown> {
  const session = window.cast?.framework.CastContext.getInstance().getCurrentSession()
  if (!session) return Promise.resolve()
  return session.sendMessage(NAMESPACE, msg).catch(() => undefined)
}

export function playOrPause() {
  controller?.playOrPause()
  void send({ type: 'input' })
}

/** Seeks to a file position. */
export function seekCast(positionMs: number) {
  void send({ type: 'seek', positionMs: Math.max(0, Math.floor(positionMs)) })
}

export type CastRequest = {
  fileId: number
  /** File position; null resumes where the viewer left off. */
  startMs?: number | null
  audioIndex?: number
  subtitleId?: string
  subtitleMode?: 'auto' | 'off'
  quality?: string
  title?: string
  subtitle?: string
}

/** Asks the connected TV to play a file, as the signed-in user. */
export async function castFile(req: CastRequest): Promise<void> {
  const session = cast.framework.CastContext.getInstance().getCurrentSession()
  if (!session) throw new Error('Not connected to a TV')
  set({ castingFileId: req.fileId, castingChannelId: undefined, liveTarget: undefined, error: undefined })
  try {
    const [token, server] = await Promise.all([
      api.POST('/api/auth/cast-token').then(unwrap),
      api.GET('/api/server').then(unwrap),
    ])
    const urls = castServerUrls(token.urls, location.origin, location.hostname)
    const subtitleMode = req.subtitleMode ?? (req.subtitleId ? 'off' : 'auto')
    const data: LoadData = {
      v: PROTOCOL_VERSION,
      server: { id: server.id, urls },
      token: token.token,
      tokenExpiresAt: token.expiresAt,
      fileId: req.fileId,
      startMs: req.startMs ?? null,
      audioIndex: req.audioIndex ?? null,
      subtitleId: subtitleMode === 'auto' ? null : req.subtitleId ?? null,
      subtitleMode,
      quality: req.quality,
      device: { name: session.getCastDevice().friendlyName },
      sender: { name: senderName() },
    }
    const info = new chrome.cast.media.MediaInfo(contentId(req.fileId), 'video/mp4')
    const meta = new chrome.cast.media.GenericMediaMetadata()
    meta.title = req.title ?? ''
    if (req.subtitle) meta.subtitle = req.subtitle
    info.metadata = meta
    info.customData = data
    info.streamType = chrome.cast.media.StreamType.BUFFERED
    const load = new chrome.cast.media.LoadRequest(info)
    load.autoplay = true
    load.customData = data
    // Cast Connect hands these to the Android TV app.
    Object.assign(load, { credentials: token.token, credentialsType: CREDENTIALS_TYPE })
    const err = await session.loadMedia(load)
    if (err) throw new Error(`The TV refused to play (${err})`)
  } catch (e) {
    set({ error: e instanceof Error ? e.message : String(e), castingFileId: undefined })
    throw e
  }
}

export type ChannelCastRequest = {
  channelId: string
  /** The sender's Live TV quality: 1080, 720, 480 or 360. */
  quality: string
  /** "7.1 LKSD-HD", and what is on, for the TV's media notification. */
  title?: string
  subtitle?: string
  /**
   * Stops this browser's own live session first, and resolves once its
   * tuner is given back: the household's tuners are few, and the TV needs
   * one (PLAN-LIVE-TV.md §9).
   */
  stopLocal?: () => Promise<void>
}

/**
 * Asks the connected TV to play a Live TV channel as the signed-in user
 * (PLAN-LIVE-TV.md §4.1). The receiver starts the live session on the cast
 * token and plays it as a LIVE stream; offer it only to a receiver whose
 * state lists live (lib/liveCast).
 */
export async function castChannel(req: ChannelCastRequest): Promise<void> {
  // The remote drives this channel from now on, played or refused.
  set({ castingChannelId: req.channelId, liveTarget: req.channelId, castingFileId: undefined, error: undefined })
  try {
    const session = window.cast?.framework.CastContext.getInstance().getCurrentSession()
    if (!session) throw new Error('Not connected to a TV')
    await req.stopLocal?.()
    const [token, server] = await Promise.all([
      api.POST('/api/auth/cast-token').then(unwrap),
      api.GET('/api/server').then(unwrap),
    ])
    const data = liveLoadData({
      serverId: server.id,
      urls: castServerUrls(token.urls, location.origin, location.hostname),
      token: token.token,
      tokenExpiresAt: token.expiresAt,
      channelId: req.channelId,
      quality: req.quality,
      deviceName: session.getCastDevice().friendlyName,
      senderName: senderName(),
    })
    const info = new chrome.cast.media.MediaInfo(liveContentId(req.channelId), 'application/x-mpegurl')
    const meta = new chrome.cast.media.GenericMediaMetadata()
    meta.title = req.title ?? ''
    if (req.subtitle) meta.subtitle = req.subtitle
    info.metadata = meta
    info.customData = data
    info.streamType = chrome.cast.media.StreamType.LIVE
    const load = new chrome.cast.media.LoadRequest(info)
    load.autoplay = true
    load.customData = data
    // Cast Connect hands these to the Android TV app, which lists live once it plays channels.
    Object.assign(load, { credentials: token.token, credentialsType: CREDENTIALS_TYPE })
    const err = await session.loadMedia(load)
    if (err) throw new Error(`The TV refused to play (${err})`)
    // Still on its way until the TV's state says it is on the channel (settlePending).
  } catch (e) {
    // A newer ask owns the state; this one only reports its own failure.
    if (snapshot.castingChannelId === req.channelId) set({ error: e instanceof Error ? e.message : String(e), castingChannelId: undefined })
    throw e
  }
}

/** Changes the TV's channel from the remote's channel list. */
export function switchCastChannel(channelId: string) {
  set({ castingChannelId: channelId, error: undefined })
  void send({ type: 'channel', channelId })
}

/**
 * Ch+ or Ch−: the TV picks the next channel from its own list. expected is
 * the one this page's list says, shown as tuning until the TV answers.
 */
export function stepCastChannel(by: 1 | -1, expected?: string) {
  set({ castingChannelId: expected, error: undefined })
  void send({ type: 'channelStep', by })
}

/** The live remote now drives this channel: the TV is on it, or it moved there. */
export function driveCastChannel(channelId: string | undefined) {
  if (snapshot.liveTarget !== channelId) set({ liveTarget: channelId })
}

/** The TV to the live edge, playing. */
export function goLiveCast() {
  const range = player?.liveSeekableRange
  if (player && controller && range?.end !== undefined) {
    player.currentTime = liveEdge(range.start ?? 0, range.end)
    controller.seek()
  }
  if (player?.isPaused) controller?.playOrPause()
  void send({ type: 'input' })
}

/** Stops what the TV plays and stays connected. */
export function stopCastMedia() {
  controller?.stop()
}

let lastInput = 0
/** A touch on the remote: the TV counts it against Still watching, at most every few seconds. */
export function noteCastInput() {
  const now = Date.now()
  if (now - lastInput < 5000) return
  lastInput = now
  void send({ type: 'input' })
}

/** Resolves once the TV's state passes ok, or after ms. */
export function whenCast(ok: (s: CastSnapshot) => boolean, ms: number): Promise<void> {
  return new Promise((resolve) => {
    if (ok(snapshot)) return resolve()
    const done = () => {
      listeners.delete(check)
      window.clearTimeout(timer)
      resolve()
    }
    const check = () => {
      if (ok(snapshot)) done()
    }
    const timer = window.setTimeout(done, ms)
    listeners.add(check)
  })
}

function senderName(): string {
  const ua = navigator.userAgent
  const browser = /Edg\//.test(ua) ? 'Edge' : 'Chrome'
  const os = /Android/.test(ua) ? 'Android' : /Mac OS X/.test(ua) ? 'macOS' : /Windows/.test(ua) ? 'Windows' : /CrOS/.test(ua) ? 'ChromeOS' : /Linux/.test(ua) ? 'Linux' : ''
  return os ? `${browser} on ${os}` : browser
}

/**
 * What the local player was doing when casting started, so the TV picks up
 * where the browser was (PLAN-CAST.md §5.1).
 */
export type Handover = { fileId: number; positionMs: number; audioIndex?: number; subtitleId?: string; subtitleMode?: 'auto' | 'off'; quality?: string; title?: string }
let handover: Handover | null = null
export function noteLocalPlay(h: Handover | null) {
  handover = h
}
export function takeHandover(fileId: number): Handover | null {
  const h = handover?.fileId === fileId ? handover : null
  handover = null
  return h
}
