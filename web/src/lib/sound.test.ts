import { describe, expect, it } from 'vitest'
import { SOUND_ROWS, soundAndSubtitlesOn, soundFailed, soundLevel, soundOf, soundPatch } from './sound'

describe('sound settings', () => {
  it('reads anything unknown, or nothing, as Off', () => {
    expect(soundLevel(undefined)).toBe('off')
    expect(soundLevel('loud')).toBe('off')
    expect(soundLevel('high')).toBe('high')
    expect(soundOf(undefined)).toEqual({ dialogueBoost: 'off', reduceLoudSounds: 'off' })
    expect(soundOf({ audio: { languages: [], dialogueBoost: 'medium' } })).toEqual({ dialogueBoost: 'medium', reduceLoudSounds: 'off' })
  })

  it('sends only what changed, as partial audio fields', () => {
    const saved = { dialogueBoost: 'off', reduceLoudSounds: 'medium' } as const
    expect(soundPatch(saved, saved)).toBeNull()
    expect(soundPatch(saved, { ...saved, dialogueBoost: 'high' })).toEqual({ audio: { dialogueBoost: 'high' } })
    expect(soundPatch(saved, { dialogueBoost: 'medium', reduceLoudSounds: 'off' })).toEqual({ audio: { dialogueBoost: 'medium', reduceLoudSounds: 'off' } })
  })

  it('names the value a failed change stayed at', () => {
    expect(soundFailed('off')).toBe('Couldn’t change the sound, so it’s still Off.')
    expect(soundFailed('medium')).toBe('Couldn’t change the sound, so it’s still Medium.')
  })

  it('is offered only from API level 35', () => {
    expect(soundAndSubtitlesOn(undefined)).toBe(false)
    expect(soundAndSubtitlesOn(34)).toBe(false)
    expect(soundAndSubtitlesOn(35)).toBe(true)
    expect(SOUND_ROWS.map((r) => r.label)).toEqual(['Boost dialogue', 'Reduce loud sounds'])
  })
})
