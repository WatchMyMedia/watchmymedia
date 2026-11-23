import { describe, expect, it } from 'vitest'
import { filesChips, filesQueryOf, filesSummary } from './libraryFiles'

const lib = { fileCount: 236, unmatchedCount: 3, pendingCount: 0, missingCount: 0, lastScannedAt: '' }

describe('the Files page’s summary', () => {
  it('says what is matched, what needs a match and what is missing', () => {
    expect(filesSummary(lib)).toEqual([
      { text: '236 files' },
      { text: '233 matched' },
      { text: '3 need a match', warn: true },
      { text: 'none missing' },
      { text: 'not scanned yet' },
    ])
  })

  it('counts files waiting for a first match as needing one, and says what is missing', () => {
    const parts = filesSummary({ ...lib, unmatchedCount: 0, pendingCount: 1, missingCount: 2, lastScannedAt: new Date().toISOString() })
    expect(parts.map((p) => p.text)).toEqual(['236 files', '235 matched', '1 needs a match', '2 missing', 'scanned just now'])
  })

  it('says all matched when nothing needs one', () => {
    expect(filesSummary({ ...lib, unmatchedCount: 0 })[1]).toEqual({ text: 'all matched' })
    expect(filesSummary({ ...lib, fileCount: 0, unmatchedCount: 0 }).map((p) => p.text)).toEqual(['0 files', 'none missing', 'not scanned yet'])
  })
})

describe('the Files page’s filters', () => {
  it('offers one choice at a time, with counts where the library has them', () => {
    expect(filesChips(lib)).toEqual([
      { value: 'all', label: 'All', count: 236 },
      { value: 'matched', label: 'Matched', count: 233 },
      { value: 'unmatched', label: 'Needs a match', count: 3 },
      { value: 'failed', label: 'Match failed' },
      { value: 'missing', label: 'Missing', count: 0 },
    ])
    expect(filesChips({ ...lib, pendingCount: 4 }).map((c) => c.value)).toContain('pending')
  })

  it('asks the files reply for each choice', () => {
    expect(filesQueryOf('all')).toEqual({})
    expect(filesQueryOf('missing')).toEqual({ status: 'missing' })
    expect(filesQueryOf('unmatched')).toEqual({ status: 'present', match: 'unmatched' })
  })
})
