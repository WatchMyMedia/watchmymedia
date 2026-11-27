import { clock } from '../../../src/lib/clock'
import { timingWords } from '../../../src/lib/play/subtitleTiming'
import { SOUND_CHANGING, soundFailed } from '../../../src/lib/sound'
import type { SoundSetting } from '../../../src/lib/sound'
import type { FilePlayback, SoundState } from '../../player'

// The player panel's words for the sound settings and subtitle timing, on
// the TV's remote: which keys the foot names, and the lines under a row.

/** The timing row's focus id: ◀ and ▶ on it are Earlier and Later. */
export const TIMING_ROW = 'timing/row'

/** The sound chips' focus ids start with this, then the setting. */
export const SOUND_ROW = 'sound/'

export type PanelFoot = 'timing' | 'chips' | 'sentence'

/** What the panel's foot explains: the keys of the row focused, else the panel's one sentence. */
export function panelFoot(focused: string | undefined): PanelFoot {
  if (focused === TIMING_ROW) return 'timing'
  if (focused?.startsWith(SOUND_ROW)) return 'chips'
  return 'sentence'
}

/** The foot in words for a remote that points (LG's Magic Remote). */
export function pointerFoot(foot: PanelFoot, back: string): string | undefined {
  if (foot === 'timing') return `Click Earlier or Later, or press ◀ ▶. Hold to repeat. ${back} closes this.`
  if (foot === 'chips') return `Click a choice, or press ◀ ▶ and OK. ${back} closes this.`
  return undefined
}

/** Under Reset: what it goes back to. */
export function resetDetail(resetTo: number): string {
  return resetTo === 0 ? 'Back to In step' : `Back to ${timingWords(resetTo)}, set for everyone`
}

/** The line under a sound setting: changing, failed, or what it does. */
export function soundLine(sound: SoundState, setting: SoundSetting, help: string): { kind: 'busy' | 'error' | 'help'; text: string; more?: string } {
  if (sound.busy === setting) return { kind: 'busy', text: SOUND_CHANGING, more: `The video carries on from ${clock(sound.busyFromMs ?? 0)}.` }
  if (sound.failed === setting) return { kind: 'error', text: `${soundFailed(sound.stayed ?? 'off')} Press OK to try again.` }
  return { kind: 'help', text: help }
}

/** ◀ and ▶ on the timing row are Earlier and Later; a held key repeats. */
export function timingKey(action: string, playback: Pick<FilePlayback, 'stepTiming'>): boolean {
  if ((document.activeElement as HTMLElement | null)?.dataset?.focus !== TIMING_ROW) return false
  if (action === 'left') { playback.stepTiming(-1); return true }
  if (action === 'right') { playback.stepTiming(1); return true }
  return false
}

