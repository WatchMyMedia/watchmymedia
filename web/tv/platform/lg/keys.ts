import type { Action } from '../../app/keys'

/**
 * The LG remotes' key codes (LG's Magic Remote guide). The Magic Remote
 * has the arrows, OK (its wheel) and Back; conventional LG remotes add the
 * media keys. LG gives apps no channel or number keys: if CH ▲▼ ever
 * arrive, they come as Page Up and Page Down.
 */
export const LG_KEY_CODES: Readonly<Record<number, Action>> = {
  37: 'left', 38: 'up', 39: 'right', 40: 'down', 13: 'ok', 461: 'back',
  415: 'play', 19: 'pause', 413: 'stop', 412: 'rewind', 417: 'fastForward',
  33: 'channelUp', 34: 'channelDown',
}

/** The remote's Back, which also ends typing in a field (appinfo.json's disableBackHistoryAPI sends it to the app). */
export const BACK_KEY = 461

/**
 * webOS repeats a held key about 40 times a second (Kodi's webOS port
 * measured it). The app acts on one repeat every 100 ms, the pace the
 * Samsung app's rows and held seeking were tuned for.
 */
export const REPEAT_MS = 100
