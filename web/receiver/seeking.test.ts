import { describe, expect, it } from 'vitest'
import { interceptSeek, rememberedPosition, seekStaysInPlayer } from './seeking'

describe('CAF standard seeks', () => {
  it('returns a cold VOD seek to CAF instead of seeking again from the interceptor', () => {
    expect(interceptSeek(120, 0, true, 30)).toEqual({ fileS: 120, reload: false })
  })

  it('keeps a seek inside an offset event playlist in player time and records the file position', () => {
    expect(interceptSeek(12, 60, false, 40)).toEqual({ fileS: 72, reload: false })
  })

  it('reloads a seek beyond what an event playlist has produced, or before its start', () => {
    expect(interceptSeek(90, 60, false, 40)).toEqual({ fileS: 150, reload: true })
    expect(interceptSeek(-10, 60, false, 40)).toEqual({ fileS: 50, reload: true })
    expect(interceptSeek(90, 0, false, 40)).toEqual({ fileS: 90, reload: true })
  })

  it('leaves absent and invalid times to CAF validation', () => {
    for (const t of [undefined, NaN, Infinity]) expect(interceptSeek(t, 0, true, 30)).toBeUndefined()
  })

  it('treats a whole-title playlist as seekable anywhere, even before it has loaded', () => {
    expect(seekStaysInPlayer(400, 0, true, 0)).toBe(true)
    expect(seekStaysInPlayer(400, 30, true, 100)).toBe(false)
  })
})

it('retains progress after CAF clears its clock, including a deliberate backward seek', () => {
  expect(rememberedPosition(330, 342.5, 'PLAYING')).toBe(342.5)
  expect(rememberedPosition(342.5, 0, 'IDLE')).toBe(342.5)
  expect(rememberedPosition(120, 0, 'BUFFERING')).toBe(120)
  expect(rememberedPosition(120, 12, 'PAUSED')).toBe(12)
  expect(rememberedPosition(12, 0, 'PLAYING')).toBe(0)
  expect(rememberedPosition(12, NaN, 'PLAYING')).toBe(12)
})
