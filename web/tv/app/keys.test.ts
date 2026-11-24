import { describe, expect, it, vi } from 'vitest'
import { actionFor, HoldDetector, HOLD_MS } from './keys'

const key = (keyCode: number, key = '', repeat = false) => ({ keyCode, key, repeat })

describe('remote keys', () => {
  it('maps the TV’s key codes (Samsung’s in tests) and the preview keyboard to the same actions', () => {
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
})
