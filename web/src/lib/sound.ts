import type { components } from '../api/schema'

type Preferences = components['schemas']['Preferences']
type Patch = components['schemas']['UpdatePreferencesInputBody']

/**
 * Boost dialogue and Reduce loud sounds: two settings of the person's, kept
 * on the server, which applies them when it decides how a play goes. The
 * apps only save them, and reload a play to hear a change. Every app uses
 * these words.
 */

/** The server level that keeps the sound settings, a subtitle size and subtitle timings. Older servers refuse the fields: send none and hide the controls. */
export const SOUND_LEVEL = 35

export const soundAndSubtitlesOn = (apiLevel: number | undefined) => (apiLevel ?? 0) >= SOUND_LEVEL

export type SoundLevel = 'off' | 'medium' | 'high'
export type SoundSetting = 'dialogueBoost' | 'reduceLoudSounds'
export type Sound = Record<SoundSetting, SoundLevel>

export const SOUND_LEVELS: readonly { id: SoundLevel; label: string }[] = [
  { id: 'off', label: 'Off' },
  { id: 'medium', label: 'Medium' },
  { id: 'high', label: 'High' },
]

export const SOUND_ROWS: readonly { id: SoundSetting; label: string; help: string }[] = [
  { id: 'dialogueBoost', label: 'Boost dialogue', help: 'Makes voices clearer over music and effects.' },
  { id: 'reduceLoudSounds', label: 'Reduce loud sounds', help: 'Brings loud scenes closer to the level of speech, for watching quietly.' },
]

/** Under the two, in Settings and in the player. */
export const SOUND_NOTE = 'While either is on, the server converts the sound. Surround stays; lossless and Dolby Atmos sound come back when both are Off.'

/** In the player while a change reloads the play. */
export const SOUND_CHANGING = 'Changing the sound…'

/** After a change saved and played. */
export const SOUND_SAVED = 'Saved for your account.'

export const soundLabel = (level: SoundLevel) => SOUND_LEVELS.find((l) => l.id === level)?.label ?? 'Off'

/** When a change couldn't be saved: the value it stayed at. */
export const soundFailed = (stayed: SoundLevel) => `Couldn’t change the sound, so it’s still ${soundLabel(stayed)}.`

/** A stored value, read safely: anything unknown, or nothing, is Off. */
export function soundLevel(value: string | undefined | null): SoundLevel {
  return value === 'medium' || value === 'high' ? value : 'off'
}

export function soundOf(p: Pick<Preferences, 'audio'> | undefined): Sound {
  return { dialogueBoost: soundLevel(p?.audio?.dialogueBoost), reduceLoudSounds: soundLevel(p?.audio?.reduceLoudSounds) }
}

/** Only the settings that changed, as PUT /api/me/preferences takes them; null when none did. */
export function soundPatch(saved: Sound, next: Sound): Patch | null {
  const audio: NonNullable<Patch['audio']> = {}
  for (const row of SOUND_ROWS) if (saved[row.id] !== next[row.id]) audio[row.id] = next[row.id]
  return Object.keys(audio).length ? { audio } : null
}
