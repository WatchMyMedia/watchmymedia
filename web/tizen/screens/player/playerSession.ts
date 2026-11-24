import { qualityById, saveQuality, savedQuality } from '../../../src/lib/quality'
import type { PlayRequest } from '../../app/routes'
import { localSettings } from '../../app/localSettings'
import { tvTakesHdr10 } from '../../app/platform'
import { FilePlayback, playbackSettings, StillWatchingSession, type FilePlaybackState, type FileRequest, type PlaybackSettings, type Player, type Preferences, type UpNext } from '../../player'
import type { ActiveSession } from '../../session/active'
import type { Source } from '../../session/peerRegistry'
import type { PlaybackMedia } from '../../app/playbackErrors'
import { episodeLine, type PlayerKind } from './labels'

/** What the player route asks for, as the engine's request; undefined for what this player can't play (a trailer with no file). */
export function fileRequest(play: PlayRequest): FileRequest | undefined {
  switch (play.kind) {
    case 'file': return { kind: 'file', fileId: play.fileId, startMs: play.startMs, title: play.title }
    case 'recording': return { kind: 'recording', recordingId: play.id, startMs: play.startMs, title: play.title }
    case 'trailer': return play.fileId === undefined ? undefined : { kind: 'trailer', fileId: play.fileId, title: play.title }
    default: return undefined
  }
}

/** What a play was, for the TV's list of playback errors: a file that is no episode counts as a movie. */
export const playbackMedia = (kind: PlayerKind): PlaybackMedia => kind === 'file' ? 'movie' : kind

/** What heads the player: the title, and the line under it. */
export type Heading = { title: string; line?: string }

export function playKey(request: FileRequest | undefined): string {
  if (!request) return ''
  return request.kind === 'recording' ? `recording:${request.recordingId}` : `${request.kind}:${request.fileId}`
}

/** The route's own words for what it plays. */
export function routeHeading(play: PlayRequest): Heading {
  switch (play.kind) {
    case 'file': return { title: play.title ?? 'Playing', line: play.subtitle }
    case 'recording': return { title: play.title ?? 'Recording' }
    case 'trailer': return { title: play.title ?? 'Trailer' }
    default: return { title: 'Playing' }
  }
}

/** An episode the player moves on to: the show, and the episode's line. */
export const episodeHeading = (e: Pick<UpNext, 'showTitle' | 'seasonNumber' | 'episodeNumber' | 'title'>): Heading =>
  ({ title: e.showTitle, line: episodeLine(e.seasonNumber, e.episodeNumber, e.title) })

/** The Still watching counts outlive each play and each player screen: one per sign-in. */
const stillWatching = new WeakMap<ActiveSession, StillWatchingSession>()

function stillWatchingFor(session: ActiveSession): StillWatchingSession {
  let still = stillWatching.get(session)
  if (!still) stillWatching.set(session, still = new StillWatchingSession(previewClock()))
  return still
}

/**
 * The preview's ?fakes=player runs Still watching's clock a thousand times
 * fast, so its question comes at the next episode after a few quiet seconds.
 * Never in the TV build.
 */
function previewClock(): (() => number) | undefined {
  if (!import.meta.env.DEV || document.documentElement.dataset.fakes !== 'player') return undefined
  const from = Date.now()
  return () => from + (Date.now() - from) * 1000
}

/**
 * One player screen's play: the engine's FilePlayback for the session (its
 * api, the app's Player, the media addresses and Still watching), the
 * person's preferences and the TV's own settings, and what heads each play
 * it moves on to. Made when the screen opens, released when it goes.
 */
export class PlayerSession {
  readonly controller: FilePlayback
  private preferences?: Preferences
  private quality: string
  private settingsMemo?: { preferences?: Preferences; size: string; quality: string; value: PlaybackSettings }
  private headings = new Map<string, Heading>()
  private unsubscribe: () => void

  /**
   * `source` is where the file is: the current server, or another this TV is
   * signed in to, whose own client and media addresses play it (and get its
   * progress) without switching the app.
   */
  constructor(session: ActiveSession, player: Player, source?: Source) {
    const peer = source?.peer
    const level = peer ? peer.apiLevel ?? session.server.apiLevel : session.server.apiLevel
    this.quality = savedQuality(level).id
    this.controller = new FilePlayback({
      api: peer ? peer.api : session.api,
      player,
      mediaUrl: peer ? peer.playerMediaUrl : session.playerMediaUrl,
      client: session.client,
      settings: () => this.settings(),
      stillWatching: stillWatchingFor(session),
      saveQuality: (id) => { this.quality = id; saveQuality(qualityById(id, level)) },
    })
    // The next episode's words, kept before it plays, for when it does.
    this.unsubscribe = this.controller.subscribe(() => {
      const next = this.controller.snapshot().upNext
      const key = next && `file:${next.fileId}`
      if (next && key && !this.headings.has(key)) this.headings.set(key, episodeHeading(next))
    })
  }

  /** Read at each play and as it goes: the account's skip, autoplay and subtitle settings, the TV's quality and subtitle size. */
  private settings(): PlaybackSettings {
    const size = localSettings().subtitleSize
    const m = this.settingsMemo
    if (m && m.preferences === this.preferences && m.size === size && m.quality === this.quality) return m.value
    const value = playbackSettings(this.preferences, { quality: this.quality, subtitleSize: size, hdr10: tvTakesHdr10() })
    this.settingsMemo = { preferences: this.preferences, size, quality: this.quality, value }
    return value
  }

  setPreferences(preferences: Preferences | undefined) { this.preferences = preferences }

  /** Plays what the route asks for. */
  start(play: PlayRequest, request: FileRequest) {
    this.headings.set(playKey(request), routeHeading(play))
    void this.controller.play(request)
  }

  /** Plays another episode, as a new play: the account's languages choose its tracks. */
  playEpisode(fileId: number, heading: Heading) {
    this.headings.set(`file:${fileId}`, heading)
    void this.controller.play({ kind: 'file', fileId, title: heading.title })
  }

  /** Plays another version of the same title from where it was, after one couldn't play. */
  playVersion(fileId: number, heading: Heading, startMs: number) {
    this.headings.set(`file:${fileId}`, heading)
    void this.controller.play({ kind: 'file', fileId, startMs, title: heading.title })
  }

  heading(state: Pick<FilePlaybackState, 'request' | 'title'>): Heading {
    return this.headings.get(playKey(state.request)) ?? { title: state.title ?? 'Playing' }
  }

  /** Leaving: the last report and the server's session go out. */
  stop(): Promise<void> { return this.controller.stop() }

  release() {
    this.unsubscribe()
    void this.controller.release()
  }
}
