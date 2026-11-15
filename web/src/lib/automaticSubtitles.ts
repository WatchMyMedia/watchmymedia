import { authScope } from './authScope'
import { SubtitleIntent } from './subtitleIntent'

type Result<T> = { track?: T; error?: string }

/**
 * Whether a play should ask for an automatic subtitle: whenever subtitles
 * would start, as the server allows. That is "Always" (mode on), and "When
 * the audio isn't in my languages" (forced with foreignAudio), where the
 * server checks the play's audio and answers with nothing when it is one of
 * the person's languages.
 */
export function asksForAutomaticSubtitle(subtitles: { mode: string; foreignAudio?: boolean; automaticDownload?: boolean } | undefined) {
  if (!subtitles?.automaticDownload) return false
  return subtitles.mode === 'on' || (subtitles.mode === 'forced' && !!subtitles.foreignAudio)
}
type Request<T> = (fileId: number, playId: string, cancel?: boolean) => Promise<Result<T>>

/** One optional request per owned play. The server separately enforces its
 * quota boundary; this fence prevents a delayed result changing the local UI. */
export class AutomaticSubtitles<T> {
  readonly intent = new SubtitleIntent()
  private attempted = new Set<string>()
  private pending = new Set<string>()
  private cancelled = new Set<string>()
  private closed = false
  private playId?: string
  private readonly owner = authScope()

  constructor(private fileId: number, private request: Request<T>) {}

  activate() { this.closed = false }

  setPlay(playId: string | undefined) { this.playId = playId }

  choose(id: string) {
    this.intent.choose(id)
    if (this.playId) this.cancel(this.playId)
  }

  private cancel(playId: string) {
    if (this.owner !== authScope() || this.cancelled.has(playId)) return
    this.cancelled.add(playId)
    void this.request(this.fileId, playId, true).catch(() => undefined)
  }

  async begin(playId: string): Promise<Result<T> | undefined> {
    if (this.closed || !this.intent.automatic || this.owner !== authScope() || this.attempted.has(playId)) return
    this.attempted.add(playId)
    this.pending.add(playId)
    const revision = this.intent.capture()
    try {
      const result = await this.request(this.fileId, playId)
      if (this.closed || this.owner !== authScope() || !this.intent.accepts(revision) || this.playId !== playId) return
      return result
    } catch (error) {
      if (this.closed || this.owner !== authScope() || !this.intent.accepts(revision) || this.playId !== playId) return
      throw error
    } finally {
      this.pending.delete(playId)
    }
  }

  close() {
    this.closed = true
    this.intent.invalidate()
    for (const playId of this.pending) this.cancel(playId)
  }
}
