import { describe, expect, it, vi } from 'vitest'
import { SOUND_ROWS, changeSound, soundAndSubtitlesOn, soundFailed, soundLevel, soundOf, soundPatch } from './sound'

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

describe('changing the sound in a player', () => {
  const saved = { dialogueBoost: 'off', reduceLoudSounds: 'medium' } as const

  it('saves the one setting, plays on with it, then says it saved', async () => {
    const steps: string[] = []
    const ok = await changeSound('dialogueBoost', 'high', saved, {
      save: async (patch) => { steps.push(`save ${JSON.stringify(patch)}`) },
      apply: async () => { steps.push('reload') },
      report: (s) => steps.push(s.phase),
    })
    expect(ok).toBe(true)
    expect(steps).toEqual(['busy', 'save {"audio":{"dialogueBoost":"high"}}', 'reload', 'ok'])
  })

  it('reloads nothing when the save is refused, and names the value it stayed at', async () => {
    const reports: unknown[] = []
    const apply = vi.fn(async () => undefined)
    const ok = await changeSound('reduceLoudSounds', 'off', saved, { save: () => Promise.reject(new Error('500')), apply, report: (s) => reports.push(s) })
    expect(ok).toBe(false)
    expect(apply).not.toHaveBeenCalled()
    expect(reports.at(-1)).toEqual({ setting: 'reduceLoudSounds', phase: 'err', stayed: 'medium' })
  })

  it('counts a saved setting as saved even when the reload fails', async () => {
    const reports: { phase: string }[] = []
    await changeSound('dialogueBoost', 'medium', saved, { save: async () => undefined, apply: () => Promise.reject(new Error('gone')), report: (s) => reports.push(s) })
    expect(reports.map((r) => r.phase)).toEqual(['busy', 'ok'])
  })
})
