import type { Action } from '../../app/keys'
import type { FilePlaybackState } from '../../player'

// The player's remote, as Android TV's PlayerKeys.kt: what is over the
// video, what a key does there, and what Return does. Pure, so the screen
// only acts on the answers.

/** What is over the video and takes the remote, most important first. */
export type Overlay = 'message' | 'still' | 'panel' | 'next' | 'none'

/**
 * The overlay up now: an administrator's stop or an error, then Still
 * watching, then a picker or the Episodes panel, then the Next episode card.
 */
export function overlayOf(state: Pick<FilePlaybackState, 'adminStop' | 'phase' | 'askStillWatching' | 'next'>, panelOpen: boolean): Overlay {
  if (state.adminStop || state.phase === 'error') return 'message'
  if (state.askStillWatching) return 'still'
  if (panelOpen) return 'panel'
  if (state.next) return 'next'
  return 'none'
}

/** Where focus is while nothing is over the video: nowhere (the controls hidden), Skip, the scrubber or a button. */
export type Area = 'root' | 'skip' | 'scrubber' | 'buttons'

export const SKIP_ID = 'skip'
export const SCRUBBER_ID = 'scrubber'
export const PLAY_PAUSE_ID = 'playPause'

export function areaOf(focusId: string | undefined): Area {
  if (!focusId) return 'root'
  if (focusId === SKIP_ID) return 'skip'
  if (focusId === SCRUBBER_ID) return 'scrubber'
  return 'buttons'
}

export type KeyAction =
  /** Focus moves, OK clicks: the focused control or the overlay takes it. */
  | { kind: 'pass' }
  /** Kept, so it neither moves focus nor clicks. */
  | { kind: 'swallow' }
  | { kind: 'show' }
  | { kind: 'seek'; direction: 1 | -1 }
  | { kind: 'toggle' }
  | { kind: 'play' }
  | { kind: 'pause' }
  | { kind: 'next' }
  | { kind: 'previous' }
  | { kind: 'keepWatching' }
  | { kind: 'leave' }

export type KeyContext = { overlay: Overlay; controls: boolean; area: Area; hasNext: boolean; hasPrevious: boolean }

const PASS: KeyAction = { kind: 'pass' }
const SWALLOW: KeyAction = { kind: 'swallow' }
const seek = (forward: boolean): KeyAction => ({ kind: 'seek', direction: forward ? 1 : -1 })

/**
 * A key going down (Return aside: backAction). ◀ ▶ seek unless focus is in
 * the button row, where they move along it; OK plays or pauses unless a
 * control has focus; with the controls hidden ▲ ▼ only bring them back. An
 * overlay keeps the arrows and OK for its own buttons; Play/Pause still
 * works under the card and a panel, ⏪ ⏩ under the card.
 */
export function routeKey(action: Action, c: KeyContext): KeyAction {
  const { overlay } = c
  if (overlay === 'message') return action === 'stop' ? { kind: 'leave' } : moves(action) ? PASS : SWALLOW
  if (overlay === 'still') {
    if (moves(action)) return PASS
    return action === 'playPause' || action === 'play' ? { kind: 'keepWatching' } : action === 'stop' ? { kind: 'leave' } : SWALLOW
  }
  switch (action) {
    case 'left': case 'right':
      if (overlay !== 'none' || (c.controls && c.area === 'buttons')) return PASS
      return seek(action === 'right')
    case 'up': case 'down':
      return overlay === 'none' && !c.controls ? { kind: 'show' } : PASS
    case 'ok':
      return overlay !== 'none' || c.area === 'skip' || c.area === 'buttons' ? PASS : { kind: 'toggle' }
    case 'hold':
      return PASS
    case 'playPause': return { kind: 'toggle' }
    case 'play': return { kind: 'play' }
    case 'pause': return { kind: 'pause' }
    case 'rewind': case 'fastForward':
      return overlay === 'none' || overlay === 'next' ? seek(action === 'fastForward') : SWALLOW
    case 'stop': return { kind: 'leave' }
    // The remote's Next plays the next episode, from the card or with nothing over the video; Previous the one before.
    case 'next': return (overlay === 'next' || overlay === 'none') && c.hasNext ? { kind: 'next' } : SWALLOW
    case 'previous': return (overlay === 'next' || overlay === 'none') && c.hasPrevious ? { kind: 'previous' } : SWALLOW
    case 'info': return overlay === 'none' ? { kind: 'show' } : SWALLOW
    // Channels, numbers and the guide do nothing over a file: the video keeps playing.
    default: return SWALLOW
  }
}

function moves(action: Action) {
  return action === 'up' || action === 'down' || action === 'left' || action === 'right' || action === 'ok' || action === 'hold'
}

/** What Return does: close what is open first, then hide the controls, then leave. A panel closes itself. */
export type BackAction = 'leave' | 'keepWatching' | 'watchCredits' | 'cancelScrub' | 'hideControls' | 'closePanel'

export function backAction(overlay: Overlay, controls: boolean, scrubbing: boolean): BackAction {
  switch (overlay) {
    case 'message': return 'leave'
    case 'still': return 'keepWatching'
    case 'panel': return 'closePanel'
    case 'next': return scrubbing ? 'cancelScrub' : 'watchCredits'
    case 'none': return scrubbing ? 'cancelScrub' : controls ? 'hideControls' : 'leave'
  }
}

/** The controls hide this long after the last key, while the video plays with nothing over it. */
export const HIDE_CONTROLS_MS = 5_000

/** A held seek key with no repeat for this long counts as let go, if its keyup never came. */
export const RELEASE_AFTER_MS = 800

export type SeekKey = 'left' | 'right' | 'rewind' | 'fastForward'

/**
 * Which seek key is held, so a press, its repeats and its release become
 * beginScrub, scrubStep and commitScrub. A TV may repeat keydown without
 * marking it as a repeat, so a key counts as held from its first keydown
 * until its keyup (or a blur, or a quiet spell), whatever the events say.
 */
export class SeekHold {
  private held?: { key: SeekKey; direction: 1 | -1; at: number }

  /** A seek key went down: 'begin' for a new press (or the other way), 'step' while the same key is held. */
  down(key: SeekKey, direction: 1 | -1, now: number): 'begin' | 'step' {
    const same = this.held && this.held.key === key && this.held.direction === direction
    this.held = { key, direction, at: now }
    return same ? 'step' : 'begin'
  }

  /** A key came up (undefined: every key, as on a blur): whether that ends the hold, so the seek applies. */
  up(key: Action | undefined): boolean {
    if (!this.held || (key !== undefined && key !== this.held.key)) return false
    this.held = undefined
    return true
  }

  /** No repeat for RELEASE_AFTER_MS: the hold ends as if let go. */
  quiet(now: number): boolean {
    if (!this.held || now - this.held.at < RELEASE_AFTER_MS) return false
    this.held = undefined
    return true
  }

  /** Return while seeking: the hold ends and nothing seeks. */
  cancel() { this.held = undefined }

  get holding() { return !!this.held }
}

export function seekKey(action: Action): SeekKey | undefined {
  return action === 'left' || action === 'right' || action === 'rewind' || action === 'fastForward' ? action : undefined
}
