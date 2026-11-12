import { describe, expect, it } from 'vitest'
import type { Profile } from './capabilities'
import { forApiLevel, hdrFields } from './hdr'

const all = () => true

describe('hdrFields', () => {
  it('lists what the screen shows, and the dovi keys it decodes', () => {
    expect(hdrFields({ hdr10: true, hlg: true, dolbyVision: true }, all)).toEqual({ hdrTypes: ['hdr10', 'hlg', 'dovi'], doviKeys: ['dovi5', 'dovi8'] })
    expect(hdrFields({ hdr10: true, hlg: true, dolbyVision: true }, (c) => c.startsWith('dvh1.08')).doviKeys).toEqual(['dovi8'])
  })

  it('sends no dovi key to a screen without Dolby Vision, however it decodes', () => {
    expect(hdrFields({ hdr10: true, hlg: false, dolbyVision: false }, all)).toEqual({ hdrTypes: ['hdr10'], doviKeys: [] })
  })

  it('gives an SDR screen an empty list, which still says the profile follows the plan', () => {
    expect(hdrFields({ hdr10: false, hlg: false, dolbyVision: false }, all)).toEqual({ hdrTypes: [], doviKeys: [] })
  })
})

describe('forApiLevel', () => {
  const p: Profile = { name: 'cast', videoCodecs: ['h264', 'hevc', 'hevc10', 'dovi5', 'dovi8'], hdr: true, hdrTypes: ['hdr10', 'hlg', 'dovi'] }

  it('keeps the profile from level 8', () => {
    expect(forApiLevel(p, 8)).toBe(p)
    expect(forApiLevel(p, 11)).toBe(p)
  })

  it('drops hdrTypes and the dovi keys below it, and keeps the rest', () => {
    expect(forApiLevel(p, 7)).toEqual({ name: 'cast', videoCodecs: ['h264', 'hevc', 'hevc10'], hdr: true })
    expect(forApiLevel({ name: 'cast' }, 0)).toEqual({ name: 'cast' })
  })
})
