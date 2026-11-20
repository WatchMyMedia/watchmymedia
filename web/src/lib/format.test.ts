import { describe, expect, it } from 'vitest'
import { knownLanguage, languageName } from './format'

describe('languageName', () => {
  it('names a language, and calls a missing or undetermined one unknown, never "root"', () => {
    expect(languageName('en')).toBe('English')
    expect(languageName('fra')).toBe('French')
    expect(languageName(undefined)).toBe('Unknown language')
    expect(languageName('und')).toBe('Unknown language')
    expect(languageName('UND')).toBe('Unknown language')
  })

  it('keeps only tags that name a language', () => {
    expect(knownLanguage('en')).toBe('en')
    expect(knownLanguage('und')).toBeUndefined()
    expect(knownLanguage('')).toBeUndefined()
    expect(knownLanguage(null)).toBeUndefined()
  })
})
