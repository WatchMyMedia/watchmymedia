import { describe, expect, it } from 'vitest'
import { moveKey, playerSubtitleSize, sizeToMove } from './subtitleSizeMove'

const prefs = (size?: string) => ({ subtitles: { languages: [], mode: 'off', automaticDownload: false, ...(size && { size }) } })

describe('subtitle size moving up to the person', () => {
  it('moves this TV’s own size once, to a person who never chose one, on a server from level 35', () => {
    expect(sizeToMove(35, prefs(), 'largest', false)).toBe('largest')
    expect(sizeToMove(35, prefs(), 'smaller', false)).toBe('smaller')
    // Once done, never again.
    expect(sizeToMove(35, prefs(), 'larger', true)).toBeUndefined()
  })

  it('moves nothing when the TV is on Normal or the person chose a size, Normal included', () => {
    expect(sizeToMove(35, prefs(), 'normal', false)).toBeUndefined()
    expect(sizeToMove(35, prefs('normal'), 'larger', false)).toBeUndefined()
    expect(sizeToMove(35, prefs('smaller'), 'larger', false)).toBeUndefined()
  })

  it('waits for the preferences, and asks a server below level 35 nothing', () => {
    expect(sizeToMove(35, undefined, 'larger', false)).toBeUndefined()
    expect(sizeToMove(34, prefs('normal'), 'larger', false)).toBeUndefined()
    expect(sizeToMove(undefined, prefs('normal'), 'larger', false)).toBeUndefined()
  })

  it('draws the person’s size from level 35, and the TV’s own below it or before the preferences come', () => {
    expect(playerSubtitleSize(35, prefs('largest'), 'smaller')).toBe('largest')
    expect(playerSubtitleSize(35, prefs(), 'smaller')).toBe('normal')
    expect(playerSubtitleSize(35, undefined, 'smaller')).toBe('smaller')
    expect(playerSubtitleSize(34, prefs('largest'), 'smaller')).toBe('smaller')
  })

  it('remembers a move per person per server', () => {
    expect(moveKey('srv-1', 7)).toBe('srv-1/7')
    expect(moveKey('srv-1', 7)).not.toBe(moveKey('srv-2', 7))
  })
})
