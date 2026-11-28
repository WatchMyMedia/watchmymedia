/**
 * Subtitle timing: how much later (positive) or earlier a subtitle track
 * shows, in milliseconds, saved on the server for the person, for that
 * track of that file, so all their devices play it in step. An admin can
 * also save it for everyone who plays the file; a person's own timing wins
 * over that, and their Reset goes back to it.
 *
 * Apps that draw the cues themselves move them at once: a cue that starts
 * at t shows at t + timing. Subtitles burned into the picture take the
 * timing from the server, so they need a new play once it is saved.
 */

export const TIMING_STEP_MS = 100
export const TIMING_LIMIT_MS = 60_000
/** How long after the last press the timing is saved (and a burned-in play restarts). */
export const TIMING_SETTLE_MS = 1000

/** A timing on the 0.1 s steps, within the minute either way. */
export function clampTiming(ms: number): number {
  const stepped = Math.round(ms / TIMING_STEP_MS) * TIMING_STEP_MS
  return Math.max(-TIMING_LIMIT_MS, Math.min(TIMING_LIMIT_MS, stepped)) || 0
}

/** "In step", "0.4 s later", "1.2 s earlier". */
export function timingWords(ms: number): string {
  const t = clampTiming(ms)
  if (t === 0) return 'In step'
  return `${(Math.abs(t) / 1000).toFixed(1)} s ${t > 0 ? 'later' : 'earlier'}`
}

export const TIMING_HELP = 'Saved for this subtitle track on all your devices.'
export const TIMING_SHARED = 'Set for everyone'
export const SHARE_LABEL = 'Save for everyone'
export const SHARE_HELP = 'Everyone who plays this file gets this timing. Their own changes still win for them.'
export const SHARE_DONE = 'Saved for everyone'
export const TIMING_FAILED = 'Couldn’t save, so this timing is for this play only.'
export const TIMING_BURNED = 'These subtitles are part of the picture, so the video restarts here a second after you stop changing the timing.'

/** A track's timing as a decision gives it. */
export type TrackTiming = { timingMs?: number; timingShared?: boolean }

/** Which track a timing belongs to. */
export type TimingTarget = { fileId: number; trackId: string }

/** The server's answer to a save or a reset: the timing the person's plays now get. */
export type SavedTiming = { timingMs: number; timingShared: boolean }

export type TimingSaver = {
  /** PUT …/subtitles/{track}/timing; everyone for an admin's Save for everyone. */
  save(target: TimingTarget, timingMs: number, everyone: boolean): Promise<SavedTiming>
  /** DELETE …/subtitles/{track}/timing: back to the timing saved for everyone, else in step. */
  reset(target: TimingTarget): Promise<SavedTiming>
}

type Timers = { setTimeout(fn: () => void, ms: number): unknown; clearTimeout(handle: unknown): void }

const browserTimers: Timers = {
  setTimeout: (fn, ms) => setTimeout(fn, ms),
  clearTimeout: (h) => clearTimeout(h as ReturnType<typeof setTimeout>),
}

export type TimingStatus = 'idle' | 'changed' | 'saving' | 'failed' | 'shared'

/** What a player draws for the timing of the track that is on. */
export type TimingView = {
  /** The timing the cues are drawn with now. */
  ms: number
  words: string
  /** "Set for everyone" under the value: the admin's timing, not changed by this person. */
  setForEveryone: boolean
  /** Reset would change something. */
  canReset: boolean
  /** What Reset goes back to: the timing saved for everyone, when known, else 0. */
  resetTo: number
  /** Save for everyone: an admin, with a timing of their own that isn't everyone's already. */
  canShare: boolean
  /** The track is part of the picture: the play restarts to apply a change. */
  burned: boolean
  status: TimingStatus
}

const IDLE_VIEW: TimingView = { ms: 0, words: 'In step', setForEveryone: false, canReset: false, resetTo: 0, canShare: false, burned: false, status: 'idle' }

/**
 * The timing of the subtitle track a player shows, one per play. Earlier
 * and Later move it at once and save it a second after the last press;
 * Reset and Save for everyone save at once. A failed save keeps the timing
 * for this play and offers Try again. When the play moves to another track,
 * a change still waiting is saved for the track it was made on.
 */
export class SubtitleTiming {
  private target: TimingTarget | undefined
  private ms = 0
  /** The admin's timing for everyone, when known: what Reset goes back to. */
  private sharedMs: number | undefined
  /** ms is the shared one, not the person's own. */
  private onShared = false
  private burned = false
  private status: TimingStatus = 'idle'
  private failedOp: 'save' | 'reset' | 'share' = 'save'
  private timer: unknown
  private revision = 0
  private closed = false
  private snapshot: TimingView = IDLE_VIEW
  private admin: boolean

