/**
 * What senders and the Cast receiver say to each other (PLAN-CAST.md §2,
 * §4.5). Version 1. Changes are additive: both sides ignore message types
 * and fields they do not know. The Android app (core:cast) mirrors this
 * file; change both together.
 *
 * Standard Cast media messages carry play, pause, volume and stop; MEDIA_STATUS
 * carries the position and play state. Everything WatchMyMedia-specific goes
 * on NAMESPACE as JSON.
 */

export const NAMESPACE = 'urn:x-cast:media.watchmy.cast'
export const PROTOCOL_VERSION = 1

/** LOAD's media.contentId. */
export const contentId = (fileId: number) => `wmm:file:${fileId}`

/** LOAD's media.contentId for a Live TV channel (PLAN-LIVE-TV.md §4.1). */
export const liveContentId = (channelId: string) => `wmm:live:${channelId}`

/**
 * What a receiver can do beyond playing files, in every state message's
 * features. Senders offer a channel only to a receiver that lists live:
 * older receivers and Cast Connect apps reject a channel's LOAD.
 */
export const FEATURE_LIVE = 'live'
/** Receiver can accept Live TV Automatic LOAD and quality commands. */
export const FEATURE_LIVE_AUTO = 'live-auto'

/** A Live TV channel to play, in LOAD's media.customData. */
export type LiveLoad = {
  channelId: string
  /** auto on a capable receiver/server, or 1080, 720, 480, 360. Absent is 1080. */
  quality?: string
}

/** Cast Connect: LOAD's credentialsType when credentials hold a cast token. */
export const CREDENTIALS_TYPE = 'wmm-cast'

/** LOAD's media.customData. */
export type LoadData = {
  v: number
  server: {
    /** GET /api/server's id: the receiver plays only from a server that answers with it. */
    id: string
    /** Addresses to try, best first (POST /api/auth/cast-token's urls plus the sender's own). */
    urls: string[]
  }
  /** The cast token. */
  token: string
  /** Its expiry, ISO 8601. */
  tokenExpiresAt?: string
  /** The file to play; absent when live names a channel instead. */
  fileId?: number
  /** A Live TV channel instead of a file (API level 14). */
  live?: LiveLoad
  /** File position to start at; absent or null resumes where the viewer left off. */
  startMs?: number | null
  audioIndex?: number | null
  subtitleId?: string | null
  /** API level 18: auto uses local-track preferences; off preserves an explicit Off. Older receivers ignore it. */
  subtitleMode?: 'auto' | 'off'
  /**
   * A quality ID shared by every client: auto, original, 2160, 1080, 720,
   * 480, 360. The receiver plays auto as original on a server below API
   * level 3, and an ID it does not know as original.
   */
  quality?: string
  /** The Cast device as the sender knows it, for Activity: a receiver cannot read its own name. */
  device?: { id?: string; name?: string }
  /** Who cast, for the receiver's screens ("Cast from Pixel 9"). */
  sender?: { name?: string }
}

export type SegmentInfo = { kind: string; startMs: number; endMs?: number | null; final?: boolean }

export type AudioOption = { index: number; codec: string; channels: number; language?: string; title?: string; default: boolean }

export type SubtitleOption = { id: string; language?: string; title?: string; format: string; forced?: boolean; burnInOnly?: boolean }

export type UpNextInfo = {
  fileId: number
  showTitle: string
  seasonNumber: number
  episodeNumber: number
  title: string
  /** Optional public artwork URL; authenticated artwork stays on the receiver. */
  stillUrl?: string
  /** Seconds before it plays by itself; null when autoplay is off. */
  secondsLeft: number | null
}

/** What is on a channel, from the server's listings. */
export type LiveProgrammeInfo = { title: string; episodeTitle?: string; start: string; end: string }

/**
 * The channel playing, in a live play's state. Senders show its logo and
 * pictures from their own channel list: those need the sender's own sign-in.
 */
export type LiveInfo = {
  channelId: string
  number: string
  name: string
  quality: string
  now?: LiveProgrammeInfo | null
  next?: LiveProgrammeInfo | null
  /** The viewer paused; the tuner goes back after pauseLeftSeconds. */
  paused?: boolean
  pauseLeftSeconds?: number | null
  /** Why the channel stopped or could not play: the server's reason (liveFailure words it). */
  reason?: string | null
}

/** Receiver to senders: sent on every change, and to each sender that joins or asks. */
export type StateMessage = {
  type: 'state'
  v: number
  /** FEATURE_LIVE and others to come; absent from older receivers. */
  features?: string[]
  /** idle: nothing loaded; loading; playing covers paused and buffering too (see MEDIA_STATUS); ended; stopped by an admin; error. */
  phase: 'idle' | 'loading' | 'playing' | 'ended' | 'stopped' | 'error'
  fileId?: number
  playId?: string
  title?: string
  /** "S1 · E2 · Title" for an episode. */
  subtitle?: string
  mode?: 'direct' | 'remux' | 'audio' | 'transcode'
  quality?: string
  /** At quality auto: the step the stream is at (original, 2160 … 360). */
  autoStep?: string | null
  durationMs?: number
  /** File position = offsetMs + MEDIA_STATUS currentTime. 0 for direct play and VOD playlists. */
  offsetMs?: number
  /** Past this file position the item counts as watched. */
  watchedAtMs?: number
  segments?: SegmentInfo[]
  /** The segment the Skip button offers now; null when none. */
  skip?: { kind: string; endMs: number; auto: boolean } | null
  /** The Next Episode card; null when hidden. */
  upNext?: UpNextInfo | null
  /** The episode after this one, card or not. */
  nextEpisode?: Omit<UpNextInfo, 'secondsLeft'> | null
  /** Paused to ask "Still watching?". */
  stillWatching?: boolean
  audio?: AudioOption[]
  selectedAudio?: number | null
  subtitles?: SubtitleOption[]
  selectedSubtitle?: string | null
  /** An admin stopped the play; the message to show, possibly empty. */
  stopMessage?: string | null
  error?: string | null
  /** The device the receiver says it is, for "Playing on …" when the sender has no name. */
  deviceName?: string
  /** A Live TV channel is playing (fileId is then absent). */
  live?: LiveInfo | null
}

/** Senders to receiver. */
export type CommandMessage =
  | { type: 'getState' }
  | { type: 'seek'; positionMs: number }
  | { type: 'skip' }
  | { type: 'playNext' }
  | { type: 'watchCredits' }
  | { type: 'stillWatching'; keep: boolean }
  | { type: 'audio'; index: number }
  | { type: 'subtitle'; id: string | null }
  | { type: 'quality'; id: string }
  /** Any touch on a sender: resets the Still Watching counters. */
  | { type: 'input' }
  /** Diagnostics: the receiver answers the sender alone with a `caps` message (castCaps). */
  | { type: 'caps' }
  /** Live TV: change to this channel, or the next or previous one the viewer may watch. */
  | { type: 'channel'; channelId: string }
  | { type: 'channelStep'; by: 1 | -1 }
