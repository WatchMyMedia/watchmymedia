import { useId, useState, type ReactNode } from 'react'
import { languageName } from '../../../lib/format'
import { languageList, moveLanguage } from '../../../lib/languagePreferences'
import { Button, Card, Pill, fieldInput } from '../../../components/settings/Frame'

const commonLanguages = ['en', 'es', 'fr', 'de', 'it', 'pt', 'pt-BR', 'nl', 'ar', 'ur', 'hi', 'ja', 'ko', 'zh', 'zh-Hant', 'pl', 'ru', 'tr', 'sv', 'da', 'no', 'fi', 'el', 'he', 'id', 'th', 'vi']

const MAX_LANGUAGES = 16

/**
 * An ordered list of languages by name, with move, remove and add:
 * a person's audio and subtitle languages,
 * and the server's fallback languages. Languages not in `saved` are marked
 * Added until the draft is saved.
 */
export function LanguageList({ kind, languages, saved, onChange, disabled, empty, suggestion, hint, refuse, addLabel }: {
  kind: 'audio' | 'subtitle'
  languages: string[]
  saved?: string[]
  onChange: (languages: string[]) => void
  disabled?: boolean
  empty: ReactNode
  /** The browser's language, offered in one press while the list is empty. */
  suggestion?: string
  hint?: ReactNode
  /** Why a language can't go in this list, or null. */
  refuse?: (tag: string) => string | null
  addLabel: string
}) {
  const id = useId()
  const [value, setValue] = useState('')
  const [error, setError] = useState<string | null>(null)
  const full = languages.length >= MAX_LANGUAGES
  const add = (typed: string) => {
    try {
      const tag = commonLanguages.find((t) => languageName(t).toLocaleLowerCase() === typed.trim().toLocaleLowerCase()) ?? typed
      const next = languageList([...languages, tag], kind)
      const added = next[next.length - 1]
      const why = next.length > languages.length ? refuse?.(added) : null
      if (why) return setError(why)
      if (next.length > languages.length) onChange(next)
      setValue('')
      setError(null)
    } catch (e) {
      setError((e as Error).message)
    }
  }
  return (
    <Card>
      {languages.length > 0 ? (
        <ol className="divide-y divide-line">
          {languages.map((tag, index) => {
            const name = languageName(tag)
            return (
              <li key={tag} className="flex flex-wrap items-center gap-2 px-5 py-2.5">
                <span className="flex min-w-0 flex-1 basis-32 flex-wrap items-center gap-2 font-medium break-words">
                  {index + 1}. {name}
                  {saved && !saved.includes(tag) && <Pill tone="now">Added</Pill>}
                </span>
                <span className="flex gap-2">
                  <Button small disabled={disabled || index === 0} aria-label={`Move ${name} up`} onClick={() => onChange(moveLanguage(languages, index, -1))}>↑</Button>
                  <Button small disabled={disabled || index === languages.length - 1} aria-label={`Move ${name} down`} onClick={() => onChange(moveLanguage(languages, index, 1))}>↓</Button>
                  <Button small disabled={disabled} aria-label={`Remove ${name}`} onClick={() => onChange(languages.filter((t) => t !== tag))}>Remove</Button>
                </span>
              </li>
            )
          })}
        </ol>
      ) : (
        <p className="px-5 pt-4 text-sm text-neutral-400">{empty}</p>
      )}
      <div className={`px-5 py-4 ${languages.length ? 'border-t border-line' : ''}`}>
        <div className="flex flex-wrap items-center gap-2">
          <label htmlFor={id} className="sr-only">{addLabel}</label>
          <input
            id={id}
            list={`${id}-options`}
            value={value}
            onChange={(e) => { setValue(e.target.value); setError(null) }}
            onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); if (value.trim() && !full) add(value) } }}
            placeholder="Choose or type a language"
            disabled={disabled}
            aria-invalid={error ? true : undefined}
            aria-describedby={error ? `${id}-error` : undefined}
            className={`${fieldInput} min-w-0 flex-1 basis-52`}
          />
          <datalist id={`${id}-options`}>{commonLanguages.map((tag) => <option key={tag} value={languageName(tag)} />)}</datalist>
          <Button small disabled={!value.trim() || disabled || full} onClick={() => add(value)}>Add language</Button>
        </div>
        {error && <p id={`${id}-error`} role="alert" className="mt-1.5 text-sm text-red-300">{error}</p>}
        {full && <p className="mt-2 text-sm text-neutral-400">You can save up to {MAX_LANGUAGES} languages. Remove one before adding another.</p>}
        {suggestion && !languages.length && (
          <p className="mt-2 flex flex-wrap items-center gap-2 text-sm text-neutral-400">
            Your browser uses {languageName(suggestion)}.
            <Button small disabled={disabled} onClick={() => add(suggestion)}>Use {languageName(suggestion)}</Button>
          </p>
        )}
        {hint && <p className="mt-2 text-sm text-neutral-400">{hint}</p>}
      </div>
    </Card>
  )
}
