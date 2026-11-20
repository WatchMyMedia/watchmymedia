import { describe, expect, it, vi } from 'vitest'
import configXml from '../public/config.xml?raw'
import { actionFor, HoldDetector, HOLD_MS, REGISTERED_KEYS } from './keys'
import { TV_APP_VERSION } from '../session/device'

const key = (keyCode: number, key = '', repeat = false) => ({ keyCode, key, repeat })

describe('remote keys', () => {
  it('maps Samsung key codes and the preview keyboard to the same actions', () => {
    expect(actionFor(key(10009))).toEqual({ action: 'back', repeat: false })
    expect(actionFor(key(27, 'Escape'))?.action).toBe('back')
    expect(actionFor(key(8, 'Backspace'))?.action).toBe('back')
    expect(actionFor(key(427))?.action).toBe('channelUp')
    expect(actionFor(key(33, 'PageUp'))?.action).toBe('channelUp')
    expect(actionFor(key(34, 'PageDown'))?.action).toBe('channelDown')
    expect(actionFor(key(10252))?.action).toBe('playPause')
    expect(actionFor(key(415))?.action).toBe('play')
    expect(actionFor(key(413))?.action).toBe('stop')
    expect(actionFor(key(412))?.action).toBe('rewind')
    expect(actionFor(key(417))?.action).toBe('fastForward')
    expect(actionFor(key(55, '7'))).toEqual({ action: 'digit', digit: 7, repeat: false })
    expect(actionFor(key(189))?.action).toBe('minus')
    expect(actionFor(key(40, 'ArrowDown', true))).toEqual({ action: 'down', repeat: true })
    expect(actionFor(key(13, 'Enter'))?.action).toBe('ok')
    expect(actionFor(key(65, 'a'))).toBeUndefined()
  })

  it('asks Tizen for the media, channel and number keys', () => {
    for (const name of ['MediaPlayPause', 'ChannelUp', 'ChannelDown', '0', '9', 'Info']) expect(REGISTERED_KEYS).toContain(name)
  })

  it('tells OK from Hold OK, ignoring the repeats a held key sends', () => {
    vi.useFakeTimers()
    try {
      const held = vi.fn()
      const d = new HoldDetector(held)
      d.keydown()
      vi.advanceTimersByTime(HOLD_MS - 1)
      expect(d.keyup()).toBe(true)
      expect(held).not.toHaveBeenCalled()
      d.keydown()
      vi.advanceTimersByTime(300)
      d.keydown()
      vi.advanceTimersByTime(300)
      d.keydown()
      expect(held).toHaveBeenCalledTimes(1)
      expect(d.keyup()).toBe(false)
      expect(d.keyup()).toBe(false)
      d.keydown(); d.cancel()
      vi.advanceTimersByTime(HOLD_MS)
      expect(held).toHaveBeenCalledTimes(1)
    } finally { vi.useRealTimers() }
  })

  it('keeps config.xml at the version the app reports', () => {
    expect(configXml).toContain(`version="${TV_APP_VERSION}"`)
  })
})
