import { describe, expect, it } from 'vitest'
import { onAppPlatform, pairingKind } from './pairing'

const pixel = 'Mozilla/5.0 (Linux; Android 16; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36'
const iphone = 'Mozilla/5.0 (iPhone; CPU iPhone OS 26_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.1 Mobile/15E148 Safari/604.1'
const mac = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.1 Safari/605.1.15'
const windows = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36'

describe('pairingKind', () => {
  it('tells TVs from phone and tablet apps', () => {
    expect(pairingKind('androidtv')).toBe('tv')
    expect(pairingKind('tvos')).toBe('tv')
    expect(pairingKind('tizen')).toBe('tv')
    expect(pairingKind('android')).toBe('app')
    expect(pairingKind('ios')).toBe('app')
    expect(pairingKind(undefined)).toBe('device')
    expect(pairingKind('roku')).toBe('device')
  })
})

describe('onAppPlatform', () => {
  it('matches a phone browser to its own platform', () => {
    expect(onAppPlatform('android', pixel, 5)).toBe(true)
    expect(onAppPlatform('ios', iphone, 5)).toBe(true)
    expect(onAppPlatform('ios', pixel, 5)).toBe(false)
    expect(onAppPlatform('android', iphone, 5)).toBe(false)
  })

  it('counts a touch-screen Mac as an iPad, not a desktop Mac', () => {
    expect(onAppPlatform('ios', mac, 5)).toBe(true)
    expect(onAppPlatform('ios', mac, 0)).toBe(false)
  })

  it('never offers the app on a desktop or for a TV', () => {
    expect(onAppPlatform('android', windows, 0)).toBe(false)
    expect(onAppPlatform('androidtv', pixel, 5)).toBe(false)
    expect(onAppPlatform(undefined, pixel, 5)).toBe(false)
  })
})
