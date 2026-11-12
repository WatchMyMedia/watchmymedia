import { describe, expect, it } from 'vitest'
import {
  automaticAvailable, choiceOf, draftOf, emptySubtitleText, foreignAudioTurnedOn, languageList, languagePatch, moveLanguage, subtitleChoices, subtitleFields,
  type LanguageDraft,
} from './languagePreferences'

const draft = (over: Partial<LanguageDraft> = {}): LanguageDraft => ({ audio: [], subtitles: [], choice: 'forced', automaticDownload: false, ...over })
const saved = (subtitles: { languages?: string[]; mode: string; foreignAudio?: boolean; automaticDownload?: boolean }, audio?: string[]) => ({
  skip: {}, autoplayNext: true,
  subtitles: { languages: [], automaticDownload: false, ...subtitles },
  ...(audio ? { audio: { languages: audio } } : {}),
})

describe('subtitle choices', () => {
  it('reads foreign as forced with foreignAudio, and writes it back the same way', () => {
    expect(choiceOf({ mode: 'forced', foreignAudio: true })).toBe('foreign')
    expect(choiceOf({ mode: 'forced' })).toBe('forced')
    expect(choiceOf({ mode: 'on', foreignAudio: true })).toBe('on')
    expect(choiceOf({ mode: 'off' })).toBe('off')
    expect(subtitleFields('foreign')).toEqual({ mode: 'forced', foreignAudio: true })
    expect(subtitleFields('on')).toEqual({ mode: 'on', foreignAudio: false })
    for (const c of subtitleChoices) expect(choiceOf(subtitleFields(c.id))).toBe(c.id)
  })

  it('lists the four choices in the plan’s order with their short labels', () => {
    expect(subtitleChoices.map((c) => c.id)).toEqual(['off', 'forced', 'foreign', 'on'])
    expect(subtitleChoices.find((c) => c.id === 'foreign')?.short).toBe('For foreign audio')
  })

  it('reads a server without audio languages as an empty list', () => {
    expect(draftOf(saved({ mode: 'on', languages: ['en'] }))).toEqual({ audio: [], subtitles: ['en'], choice: 'on', automaticDownload: false })
  })
})

describe('what a save sends', () => {
  it('sends nothing when nothing changed', () => {
    expect(languagePatch(draft({ audio: ['es'] }), draft({ audio: ['es'] }))).toBeNull()
  })

  it('sends a new audio list without the subtitle mode, so the server can choose foreign audio', () => {
    expect(languagePatch(draft(), draft({ audio: ['es', 'en'] }))).toEqual({ audio: { languages: ['es', 'en'] } })
  })

  it('sends both mode and foreignAudio when the choice changes', () => {
    expect(languagePatch(draft(), draft({ choice: 'foreign' }))).toEqual({ subtitles: { mode: 'forced', foreignAudio: true } })
    expect(languagePatch(draft({ choice: 'foreign' }), draft({ choice: 'on' }))).toEqual({ subtitles: { mode: 'on', foreignAudio: false } })
  })

  it('sends reordered and cleared lists and the download switch', () => {
    expect(languagePatch(draft({ subtitles: ['en', 'es'] }), draft({ subtitles: ['es', 'en'], automaticDownload: true }))).toEqual({ subtitles: { languages: ['es', 'en'], automaticDownload: true } })
    expect(languagePatch(draft({ audio: ['es'] }), draft())).toEqual({ audio: { languages: [] } })
  })
})

describe('the one-time notice', () => {
  it('shows when the server turned on foreign audio for a first audio language', () => {
    const before = draft()
    const patch = languagePatch(before, draft({ audio: ['es'] }))!
    expect(foreignAudioTurnedOn(before, patch, saved({ mode: 'forced', foreignAudio: true }, ['es']))).toBe(true)
  })

  it('stays quiet when the person chose it, or it was already on, or the server left it off', () => {
    const before = draft()
    const chosen = languagePatch(before, draft({ audio: ['es'], choice: 'foreign' }))!
    expect(foreignAudioTurnedOn(before, chosen, saved({ mode: 'forced', foreignAudio: true }, ['es']))).toBe(false)
    const already = draft({ choice: 'foreign' })
    expect(foreignAudioTurnedOn(already, languagePatch(already, draft({ choice: 'foreign', audio: ['es'] }))!, saved({ mode: 'forced', foreignAudio: true }, ['es']))).toBe(false)
    expect(foreignAudioTurnedOn(before, languagePatch(before, draft({ audio: ['es'] }))!, saved({ mode: 'forced' }, ['es']))).toBe(false)
  })
})

describe('language lists', () => {
  it('keeps canonical tags without repeats', () => {
    expect(languageList([' es', 'pt-br', 'en', 'ES '], 'audio')).toEqual(['es', 'pt-BR', 'en'])
    expect(languageList([], 'audio')).toEqual([])
  })

  it('refuses bad codes and more than 16, naming the list', () => {
    expect(() => languageList(['not a language'], 'audio')).toThrow('code such as')
    const seventeen = ['en', 'es', 'fr', 'de', 'it', 'pt', 'nl', 'ar', 'ur', 'hi', 'ja', 'ko', 'zh', 'pl', 'ru', 'tr', 'sv']
    expect(() => languageList(seventeen, 'audio')).toThrow('16 audio languages')
  })

  it('moves within bounds', () => {
    expect(moveLanguage(['es', 'en', 'ja'], 1, -1)).toEqual(['en', 'es', 'ja'])
    expect(moveLanguage(['es', 'en'], 0, -1)).toEqual(['es', 'en'])
    expect(moveLanguage(['es', 'en'], 1, 1)).toEqual(['es', 'en'])
  })
})

describe('Automatic download and the empty subtitle list', () => {
  it('needs On or foreign audio, and a subtitle language', () => {
    expect(automaticAvailable(draft({ choice: 'foreign', subtitles: ['en'] }))).toBe(true)
    expect(automaticAvailable(draft({ choice: 'on', subtitles: ['en'] }))).toBe(true)
    expect(automaticAvailable(draft({ choice: 'forced', subtitles: ['en'] }))).toBe(false)
    expect(automaticAvailable(draft({ choice: 'on' }))).toBe(false)
  })

  it('says subtitles fall back to the audio languages only when they will', () => {
    expect(emptySubtitleText(draft({ choice: 'foreign', audio: ['es'] }))).toContain('use your audio languages')
    expect(emptySubtitleText(draft({ choice: 'on', audio: ['es'] }))).toBe('No subtitle language yet. Add one below.')
  })
})
