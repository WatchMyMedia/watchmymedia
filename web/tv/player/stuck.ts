/**
 * A play that should be moving and isn't, as the Android TV app watches for
 * it. A player can be left waiting for good with no error, a seek it never
 * finished among the ways; nothing else gets it going again, and the viewer
 * sees the spinner until they leave. The controller then tries, in turn, a
 * seek where it is, the stream loaded again, and a message that Play retries.
 */

/** Standing still this long, with STUCK_BUFFER_MS in hand, is stuck. */
export const STUCK_MS = 10_000
/** A player holding less than this ahead is waiting on the network, which isn't stuck. */
export const STUCK_BUFFER_MS = 10_000
/**
 * AVPlay doesn't say how much it holds, so a wait on the network looks the
 * same as a stuck player: give it this long before acting.
 */
export const STUCK_UNSEEN_MS = 30_000
export const STUCK_MESSAGE = 'Playback stopped and didn’t start again.'

export class StuckWatch {
  private stillMs = 0
  private atMs?: number

  /**
   * One tick of dtMs: moving is whether the play should be moving now (the
   * viewer wants it playing, nothing is loading, nothing has failed). True
   * once it has stood at the same position too long.
   */
  note(moving: boolean, positionMs: number, bufferedMs: number | undefined, dtMs: number): boolean {
    if (!moving || positionMs !== this.atMs || (bufferedMs !== undefined && bufferedMs < STUCK_BUFFER_MS)) {
      this.stillMs = 0
      this.atMs = positionMs
      return false
    }
    this.stillMs += dtMs
    return this.stillMs >= (bufferedMs === undefined ? STUCK_UNSEEN_MS : STUCK_MS)
  }

  /** Acted on: count again from now. */
  reset() {
    this.stillMs = 0
    this.atMs = undefined
  }
}
