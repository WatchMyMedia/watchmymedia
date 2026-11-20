import type { Action } from '../../app/keys'

// What the remote does in the Live TV player (Android TV's
// LiveRemote.kt): with the controls hidden ▲ ▼ and CH ± change
// channel, ◀ opens On now and OK or ▶ show the controls; the number keys
// open Go to channel. Return closes the newest thing first.

export type LivePanel = 'onNow' | 'quality' | 'number'

export type LiveAction =
  | 'pass' | 'swallow' | 'controls' | 'onNow' | 'quality' | 'number'
  | 'channelUp' | 'channelDown' | 'previousChannel'
  | 'togglePause' | 'play' | 'pause' | 'leave' | 'guide' | 'keepWatching'

export type LiveKeyState = {
  panel?: LivePanel
  controls: boolean
  /** It stopped or couldn't start: the problem's buttons have focus. */
  terminal: boolean
  /** Still watching? is asked. */
  prompt: boolean
}

const MOVES: Action[] = ['up', 'down', 'left', 'right', 'ok', 'hold', 'back']

/** What a key does in the Live TV player; 'pass' leaves it to focus and the buttons. */
export function liveKeyAction(action: Action, s: LiveKeyState): LiveAction {
  if (action === 'stop') return 'leave'
  // The remote's Guide opens the full guide from wherever the player is.
  if (action === 'guide') return 'guide'
  if (s.terminal) return MOVES.includes(action) ? 'pass' : 'swallow'
  // Any press answers Still watching; moving and choosing reach its buttons.
  if (s.prompt) return MOVES.includes(action) ? 'pass' : 'keepWatching'
  if (action === 'channelUp') return 'channelUp'
  if (action === 'channelDown') return 'channelDown'
  if (action === 'previousChannel') return 'previousChannel'
  if (action === 'digit') return s.panel === 'number' ? 'pass' : 'number'
  if (s.panel) {
    if (action === 'playPause') return 'togglePause'
    if (action === 'play' || action === 'pause') return action
    if (action === 'rewind' || action === 'fastForward' || action === 'next' || action === 'previous') return 'swallow'
    // On now closes with ▶ as well as Return.
    if (s.panel === 'onNow' && action === 'right') return 'onNow'
    return 'pass'
  }
  switch (action) {
    case 'playPause': return 'togglePause'
    case 'play': case 'pause': return action
    case 'info': return 'controls'
    case 'up': return s.controls ? 'pass' : 'channelUp'
    case 'down': return s.controls ? 'pass' : 'channelDown'
    case 'left': return s.controls ? 'pass' : 'onNow'
    case 'ok': case 'right': return s.controls ? 'pass' : 'controls'
    case 'rewind': case 'fastForward': case 'next': case 'previous': case 'minus': return 'swallow'
    default: return 'pass'
  }
}

export type LiveBackAction = 'closePanel' | 'keepWatching' | 'hideControls' | 'leave'

/** Return: the prompt, then the panel, then the controls, then the tuner is given back and the player closes. */
export function liveBackAction(s: LiveKeyState): LiveBackAction {
  if (s.terminal) return 'leave'
  if (s.prompt) return 'keepWatching'
  if (s.panel) return 'closePanel'
  if (s.controls) return 'hideControls'
  return 'leave'
}

/** The key for a channel number's dot: the remote's "-" between the major and minor number (7-1). */
export function numberKey(action: Action, digit?: number): string | undefined {
  if (action === 'digit' && digit !== undefined) return String(digit)
  if (action === 'minus') return '.'
  return undefined
}

/** How long the channel banner stays after a channel change, and the controls after the last key. */
export const BANNER_MS = 5_000
export const CONTROLS_MS = 6_000
/** Still watching's grace before the tuner is given back. */
export const PROMPT_GRACE_S = 60
