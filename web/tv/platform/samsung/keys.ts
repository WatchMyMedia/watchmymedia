import type { Action } from '../../app/keys'

/** Tizen's key codes (Samsung remote-control guide). */
export const SAMSUNG_KEY_CODES: Readonly<Record<number, Action>> = {
  37: 'left', 38: 'up', 39: 'right', 40: 'down', 13: 'ok', 10009: 'back',
  10252: 'playPause', 415: 'play', 19: 'pause', 413: 'stop', 412: 'rewind', 417: 'fastForward',
  10232: 'previous', 10233: 'next', 427: 'channelUp', 428: 'channelDown', 10190: 'previousChannel',
  189: 'minus', 457: 'info', 458: 'guide',
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
/** The remote's Return, which also cancels typing. */
export const RETURN = 10009
