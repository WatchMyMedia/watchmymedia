import { describe, expect, it } from 'vitest'
import { AdaptiveFallback, failureRoute, observedRendition, requestAdaptive, adaptiveRestrictions } from './adaptive'

const offer = { renditions: [{ width: 640, height: 360, bitrate: 1_504_000 }, { width: 1280, height: 720, bitrate: 4_000_000 }] }
describe('adaptive playback contract', () => {
  it('negotiates only Automatic on a capable non-native player', () => {
    expect(requestAdaptive(26, 'auto')).toBe(false)
    expect(requestAdaptive(undefined, 'auto')).toBe(false)
    expect(requestAdaptive(27, '720')).toBe(false)
    expect(requestAdaptive(27, 'auto', true)).toBe(false)
    expect(requestAdaptive(27, 'auto')).toBe(true)
  })
  it('reports only an observed offered rendition, independently of the quality preference', () => {
    expect(observedRendition(offer, undefined)).toBeUndefined()
    expect(observedRendition(offer, 1080)).toBeUndefined()
    expect(observedRendition(undefined, 720)).toBeUndefined()
    expect(observedRendition(offer, NaN)).toBeUndefined()
    expect(observedRendition(offer, 360)).toEqual({ renditionHeight: 360, renditionBitrate: 1_504_000 })
    expect(observedRendition(offer, 720)).toEqual({ renditionHeight: 720, renditionBitrate: 4_000_000 })
    expect(adaptiveRestrictions(offer)).toEqual({ maxHeight: 720, maxBandwidth: 4_000_000 })
  })
  it('clears family caps when returning to a single output', () => {
    expect(adaptiveRestrictions(undefined)).toEqual({ maxHeight: Infinity, maxBandwidth: Infinity })
  })
  it('backs off a failed family across seeks and tracks without consuming another play’s retry', () => {
    const viewing = new AdaptiveFallback()
    expect(viewing.take(undefined)).toBe(false)
    expect(viewing.enabled).toBe(true)
    expect(viewing.take(offer)).toBe(true)
    expect(viewing.take(offer)).toBe(false)
    expect(viewing.enabled).toBe(false)
    expect(new AdaptiveFallback().enabled).toBe(true)
  })

  it('restores a failed switch before any family fallback, and fails only without either', () => {
    expect(failureRoute(true, true)).toBe('switch')
    expect(failureRoute(true, false)).toBe('switch')
    expect(failureRoute(false, true)).toBe('family')
    expect(failureRoute(false, false)).toBe('fail')
  })
})
