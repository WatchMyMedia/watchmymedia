/**
 * Seeking with the remote, as Android TV does it (tv/player/Seeking.kt): a
 * press moves the target 10 s; held, the key repeats and the step grows to
 * 30 s, 1 min and then 2 min, at most one step every 200 ms. The seek is
 * sent once the key has come up and the remote has been quiet SEND_AFTER_MS,
 * so a run of quick presses is one seek to where they add up, and the target
 * stays on screen until the position reaches it.
 */
export const PRESS_MS = 10_000
export const REPEAT_INTERVAL_MS = 200
export const MAX_STEP_MS = 120_000
/** [held for less than, step]. */
const TIERS: readonly (readonly [number, number])[] = [[2_000, 10_000], [4_000, 30_000], [7_000, 60_000]]
/** The target is reached within this. */
export const LANDED_WITHIN_MS = 1_500
/** After the seek goes, the target shows at most this long while it lands. */
export const SETTLE_TIMEOUT_MS = 2_500
/**
 * How long after a key comes up the seek waits for another press. Every seek
 * flushes the player and, on a stream the server makes, can start its
 * encoder again: three quick presses back are one seek 30 s back, not three.
 */
export const SEND_AFTER_MS = 400

export class SeekAccelerator {
  private heldSince?: number
  private lastStepAt = 0

  /** The step for a key-down at now; repeat for the key's auto-repeats. 0 between throttled repeats. */
  step(now: number, repeat: boolean): number {
    if (!repeat || this.heldSince === undefined) {
      this.heldSince = now
      this.lastStepAt = now
      return PRESS_MS
    }
    if (now - this.lastStepAt < REPEAT_INTERVAL_MS) return 0
    this.lastStepAt = now
    const held = now - this.heldSince
    return TIERS.find(([under]) => held < under)?.[1] ?? MAX_STEP_MS
  }

  release() { this.heldSince = undefined }
}

export type ScrubView = { targetMs: number; stepMs: number; holding: boolean }

/** The scrubber's target while the viewer seeks with the remote. */
export class Scrub {
  private target?: number
  private step = 0
  private holding = false
  /** Released, and not sent yet. */
  private unsent = false
  private releasedAt = 0
  private readonly accelerator = new SeekAccelerator()

  /** A press (repeat false) or a held key's repeat; from the target still shown, else the position. */
  press(direction: 1 | -1, positionMs: number, durationMs: number, now: number, repeat: boolean) {
    const step = this.accelerator.step(now, repeat && this.holding)
    if (step) this.step = step * direction
    const from = this.target ?? positionMs
    const max = durationMs > 0 ? durationMs : Number.MAX_SAFE_INTEGER
    this.target = Math.min(Math.max(from + step * direction, 0), max)
    this.holding = true
    this.unsent = false
  }

  /** The key came up: true when there is a target to send once the remote has been quiet SEND_AFTER_MS. */
  release(now: number): boolean {
    this.accelerator.release()
    if (!this.holding) return false
    this.holding = false
    this.unsent = true
    this.releasedAt = now
    return true
  }

  /** The remote has been quiet: where to seek, once; undefined while a key is down or with nothing to send. */
  send(now: number): number | undefined {
    if (this.holding || !this.unsent) return undefined
    this.unsent = false
    this.releasedAt = now
    return this.target
  }

  /** The position reached the target, or the seek had long enough: the scrubber follows the position again. */
  settle(positionMs: number, now: number) {
    if (this.holding || this.unsent || this.target === undefined) return
    if (Math.abs(positionMs - this.target) <= LANDED_WITHIN_MS || now - this.releasedAt >= SETTLE_TIMEOUT_MS) this.clear()
  }

  clear() {
    this.accelerator.release()
    this.target = undefined
    this.holding = false
    this.unsent = false
    this.step = 0
  }

  view(): ScrubView | undefined {
    return this.target === undefined ? undefined : { targetMs: this.target, stepMs: this.step, holding: this.holding }
  }
}

/** The seek thumbnail's chip: "+10 s", "+30 s", "+1 min", "+2 min"; back with a minus sign. */
export function seekStepLabel(stepMs: number): string {
  const sign = stepMs < 0 ? '−' : '+'
  const seconds = Math.abs(stepMs) / 1000
  return seconds >= 60 && seconds % 60 === 0 ? `${sign}${seconds / 60} min` : `${sign}${seconds} s`
}
