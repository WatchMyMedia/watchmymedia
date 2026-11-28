import { describe, expect, it, vi } from 'vitest'
import { panelFoot, pointerFoot, resetDetail, soundLine, timingKey, TIMING_ROW } from './soundWords'

describe('the player panel’s sound and timing words', () => {
  it('says the keys of the row focused at the foot', () => {
    expect(panelFoot(TIMING_ROW)).toBe('timing')
    expect(panelFoot('sound/dialogueBoost/medium')).toBe('chips')
    expect(panelFoot('audio/1')).toBe('sentence')
    expect(panelFoot(undefined)).toBe('sentence')
    expect(pointerFoot('timing', 'Back')).toBe('Click Earlier or Later, or press ◀ ▶. Hold to repeat. Back closes this.')
    expect(pointerFoot('chips', 'Back')).toBe('Click a choice, or press ◀ ▶ and OK. Back closes this.')
    expect(pointerFoot('sentence', 'Back')).toBeUndefined()
  })

  it('says what Reset goes back to', () => {
    expect(resetDetail(0)).toBe('Back to In step')
    expect(resetDetail(800)).toBe('Back to 0.8 s later, set for everyone')
  })

  it('says a sound change is under way, from where the video carries on, or what it stayed at', () => {
    const sound = { dialogueBoost: 'off', reduceLoudSounds: 'high' } as const
    expect(soundLine({ ...sound, busy: 'reduceLoudSounds', busyFromMs: 1_450_000 }, 'reduceLoudSounds', 'help')).toEqual({ kind: 'busy', text: 'Changing the sound…', more: 'The video carries on from 24:10.' })
    expect(soundLine({ ...sound, failed: 'dialogueBoost', stayed: 'off' }, 'dialogueBoost', 'help')).toEqual({ kind: 'error', text: 'Couldn’t change the sound, so it’s still Off. Press OK to try again.' })
    expect(soundLine(sound, 'dialogueBoost', 'Makes voices clearer over music and effects.')).toEqual({ kind: 'help', text: 'Makes voices clearer over music and effects.' })
  })

  it('takes ◀ and ▶ as Earlier and Later only on the timing row', () => {
    const playback = { stepTiming: vi.fn() }
    const focused = { activeElement: { dataset: { focus: TIMING_ROW } } as unknown }
    vi.stubGlobal('document', focused)
    expect(timingKey('left', playback)).toBe(true)
    expect(timingKey('right', playback)).toBe(true)
    expect(timingKey('down', playback)).toBe(false)
    expect(playback.stepTiming.mock.calls).toEqual([[-1], [1]])
    focused.activeElement = { dataset: { focus: 'subtitles/s1' } }
    expect(timingKey('left', playback)).toBe(false)
    vi.unstubAllGlobals()
  })
})
