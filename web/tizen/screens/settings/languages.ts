import { commonLanguages, MAX_LANGUAGES, moveLanguage, type LanguageDraft } from '../../../src/lib/languagePreferences'
import { languageName } from '../../../src/lib/format'

// Settings › Audio & subtitles on the TV (SettingsPlayback, Tweak `pane`:
// `audio`): two ordered lists, each language a row whose Options are Move
// up, Move down and Remove, and Add language from a list by name. Each
// change saves at once.

export type LanguageListKind = 'audio' | 'subtitles'
export type LanguageAction = 'up' | 'down' | 'remove' | 'cancel'

export const LIST_TITLES: Record<LanguageListKind, string> = { audio: 'Audio languages', subtitles: 'Subtitle languages' }

/** What a language's panel offers. */
export function languageActions(index: number, count: number): LanguageAction[] {
  return [...(index > 0 ? ['up' as const] : []), ...(index < count - 1 ? ['down' as const] : []), 'remove', 'cancel']
}

export const languageActionLabel = (a: LanguageAction) => ({ up: 'Move up', down: 'Move down', remove: 'Remove', cancel: 'Cancel' }[a])

/** The draft after an action on `tag` in a list, or undefined when nothing changes. */
export function actOn(draft: LanguageDraft, list: LanguageListKind, tag: string, action: LanguageAction): LanguageDraft | undefined {
  const langs = draft[list]
  const index = langs.indexOf(tag)
  if (index < 0 || action === 'cancel') return undefined
  const next = action === 'remove' ? langs.filter((t) => t !== tag) : moveLanguage(langs, index, action === 'up' ? -1 : 1)
  return next.join() === langs.join() ? undefined : { ...draft, [list]: next }
}

/** The draft with `tag` added at the end, or undefined when it is there already or the list is full. */
export function addTo(draft: LanguageDraft, list: LanguageListKind, tag: string): LanguageDraft | undefined {
  const langs = draft[list]
  if (langs.includes(tag) || langs.length >= MAX_LANGUAGES) return undefined
  return { ...draft, [list]: [...langs, tag] }
}

/** Add language's choices: the common languages by name, without those in the list. */
export function addable(langs: readonly string[]): { tag: string; name: string }[] {
  return commonLanguages.filter((t) => !langs.includes(t)).map((tag) => ({ tag, name: languageName(tag) }))
}
