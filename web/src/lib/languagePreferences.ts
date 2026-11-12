import type { components } from '../api/schema'

type Saved = components['schemas']['Preferences']
type Patch = components['schemas']['UpdatePreferencesInputBody']

/** How subtitles start (PLAN-LANGUAGES.md §5.6). foreign is saved as mode
 * forced with foreignAudio, so older apps, which don't know the flag, read it
 * as Forced only and leave the flag alone when they save. */
export type SubtitleChoice = 'off' | 'forced' | 'foreign' | 'on'

export const subtitleChoices: readonly { id: SubtitleChoice; label: string; short: string; line: string }[] = [
  { id: 'off', label: 'Off', short: 'Off', line: 'Start without subtitles.' },
  { id: 'forced', label: 'Forced only', short: 'Forced only', line: 'Only parts the file marks as forced, such as a line in another language.' },
  { id: 'foreign', label: 'When the audio isn’t in my languages', short: 'For foreign audio', line: 'Forced only when you follow the audio. Full subtitles when it isn’t in your audio languages.' },
  { id: 'on', label: 'On', short: 'On', line: 'Always start with a subtitle in your first language that has one.' },
]

/** What the Audio & subtitles page edits, in one draft saved together. */
export type LanguageDraft = { audio: string[]; subtitles: string[]; choice: SubtitleChoice; automaticDownload: boolean }

export function choiceOf(s: { mode: string; foreignAudio?: boolean }): SubtitleChoice {
  if (s.mode === 'off' || s.mode === 'on') return s.mode
  return s.foreignAudio ? 'foreign' : 'forced'
}

export function subtitleFields(choice: SubtitleChoice): { mode: 'off' | 'forced' | 'on'; foreignAudio: boolean } {
  return choice === 'foreign' ? { mode: 'forced', foreignAudio: true } : { mode: choice, foreignAudio: false }
}

export function draftOf(p: Saved): LanguageDraft {
  return { audio: p.audio?.languages ?? [], subtitles: p.subtitles?.languages ?? [], choice: choiceOf(p.subtitles ?? { mode: 'forced' }), automaticDownload: !!p.subtitles?.automaticDownload }
}

/** Automatic download needs a choice that can turn subtitles on, and a language to look for. */
export function automaticAvailable(d: LanguageDraft): boolean {
  return (d.choice === 'on' || d.choice === 'foreign') && d.subtitles.length > 0
}

const same = (a: readonly string[], b: readonly string[]) => a.length === b.length && a.every((v, i) => v === b[i])

/** Only what changed. Sending the subtitle mode when the person didn't touch
 * it would count as choosing it, and the server would then never give
 * someone saving their first audio language subtitles for foreign audio. */
export function languagePatch(saved: LanguageDraft, draft: LanguageDraft): Patch | null {
  const patch: Patch = {}
  if (!same(saved.audio, draft.audio)) patch.audio = { languages: draft.audio }
  const subtitles: NonNullable<Patch['subtitles']> = {}
  if (!same(saved.subtitles, draft.subtitles)) subtitles.languages = draft.subtitles
  if (saved.choice !== draft.choice) Object.assign(subtitles, subtitleFields(draft.choice))
  if (saved.automaticDownload !== draft.automaticDownload) subtitles.automaticDownload = draft.automaticDownload
  if (Object.keys(subtitles).length) patch.subtitles = subtitles
  return Object.keys(patch).length ? patch : null
}

/** The server chose subtitles for foreign audio itself (the person's first
 * audio languages, with no subtitle mode ever saved): say so once. */
export function foreignAudioTurnedOn(before: LanguageDraft, patch: Patch, after: Saved): boolean {
  return before.choice !== 'foreign' && patch.subtitles?.mode === undefined && choiceOf(after.subtitles ?? { mode: 'forced' }) === 'foreign'
}

/** Canonical BCP 47 tags, without repeats; the server checks them again. */
export function languageList(values: readonly string[], kind: 'audio' | 'subtitle'): string[] {
  const languages = values.map((s) => s.trim()).filter(Boolean)
  let canonical: string[]
  try {
    canonical = Intl.getCanonicalLocales(languages)
  } catch {
    throw new Error('Use a language from the list, or a code such as en, es or pt-BR.')
  }
  if (canonical.length > 16) throw new Error(`Choose at most 16 ${kind} languages.`)
  return canonical
}

export function moveLanguage(list: readonly string[], index: number, by: -1 | 1): string[] {
  const other = index + by
  if (index < 0 || other < 0 || other >= list.length) return [...list]
  const next = [...list]
  ;[next[index], next[other]] = [next[other], next[index]]
  return next
}

/** The line in an empty Subtitle languages list. */
export function emptySubtitleText(d: LanguageDraft): string {
  return d.choice === 'foreign' && d.audio.length
    ? 'No subtitle language yet. Until you add one, subtitles use your audio languages.'
    : 'No subtitle language yet. Add one below.'
}
