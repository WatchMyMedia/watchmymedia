// The remote's keys, in one table: the TV's key codes (the platform's) and
// the browser preview's keyboard, both turned into the same named actions.

import { platform } from '../platform/current'

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

/** The remote's key that goes back, as hints and notes name it: Return on a Samsung TV. */
export const BACK = platform.words.back

/** The browser preview's keyboard: Escape or Backspace is Return, PageUp and PageDown are CH ▲ ▼. */
const KEYS: Record<string, Action> = {
  ArrowLeft: 'left', ArrowUp: 'up', ArrowRight: 'right', ArrowDown: 'down', Enter: 'ok',
  Escape: 'back', Backspace: 'back', GoBack: 'back', BrowserBack: 'back',
  MediaPlayPause: 'playPause', MediaPlay: 'play', MediaPause: 'pause', MediaStop: 'stop',
  MediaRewind: 'rewind', MediaFastForward: 'fastForward', MediaTrackPrevious: 'previous', MediaTrackNext: 'next',
  PageUp: 'channelUp', PageDown: 'channelDown', ChannelUp: 'channelUp', ChannelDown: 'channelDown',
  '-': 'minus', Info: 'info', Guide: 'guide',
}

/** The action a key stands for, or undefined for keys the app leaves alone. */
export function actionFor(event: Pick<KeyboardEvent, 'key' | 'keyCode' | 'repeat'>): KeyInput | undefined {
  const repeat = !!event.repeat
  if (event.keyCode >= 48 && event.keyCode <= 57) return { action: 'digit', digit: event.keyCode - 48, repeat }
  if (/^[0-9]$/.test(event.key)) return { action: 'digit', digit: Number(event.key), repeat }
  const action = platform.keyCodes[event.keyCode] ?? KEYS[event.key]
  return action ? { action, repeat } : undefined
}

/** How long OK is held before it counts as Hold OK. */
export const HOLD_MS = 500

type Timers = { setTimeout(fn: () => void, ms: number): unknown; clearTimeout(id: unknown): void }
const realTimers: Timers = { setTimeout: (fn, ms) => setTimeout(fn, ms), clearTimeout: (id) => clearTimeout(id as ReturnType<typeof setTimeout>) }

/**
 * Tells OK from Hold OK. A TV repeats keydown while a key is held, so only
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
