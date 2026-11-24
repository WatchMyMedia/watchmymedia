import type { Action } from '../app/keys'
import type { DeviceProfiles, Player } from '../player'

/**
 * What one make of TV gives the app, as one object. Shared code reads it
 * from `./current` and never names a make: a difference between TVs goes
 * here, not into an `if` in a screen. Each build resolves `./current` to its
 * own make's folder (`vite.tv.config.ts`), so a bundle carries no other
 * make's code.
 */
export type TvPlatform = {
  /** What sign-in and plays report as this TV's platform. */
  id: 'tizen' | 'webos'
  words: TvWords
  /**
   * The names this app keeps in the TV's storage. Samsung's never change:
   * they hold every Samsung TV's servers and sign-ins.
   */
  storage: StorageNames
  /** The remote's key codes, beside the browser's own key names. */
  keyCodes: Readonly<Record<number, Action>>
  /** Key codes that end typing in a field, as Enter and Escape do: the TV keyboard's Done and Cancel, and the remote's back key. */
  typing: { done: readonly number[]; cancel: readonly number[] }
  /**
   * The shortest time between two repeats of a held key that the app acts
   * on, in ms; 0 takes every one. A TV that repeats much faster than the
   * remote was tuned for would race through rows and seeks.
   */
  repeatMs: number
  /** Whether the remote sends number keys to apps. Without them, Go to channel is a button and its pad. */
  numberKeys: boolean
  /** Whether the remote points (LG's Magic Remote): pointing focuses, a click presses, the wheel moves. */
  pointer: boolean
  /** Loads the TV's own APIs and asks for the remote's keys. Resolves in the preview too, and never rejects. */
  start(): Promise<void>
  /** Leaves the app. False where there is nowhere to go, as in the preview. */
  exit(): boolean
  /**
   * Back with nothing left to go back to, on Home or the first start
   * screen: true when the TV took it (LG hands it to webOS, which asks and
   * leaves), false for the app to ask "Leave WatchMyMedia?" itself, as
   * Samsung's certification asks.
   */
  backAtHome(): boolean
  /** Keeps the TV's screensaver away while something plays; call the returned function when it stops. */
  holdScreenSaver(): () => void
  /** Applies the screensaver state again, as the app comes and goes. */
  refreshScreenSaver(): void
  /** The TV's model, for the signed-in devices list; undefined in the preview. */
  model(): string | undefined
  /** The TV in Settings › About: "Samsung QN65QN90D · Tizen 8.0". */
  describe(): string
  /** Whether the TV shows HDR10, so the profile may claim it. */
  takesHdr10(): boolean
  /** The TV's own player, made once; undefined where there is none (the preview). */
  createPlayer(): Player | undefined
  /** What this TV plays, for the server's playback requests. */
  profiles: DeviceProfiles
}

export type TvWords = {
  /** The TV as sign-in names it and people say it: "Samsung TV". */
  tv: string
  /** With its article, after "on": "a Samsung TV". */
  aTv: string
  /** The product, in the signed-in devices list and About: "WatchMyMedia for Samsung TV". */
  product: string
  /** The remote's key that goes back, as hints name it: "Return". */
  back: string
  /** The TV's own player, where a desktop browser lacks it: "Samsung player". */
  player: string
  /** How to open the browser preview of this build: "pnpm dev:tizen". */
  preview: string
}

export type StorageNames = {
  servers: string
  device: string
  copies: string
  recentSearches: string
  remindersSeen: string
}
