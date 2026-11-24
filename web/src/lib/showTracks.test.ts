import { describe, expect, it } from 'vitest'
import { hasShowTracks, pickBody, playerPickBody, rememberedChoice, sameLanguage } from './showTracks'

const probe = {
  audio: [
    { index: 1, language: 'eng', codec: 'aac', channels: 2 },
    { index: 2, language: 'jpn', codec: 'aac', channels: 6 },
  ],
  subtitles: [
    { index: 3, language: 'eng', codec: 'subrip', forced: true },
    { index: 4, language: 'eng', codec: 'subrip' },
    { index: 5, language: 'spa', codec: 'subrip' },
  ],
} as never

describe('sameLanguage', () => {
  it('matches two- and three-letter tags and case', () => {
    expect(sameLanguage('en', 'eng')).toBe(true)
    expect(sameLanguage('pt-BR', 'pt-br')).toBe(true)
    expect(sameLanguage('ja', 'jpn')).toBe(true)
  })
  it('keeps languages apart and never matches an untagged track', () => {
    expect(sameLanguage('en', 'spa')).toBe(false)
    expect(sameLanguage('und', 'und')).toBe(false)
    expect(sameLanguage(undefined, 'en')).toBe(false)
  })
})

describe('hasShowTracks', () => {
  it('is true for a language or subtitles off', () => {
    expect(hasShowTracks({ audioLanguage: 'ja', subtitlesOff: false })).toBe(true)
    expect(hasShowTracks({ subtitlesOff: true })).toBe(true)
    expect(hasShowTracks({ subtitlesOff: false })).toBe(false)
    expect(hasShowTracks(undefined)).toBe(false)
  })
})

describe('rememberedChoice', () => {
  it('starts on the file’s tracks in the remembered languages, a full subtitle before a forced one', () => {
    expect(rememberedChoice({ audioLanguage: 'ja', subtitleLanguage: 'en', subtitlesOff: false }, probe)).toEqual({ audio: '2', subtitle: 's4' })
  })
  it('starts with subtitles off', () => {
    expect(rememberedChoice({ subtitlesOff: true }, probe)).toEqual({ audio: '', subtitle: 'off' })
  })
  it('stays on Automatic for a language the file lacks, or without a choice', () => {
    expect(rememberedChoice({ audioLanguage: 'fr', subtitleLanguage: 'de', subtitlesOff: false }, probe)).toEqual({ audio: '', subtitle: '' })
    expect(rememberedChoice(undefined, probe)).toEqual({ audio: '', subtitle: '' })
    expect(rememberedChoice({ audioLanguage: 'ja', subtitlesOff: false }, undefined)).toEqual({ audio: '', subtitle: '' })
  })
})

describe('pickBody', () => {
  const auto = { audio: '', subtitle: '' }
  it('keeps the language of the track picked, and only what changed', () => {
    expect(pickBody(auto, { audio: '2', subtitle: '' }, probe)).toEqual({ audioLanguage: 'jpn' })
    expect(pickBody(auto, { audio: '', subtitle: 's5' }, probe)).toEqual({ subtitleLanguage: 'spa' })
  })
  it('turns subtitles off, or forgets a part set back to Automatic', () => {
    expect(pickBody(auto, { audio: '', subtitle: 'off' }, probe)).toEqual({ subtitlesOff: true })
    expect(pickBody({ audio: '2', subtitle: 's4' }, auto, probe)).toEqual({ audioLanguage: '', subtitleLanguage: '', subtitlesOff: false })
  })
  it('is nothing when nothing changed or the track has no language', () => {
    expect(pickBody(auto, auto, probe)).toBeUndefined()
    expect(pickBody(auto, { audio: '9', subtitle: '' }, probe)).toBeUndefined()
  })
})

describe('playerPickBody', () => {
  it('keeps a pick’s language, or subtitles off', () => {
    expect(playerPickBody({ kind: 'audio', language: 'jpn' })).toEqual({ audioLanguage: 'jpn' })
    expect(playerPickBody({ kind: 'subtitle', language: 'en' })).toEqual({ subtitleLanguage: 'en' })
    expect(playerPickBody({ kind: 'off' })).toEqual({ subtitlesOff: true })
  })
  it('keeps nothing for an untagged track', () => {
    expect(playerPickBody({ kind: 'audio', language: 'und' })).toBeUndefined()
    expect(playerPickBody({ kind: 'subtitle' })).toBeUndefined()
  })
})
