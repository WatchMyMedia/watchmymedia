import { describe, expect, it } from 'vitest'
import { liveBackAction, liveKeyAction, numberKey, type LiveKeyState } from './liveRemote'
import { channelNumberHint, editChannelNumber, exactChannel } from '../../player'
import type { LiveChannel } from '../../../src/api/liveTV'

const hidden: LiveKeyState = { controls: false, terminal: false, prompt: false }
const shown: LiveKeyState = { ...hidden, controls: true }

describe('the Live TV player’s remote', () => {
  it('changes channel with ▲ ▼ and CH ± while the controls are hidden', () => {
    expect(liveKeyAction('up', hidden)).toBe('channelUp')
    expect(liveKeyAction('down', hidden)).toBe('channelDown')
    expect(liveKeyAction('channelUp', shown)).toBe('channelUp')
    expect(liveKeyAction('up', shown)).toBe('swallow')
    expect(liveKeyAction('down', shown)).toBe('swallow')
    expect(liveKeyAction('previousChannel', hidden)).toBe('previousChannel')
  })

  it('opens On now with ◀ and the controls with OK or ▶', () => {
    expect(liveKeyAction('left', hidden)).toBe('onNow')
    expect(liveKeyAction('ok', hidden)).toBe('controls')
    expect(liveKeyAction('right', hidden)).toBe('controls')
    expect(liveKeyAction('ok', shown)).toBe('pass')
    expect(liveKeyAction('right', { ...hidden, panel: 'onNow' })).toBe('onNow')
  })

  it('opens Go to channel with a digit, and leaves digits to it once open', () => {
    expect(liveKeyAction('digit', hidden)).toBe('number')
    expect(liveKeyAction('digit', { ...hidden, panel: 'number' })).toBe('pass')
    expect(numberKey('digit', 7)).toBe('7')
    expect(numberKey('minus')).toBe('.')
    expect(numberKey('ok')).toBeUndefined()
  })

  it('answers Still watching with any press, and lets moving reach its buttons', () => {
    const prompt = { ...hidden, prompt: true }
    expect(liveKeyAction('playPause', prompt)).toBe('keepWatching')
    expect(liveKeyAction('digit', prompt)).toBe('keepWatching')
    expect(liveKeyAction('left', prompt)).toBe('pass')
    expect(liveKeyAction('stop', prompt)).toBe('leave')
  })

  it('keeps only moving and choosing on a problem, and Guide anywhere', () => {
    const terminal = { ...hidden, terminal: true }
    expect(liveKeyAction('channelUp', terminal)).toBe('swallow')
    expect(liveKeyAction('ok', terminal)).toBe('pass')
    expect(liveKeyAction('guide', terminal)).toBe('guide')
  })

  it('walks Return back: the prompt, the panel, the controls, then leaves', () => {
    expect(liveBackAction({ ...hidden, prompt: true, panel: 'quality' })).toBe('keepWatching')
    expect(liveBackAction({ ...shown, panel: 'quality' })).toBe('closePanel')
    expect(liveBackAction(shown)).toBe('hideControls')
    expect(liveBackAction(hidden)).toBe('leave')
    expect(liveBackAction({ ...shown, terminal: true })).toBe('leave')
  })
})

describe('Go to channel', () => {
  const channels = [
    { id: 'a', number: '7.1', name: 'LKSD-HD', available: true },
    { id: 'b', number: '7.2', name: 'Driftwood', available: true },
    { id: 'c', number: '20.1', name: 'Cinema Vault', available: true },
  ] as LiveChannel[]
  it('tunes only an exact number, and says what is typed', () => {
    let typed = ''
    for (const k of ['2', '0', '.', '1']) typed = editChannelNumber(typed, k)
    expect(typed).toBe('20.1')
    expect(exactChannel(channels, typed)?.name).toBe('Cinema Vault')
    expect(exactChannel(channels, '7')).toBeUndefined()
    expect(channelNumberHint(channels, '7')).toBe('Keep entering the full channel number.')
    expect(channelNumberHint(channels, '9')).toBe('No channel 9. Check the number and try again.')
  })
})
