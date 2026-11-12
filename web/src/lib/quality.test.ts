import { afterEach, describe, expect, it } from 'vitest'
import { QUALITIES, applyQuality, autoNow, qualitiesFor, qualityById, saveQuality, savedQuality, stepName } from './quality'
import type { Profile } from './capabilities'

const store = new Map<string, string>()
Object.defineProperty(globalThis, 'localStorage', {
  value: { getItem: (k: string) => store.get(k) ?? null, setItem: (k: string, v: string) => void store.set(k, v) },
  configurable: true,
})
afterEach(() => store.clear())

describe('qualities', () => {
  it('offer Automatic first, from API level 3', () => {
    expect(qualitiesFor(3)[0].id).toBe('auto')
    expect(qualitiesFor(4)[0].id).toBe('auto')
    expect(qualitiesFor(2).map((q) => q.id)).toEqual(['original', '2160', '1080', '720', '480', '360'])
    expect(qualitiesFor(undefined).some((q) => q.id === 'auto')).toBe(false)
  })
  it('play an unknown ID, or Automatic on an older server, as Original', () => {
    expect(qualityById('auto', 3).id).toBe('auto')
    expect(qualityById('auto', 2).id).toBe('original')
    expect(qualityById('1080-8', 3).id).toBe('original')
    expect(qualityById('360', 2).id).toBe('360')
  })
})

describe('savedQuality', () => {
  it('is Automatic when nothing was picked', () => {
    expect(savedQuality(3).id).toBe('auto')
    expect(savedQuality(2).id).toBe('original')
  })
  it('keeps a pick', () => {
    saveQuality(QUALITIES.find((q) => q.id === '720')!)
    expect(savedQuality(3).id).toBe('720')
    saveQuality(QUALITIES[0])
    expect(savedQuality(3).id).toBe('auto')
    expect(savedQuality(2).id).toBe('original')
  })
})

describe('labels', () => {
  it('name the step Automatic is at', () => {
    expect(autoNow('720')).toBe('Now 720p · 4 Mbps')
    expect(autoNow('original')).toBe('Now original quality')
    expect(stepName('2160')).toBe('4K')
    expect(stepName('360')).toBe('360p')
    expect(stepName('original')).toBe('Original')
  })
})

describe('applyQuality', () => {
  const p = { maxWidth: 3840, maxHeight: 2160, maxBitrate: 0 } as Profile
  it('sends the profile uncapped at Automatic', () => {
    expect(applyQuality(p, qualityById('auto', 3))).toMatchObject({ maxWidth: 3840, maxHeight: 2160, maxBitrate: 0 })
  })
  it('caps it at a step', () => {
    expect(applyQuality(p, qualityById('360', 3))).toMatchObject({ maxWidth: 640, maxHeight: 360, maxBitrate: 1_000_000 })
  })
})
