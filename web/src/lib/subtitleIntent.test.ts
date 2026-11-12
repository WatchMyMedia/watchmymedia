import { describe, expect, it } from 'vitest'
import { SubtitleIntent, subtitleLanguages, suggestedSubtitleLanguage } from './subtitleIntent'

describe('subtitle choices and delayed results', () => {
  it('asks for server preferences until a manual track is chosen', () => {
    const intent = new SubtitleIntent()
    expect(intent.request()).toEqual({ subtitleMode: 'auto' })
    const pending = intent.capture()
    intent.choose('s2')
    expect(intent.accepts(pending)).toBe(false)
    expect(intent.request()).toEqual({ subtitleMode: 'off', subtitleId: 's2' })
  })

  it('keeps explicit Off across quality reloads and audio changes', () => {
    const intent = new SubtitleIntent()
    const pending = intent.capture()
    intent.choose('')
    expect(intent.accepts(pending)).toBe(false)
    expect(intent.automatic).toBe(false)
    expect(intent.request()).toEqual({ subtitleMode: 'off' })
    intent.invalidate()
    expect(intent.request()).toEqual({ subtitleMode: 'off' })
  })

  it('discards a previous play or manual download after replacement/unmount', () => {
    const intent = new SubtitleIntent()
    intent.choose('d1')
    const result = intent.capture()
    intent.invalidate()
    expect(intent.accepts(result)).toBe(false)
    expect(intent.accepts(intent.capture())).toBe(true)
  })
})

describe('subtitle language preferences', () => {
  it('preserves priority, canonicalizes and removes duplicates', () => {
    expect(subtitleLanguages(' es, pt-br, en, ES ')).toEqual(['es', 'pt-BR', 'en'])
    expect(subtitleLanguages('')).toEqual([])
  })

  it('rejects malformed or excessive language lists before submitting', () => {
    expect(() => subtitleLanguages('not a language')).toThrow('language codes')
    expect(() => subtitleLanguages(Array(17).fill('en').join(','))).toThrow('16')
  })

  it('suggests the first valid browser language without persisting it', () => {
    expect(suggestedSubtitleLanguage(['bad locale', 'pt-br', 'en'])).toBe('pt-BR')
    expect(suggestedSubtitleLanguage(['und', 'bad locale'])).toBeUndefined()
  })
})
