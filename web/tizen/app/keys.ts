// The remote's keys, in one table: Samsung's key codes and the browser
// preview's keyboard, both turned into the same named actions.

export type Action =
  | 'up' | 'down' | 'left' | 'right'
  | 'ok'
  /** OK held for HOLD_MS without letting go: the secondary action everywhere. */
  | 'hold'
  | 'back'
  | 'playPause' | 'play' | 'pause' | 'stop' | 'rewind' | 'fastForward' | 'previous' | 'next'
  | 'channelUp' | 'channelDown' | 'previousChannel'
  /** 0–9; `digit` says which. */
  | 'digit'
  /** The remote's "-", between a channel's major and minor number (7-1). */
  | 'minus'
  | 'info' | 'guide'

export type KeyInput = { action: Action; digit?: number; repeat: boolean }

/** Tizen's key codes (Samsung remote-control guide). */
const CODES: Record<number, Action> = {
  37: 'left', 38: 'up', 39: 'right', 40: 'down', 13: 'ok', 10009: 'back',
  10252: 'playPause', 415: 'play', 19: 'pause', 413: 'stop', 412: 'rewind', 417: 'fastForward',
  10232: 'previous', 10233: 'next', 427: 'channelUp', 428: 'channelDown', 10190: 'previousChannel',
  189: 'minus', 457: 'info', 458: 'guide',
}

/** The browser preview's keyboard: Escape or Backspace is Return, PageUp and PageDown are CH ▲ ▼. */
const KEYS: Record<string, Action> = {
  ArrowLeft: 'left', ArrowUp: 'up', ArrowRight: 'right', ArrowDown: 'down', Enter: 'ok',
  Escape: 'back', Backspace: 'back', GoBack: 'back', BrowserBack: 'back',
  MediaPlayPause: 'playPause', MediaPlay: 'play', MediaPause: 'pause', MediaStop: 'stop',
  MediaRewind: 'rewind', MediaFastForward: 'fastForward', MediaTrackPrevious: 'previous', MediaTrackNext: 'next',
  PageUp: 'channelUp', PageDown: 'channelDown', ChannelUp: 'channelUp', ChannelDown: 'channelDown',
  '-': 'minus', Info: 'info', Guide: 'guide',
}

/** The keys a Tizen app must ask for (tvinputdevice.registerKey) before it sees them; the arrows, OK and Return come anyway. */
export const REGISTERED_KEYS = [
  'MediaPlayPause', 'MediaPlay', 'MediaPause', 'MediaStop', 'MediaRewind', 'MediaFastForward',
  'MediaTrackPrevious', 'MediaTrackNext', 'ChannelUp', 'ChannelDown', 'PreviousChannel',
  '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'Minus', 'Info', 'Guide',
] as const

/** The IME's own Done and Cancel keys, which end typing in a field. */
export const IME_DONE = 65376
export const IME_CANCEL = 65385

/** The action a key stands for, or undefined for keys the app leaves alone. */
export function actionFor(event: Pick<KeyboardEvent, 'key' | 'keyCode' | 'repeat'>): KeyInput | undefined {
  const repeat = !!event.repeat
  if (event.keyCode >= 48 && event.keyCode <= 57) return { action: 'digit', digit: event.keyCode - 48, repeat }
  if (/^[0-9]$/.test(event.key)) return { action: 'digit', digit: Number(event.key), repeat }
  const action = CODES[event.keyCode] ?? KEYS[event.key]
  return action ? { action, repeat } : undefined
}

/** How long OK is held before it counts as Hold OK. */
export const HOLD_MS = 500

type Timers = { setTimeout(fn: () => void, ms: number): unknown; clearTimeout(id: unknown): void }
const realTimers: Timers = { setTimeout: (fn, ms) => setTimeout(fn, ms), clearTimeout: (id) => clearTimeout(id as ReturnType<typeof setTimeout>) }

/**
 * Tells OK from Hold OK. Tizen repeats keydown while a key is held, so only
 * the first keydown counts: Hold OK fires once, after HOLD_MS without a
 * keyup; OK fires on keyup, when no hold fired.
 */
export class HoldDetector {
  private timer: unknown
  private held = false
  private down = false

  constructor(private onHold: () => void, private ms = HOLD_MS, private timers: Timers = realTimers) {}

  keydown() {
    if (this.down) return
    this.down = true
    this.held = false
    this.timer = this.timers.setTimeout(() => { this.held = true; this.onHold() }, this.ms)
  }

  /** The press ended: true when it was a plain OK (no hold fired). */
  keyup(): boolean {
    if (!this.down) return false
    this.down = false
    this.timers.clearTimeout(this.timer)
    return !this.held
  }

  /** Focus moved or the app hid mid-press: neither OK nor Hold OK. */
  cancel() {
    this.timers.clearTimeout(this.timer)
    this.down = false
  }

  get pressing() { return this.down }
}
