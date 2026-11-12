export type SubtitleMode = 'auto' | 'off'
export type SubtitleSelection = { subtitleId?: string; subtitleMode: SubtitleMode }

/** A choice belongs to one mounted play. A manual pick invalidates every earlier
 * download result, including one already on its way back from the provider. */
export class SubtitleIntent {
  private revision = 0
  private manual: string | null = null

  choose(id: string) {
    this.manual = id
    this.revision++
  }

  invalidate() { this.revision++ }

  capture() { return this.revision }

  accepts(revision: number) { return revision === this.revision }

  get automatic() { return this.manual === null }

  request(): SubtitleSelection {
    return this.manual === null
      ? { subtitleMode: 'auto' }
      : { subtitleMode: 'off', ...(this.manual ? { subtitleId: this.manual } : {}) }
  }
}

/** Validate locally for useful feedback; the server validates again atomically. */
export function subtitleLanguages(value: string): string[] {
  const languages = value.split(',').map((s) => s.trim()).filter(Boolean)
  if (languages.length > 16) throw new Error('Choose at most 16 subtitle languages.')
  try {
    return Intl.getCanonicalLocales(languages)
  } catch {
    throw new Error('Use language codes such as en, es or pt-BR, separated by commas.')
  }
}

/** A suggestion is shown, never saved until the person explicitly chooses it. */
export function suggestedSubtitleLanguage(languages: readonly string[]): string | undefined {
  for (const language of languages) {
    try {
      const [tag] = Intl.getCanonicalLocales(language)
      if (tag && tag !== 'und') return tag
    } catch { /* Ignore a malformed browser locale. */ }
  }
}
