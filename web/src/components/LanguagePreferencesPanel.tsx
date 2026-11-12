import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { preferencesQuery, useUpdatePreferences } from '../api/queries'
import { languageName } from '../lib/format'
import {
  automaticAvailable, draftOf, emptySubtitleText, foreignAudioTurnedOn, languageList, languagePatch, moveLanguage, subtitleChoices,
  type LanguageDraft,
} from '../lib/languagePreferences'
import { suggestedSubtitleLanguage } from '../lib/subtitleIntent'
import { CheckRow, Panel, primaryButton, secondaryButton, textInput } from './SettingsPanel'

const commonLanguages = ['en', 'es', 'fr', 'de', 'it', 'pt', 'pt-BR', 'nl', 'ar', 'ur', 'hi', 'ja', 'ko', 'zh', 'zh-Hant', 'pl', 'ru', 'tr', 'sv', 'da', 'no', 'fi', 'el', 'he', 'id', 'th', 'vi']

/** Settings › Audio & subtitles (PLAN-LANGUAGES.md §5.6): audio languages, subtitle
 * languages and how subtitles start, saved together. A save sends only what
 * changed, so the server can still give someone saving their first audio
 * language subtitles for foreign audio. */
export function LanguagePreferencesPanel() {
  const query = useQuery(preferencesQuery)
  const update = useUpdatePreferences()
  const [draft, setDraft] = useState<LanguageDraft | null>(null)
  const [notice, setNotice] = useState(false)
  if (query.isPending) return <p role="status" className="text-neutral-400">Loading your choices…</p>
  if (query.isError) return <div role="alert" className="space-y-3 text-warn"><p>Couldn’t load your choices. Check your connection and try again.</p><button type="button" className={secondaryButton} onClick={() => void query.refetch()}>Try again</button></div>
  const saved = draftOf(query.data)
  const d = draft ?? saved
  const edit = (patch: Partial<LanguageDraft>) => {
    setDraft({ ...d, ...patch })
    setNotice(false)
    update.reset()
  }
  const suggestion = suggestedSubtitleLanguage(navigator.languages)
  const autoOK = automaticAvailable(d)
  return (
    <form className="space-y-6" onSubmit={(event) => {
      event.preventDefault()
      const patch = draft && languagePatch(saved, draft)
      if (!patch) { setDraft(null); return }
      update.mutate(patch, { onSuccess: (after) => { setDraft(null); setNotice(foreignAudioTurnedOn(saved, patch, after)) } })
    }}>
      {notice && <p role="status" className="flex max-w-2xl gap-2.5 rounded-lg border border-emerald-900 bg-emerald-950/60 px-4 py-3 text-sm text-emerald-50">Subtitles now start when the audio isn’t in your languages. You can change this under Start subtitles.</p>}

      <Panel title="Audio languages" description="Videos start in the first of these languages they have. If they have none, they play the file’s default.">
        <LanguageList id="audio" kind="audio" languages={d.audio} disabled={update.isPending} onChange={(audio) => edit({ audio })}
          empty="No audio language yet. Videos play the file’s default audio."
          suggestion={!d.audio.length ? suggestion : undefined} />
        <p className="px-5 pb-4 text-sm text-neutral-400">Commentary and audio description are never chosen for you. You can pick them in the player.</p>
      </Panel>

      <Panel title="Subtitle languages" description="Use the first of these languages that has a subtitle.">
        <LanguageList id="subtitle" kind="subtitle" languages={d.subtitles} disabled={update.isPending} onChange={(subtitles) => edit({ subtitles })}
          empty={emptySubtitleText(d)} hint="If your language isn’t listed, enter its language code, such as pt-BR." />
      </Panel>

      <Panel title="Start subtitles" description="When a video starts">
        <fieldset className="border-0 p-0">
          <legend className="sr-only">When a video starts</legend>
          {subtitleChoices.map((c) => (
            <label key={c.id} className="flex cursor-pointer items-start gap-3 border-t border-line px-5 py-3">
              <input type="radio" name="subtitle-start" value={c.id} checked={d.choice === c.id} disabled={update.isPending} onChange={() => edit({ choice: c.id })} className="mt-0.5 h-[18px] w-[18px] flex-none accent-accent" />
              <span className="min-w-0"><span className="block font-medium">{c.label}</span><span className="mt-0.5 block text-sm text-neutral-400">{c.line}</span></span>
            </label>
          ))}
        </fieldset>
        <p className="border-t border-line px-5 py-3 text-sm text-neutral-400">A track you choose in the player always wins.</p>
        <CheckRow label="Automatic download" hint="When subtitles would start and no local subtitle matches your languages, look for one made for this exact video in your first subtitle language. This uses the server’s daily subtitle allowance after playback starts." checked={d.automaticDownload} disabled={update.isPending || !autoOK} onChange={(automaticDownload) => edit({ automaticDownload })} />
        {!autoOK && <p className="px-5 pb-4 text-sm text-neutral-400">Choose On or When the audio isn’t in my languages, and add a subtitle language.</p>}
        <p className="px-5 pb-4 text-sm text-neutral-400">If online subtitles are unavailable or the daily allowance is used up, video keeps playing. Choose a subtitle already in the file, or ask the person who runs the server for help.</p>
      </Panel>

      {update.isError && <p role="alert" className="text-sm text-warn">{update.error?.message} Your choices stay here. Check your connection and choose Save preferences again.</p>}
      <div className="flex flex-wrap items-center gap-3">
        <button type="submit" disabled={!draft || update.isPending} className={primaryButton}>{update.isPending ? 'Saving…' : 'Save preferences'}</button>
        <span role="status" className="text-sm text-neutral-300">{update.isSuccess && !draft ? 'Saved for your account.' : draft ? 'Changes not saved yet.' : ''}</span>
      </div>
    </form>
  )
}