  constructor(private readonly opts: {
    saver: TimingSaver
    admin: boolean
    onChange: () => void
    /** A burned-in track's timing was saved: start the play again here so the server applies it. */
    onBurnedSaved?: (target: TimingTarget) => void
    timers?: Timers
  }) {
    this.admin = opts.admin
  }

  /** Whether the person may save for everyone, which can change while a play lasts (an admin away from home). */
  setAdmin(admin: boolean) {
    if (admin === this.admin) return
    this.admin = admin
    this.publish()
  }

  private get timers() { return this.opts.timers ?? browserTimers }

  /**
   * The track the player shows now, with its timing from the decision. The
   * same track again (a reload) keeps a change of this play's that the
   * decision may not have yet.
   */
  track(target: TimingTarget | undefined, from: TrackTiming | undefined, burned = false) {
    const same = !!target && !!this.target && target.fileId === this.target.fileId && target.trackId === this.target.trackId
    if (same) {
      this.burned = burned
      if (this.status === 'idle' || this.status === 'shared') this.take(from)
      this.publish()
      return
    }
    if (this.status === 'changed' && this.target) this.flush()
    this.timers.clearTimeout(this.timer)
    this.revision++
    this.target = target
    this.burned = burned
    this.status = 'idle'
    this.sharedMs = undefined
    this.take(from)
    this.publish()
  }

  private take(from: TrackTiming | undefined) {
    this.ms = clampTiming(from?.timingMs ?? 0)
    this.onShared = !!from?.timingShared
    if (this.onShared) this.sharedMs = this.ms
  }

  /** Earlier (-1) or Later (+1) by 0.1 s. */
  step(by: 1 | -1) {
    if (!this.target || this.closed) return
    const next = clampTiming(this.ms + by * TIMING_STEP_MS)
    if (next === this.ms && !this.onShared) return
    this.ms = next
    this.onShared = false
    this.status = 'changed'
    this.revision++
    this.timers.clearTimeout(this.timer)
    this.timer = this.timers.setTimeout(() => this.flush(), TIMING_SETTLE_MS)
    this.publish()
  }

  /** Back to the timing saved for everyone, else in step. */
  reset() {
    const target = this.target
    if (!target || this.closed) return
    this.timers.clearTimeout(this.timer)
    const revision = ++this.revision
    this.ms = this.sharedMs ?? 0
    this.onShared = this.sharedMs !== undefined
    this.status = 'saving'
    this.publish()
    this.run(revision, 'reset', this.opts.saver.reset(target))
  }

  /** An admin's Save for everyone: this timing becomes the file's, and their own goes. */
  share() {
    const target = this.target
    if (!target || this.closed || !this.admin) return
    this.timers.clearTimeout(this.timer)
    const revision = ++this.revision
    this.status = 'saving'
    this.publish()
    this.run(revision, 'share', this.opts.saver.save(target, this.ms, true))
  }

  /** Try again after a failed save. */
  retry() {
    if (this.status !== 'failed') return
    if (this.failedOp === 'reset') this.reset()
    else if (this.failedOp === 'share') this.share()
    else this.flush()
  }

  /** Saves a change now rather than when the presses settle. */
  flush() {
    const target = this.target
    if (!target || this.closed || (this.status !== 'changed' && this.status !== 'failed')) return
    this.timers.clearTimeout(this.timer)
    const revision = ++this.revision
    this.status = 'saving'
    this.publish()
    this.run(revision, 'save', this.opts.saver.save(target, this.ms, false))
  }

  private run(revision: number, op: 'save' | 'reset' | 'share', request: Promise<SavedTiming>) {
    const target = this.target
    request.then((saved) => {
      if (this.closed || revision !== this.revision) return
      if (op === 'save') {
        this.onShared = saved.timingShared
        if (saved.timingShared) this.sharedMs = saved.timingMs
      } else {
        this.ms = clampTiming(saved.timingMs)
        this.onShared = saved.timingShared
        this.sharedMs = saved.timingShared ? this.ms : undefined
      }
      this.status = op === 'share' ? 'shared' : 'idle'
      this.publish()
      if (this.burned && target) this.opts.onBurnedSaved?.(target)
    }, () => {
      if (this.closed || revision !== this.revision) return
      this.failedOp = op
      this.status = 'failed'
      this.publish()
    })
  }

  close() {
    if (this.status === 'changed') this.flush()
    this.closed = true
    this.timers.clearTimeout(this.timer)
  }

  /** The same object until something changes, for useSyncExternalStore. */
  view(): TimingView {
    return this.snapshot
  }

  private publish() {
    const base = this.sharedMs ?? 0
    const own = !this.onShared
    this.snapshot = {
      ms: this.ms,
      words: timingWords(this.ms),
      setForEveryone: this.onShared && this.ms !== 0,
      canReset: own && this.ms !== base,
      resetTo: base,
      canShare: this.admin && !this.burned && own && this.ms !== base && this.status !== 'saving',
      burned: this.burned,
      status: this.status,
    }
    this.opts.onChange()
  }
}
