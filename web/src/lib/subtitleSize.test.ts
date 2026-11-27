import { describe, expect, it } from 'vitest'
import { SUBTITLE_SCALE, SUBTITLE_SIZES, cueFontPx, pictureBox, subtitleSize, subtitleSizeOf } from './subtitleSize'

describe('subtitle size', () => {
  it('has the four steps, scaled as the TVs’ 22, 28, 34 and 42 over 28', () => {
    expect(SUBTITLE_SIZES.map((s) => s.label)).toEqual(['Smaller', 'Normal', 'Larger', 'Largest'])
    for (const [size, px] of [['smaller', 22], ['normal', 28], ['larger', 34], ['largest', 42]] as const) {
      expect(Math.abs(SUBTITLE_SCALE[size] * 28 - px)).toBeLessThan(0.15)
    }
  })

  it('reads anything unknown, or nothing, as Normal', () => {
    expect(subtitleSize(undefined)).toBe('normal')
    expect(subtitleSize('huge')).toBe('normal')
    expect(subtitleSize('largest')).toBe('largest')
    expect(subtitleSizeOf({ subtitles: { languages: [], mode: 'off', automaticDownload: false, size: 'smaller' } })).toBe('smaller')
  })

  it('draws cues at 4.2% of the picture’s height, never under 14 px, times the step', () => {
    expect(cueFontPx(810, 'normal')).toBeCloseTo(34.02)
    expect(cueFontPx(1080, 'largest')).toBeCloseTo(68.04)
    expect(cueFontPx(219, 'normal')).toBe(14)
    expect(cueFontPx(219, 'smaller')).toBeCloseTo(11.06)
    expect(cueFontPx(219, 'larger')).toBeCloseTo(16.94)
  })

  it('finds the picture inside a box with bars', () => {
    expect(pictureBox(1440, 810, 1920, 1080)).toEqual({ left: 0, top: 0, width: 1440, height: 810 })
    const bars = pictureBox(1000, 1000, 1920, 1080)
    expect([bars.left, bars.top, bars.width, bars.height].map((n) => Math.round(n * 100) / 100)).toEqual([0, 218.75, 1000, 562.5])
    expect(pictureBox(1000, 500, 1000, 1000)).toEqual({ left: 250, top: 0, width: 500, height: 500 })
    expect(pictureBox(800, 450, 0, 0)).toEqual({ left: 0, top: 0, width: 800, height: 450 })
  })
})