/** An ordered language list with move, remove and add, as both lists use it. */
function LanguageList({ id, kind, languages, disabled, onChange, empty, suggestion, hint }: {
  id: string; kind: 'audio' | 'subtitle'; languages: string[]; disabled: boolean; onChange: (languages: string[]) => void
  empty: string; suggestion?: string; hint?: string
}) {
  const [value, setValue] = useState('')
  const [error, setError] = useState<string | null>(null)
  const add = (typed: string) => {
    try {
      const tag = commonLanguages.find((t) => languageName(t).toLocaleLowerCase() === typed.trim().toLocaleLowerCase()) ?? typed
      const next = languageList([...languages, tag], kind)
      if (next.length === languages.length) return setValue('')
      onChange(next)
      setValue('')
      setError(null)
    } catch (e) { setError((e as Error).message) }
  }
  const input = `add-${id}-language`
  return (
    <>
      {languages.length > 0 ? <ol className="divide-y divide-line border-t border-line">
        {languages.map((tag, index) => <li key={tag} className="flex flex-wrap items-center gap-2 px-5 py-2.5">
          <span className="min-w-0 flex-1 basis-24 break-words text-[15px]"><span className="mr-2 inline-block w-5 text-neutral-400">{index + 1}.</span>{languageName(tag)}</span>
          <button type="button" disabled={disabled || index === 0} aria-label={`Move ${languageName(tag)} up`} onClick={() => onChange(moveLanguage(languages, index, -1))} className={secondaryButton}>↑</button>
          <button type="button" disabled={disabled || index === languages.length - 1} aria-label={`Move ${languageName(tag)} down`} onClick={() => onChange(moveLanguage(languages, index, 1))} className={secondaryButton}>↓</button>
          <button type="button" disabled={disabled} aria-label={`Remove ${languageName(tag)}`} onClick={() => onChange(languages.filter((t) => t !== tag))} className={secondaryButton}>Remove</button>
        </li>)}
      </ol> : <p className="px-5 pb-4 text-sm text-neutral-400">{empty}</p>}
      <div className="flex flex-wrap items-center gap-2 border-t border-line p-5">
        <label htmlFor={input} className="sr-only">{kind === 'audio' ? 'Add an audio language' : 'Add a subtitle language'}</label>
        <input id={input} list={`${input}-options`} value={value} onChange={(e) => { setValue(e.target.value); setError(null) }} onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); add(value) } }} placeholder="Choose or type a language" disabled={disabled} aria-invalid={!!error} aria-describedby={error ? `${input}-error` : undefined} className={`${textInput} min-w-0 flex-1 basis-52`} />
        <datalist id={`${input}-options`}>{commonLanguages.map((tag) => <option key={tag} value={languageName(tag)} />)}</datalist>
        <button type="button" disabled={!value.trim() || disabled || languages.length >= 16} onClick={() => add(value)} className={secondaryButton}>Add language</button>
        {suggestion && <p className="w-full text-sm text-neutral-400">Your browser uses {languageName(suggestion)}. <button type="button" disabled={disabled} onClick={() => add(suggestion)} className="min-h-10 text-neutral-100 underline">Use {languageName(suggestion)}</button></p>}
        {hint && <p className="w-full text-sm text-neutral-400">{hint}</p>}
        {languages.length >= 16 && <p className="w-full text-sm text-neutral-400">You can save up to 16 languages. Remove one before adding another.</p>}
        {error && <p id={`${input}-error`} role="alert" className="w-full text-sm text-warn">{error}</p>}
      </div>
    </>
  )
}
