import { describe, expect, it } from 'vitest'
import { libraryStatusLine } from './libraryStatus'

const libs = [
  { id: 1, name: 'Movies', lastScannedAt: '2026-11-23T10:00:00Z', unmatchedCount: 0, missingCount: 0 },
  { id: 2, name: 'TV Shows', lastScannedAt: '2026-11-23T12:00:00Z', unmatchedCount: 0, missingCount: 0 },
]

describe('library status line', () => {
  it('says what is being scanned, with the share done', () => {
    expect(libraryStatusLine(libs, { 1: { scanned: 148, total: 236 } }, {})).toEqual({ text: 'Scanning Movies · 148 of 236 files', progress: 63 })
  })

  it('says what is being matched', () => {
    expect(libraryStatusLine(libs, {}, { 2: { done: 3, total: 4 } })).toEqual({ text: 'Matching TV Shows · 3 of 4', progress: 75 })
  })

  it('counts unmatched and missing files when nothing is under way', () => {
    expect(libraryStatusLine([{ ...libs[0], unmatchedCount: 3, missingCount: 1 }], {}, {}).text).toBe('3 unmatched · 1 missing files')
    expect(libraryStatusLine([{ ...libs[0], unmatchedCount: 1 }], {}, {}).text).toBe('1 unmatched file')
  })

  it('says when the libraries were last scanned', () => {
    expect(libraryStatusLine(libs, {}, {}).text).toMatch(/^Up to date · scanned /)
    expect(libraryStatusLine([{ ...libs[0], lastScannedAt: '' }], {}, {}).text).toBe('Not scanned yet')
  })
})
