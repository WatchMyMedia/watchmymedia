import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { preferencesQuery, useUpdatePreferences } from '../../api/queries'
import { Button, Choice, HubPage, LoadingRows, Message, Pill, SaveBar, Section } from '../../components/settings/Frame'
import { LeaveCheck } from '../../components/settings/Dialogs'
import { errorText, useSettingsPage } from '../../components/settings/hooks'
import { draftOf, emptySubtitleText, foreignAudioTurnedOn, languagePatch, subtitleChoices, type LanguageDraft } from '../../lib/languagePreferences'
import { settingsCrumbs } from '../../lib/settingsRoutes'
import { suggestedSubtitleLanguage } from '../../lib/subtitleIntent'
import { automaticReason, languageLede, languageSaveError, type Notice } from '../../lib/youSettings'
import { LanguageList } from './you/LanguageList'
import { FallbackLanguagesFlow, OpenSubtitlesFlow, SubtitleService } from './you/SubtitleService'
import { useFlash } from './you/hooks'

/**
 * Settings › You › Audio & subtitles (PLAN-LANGUAGES.md §5.6,
 * PLAN-ADMIN-SETTINGS.md §5.10): the person's audio and subtitle languages,
 * how subtitles start and Automatic download, which depend on each other and
 * so are one draft with the save bar; then, for admins, the server subtitle
 * service, with its two one-step tasks at their own addresses. The route
 * stays /settings/subtitles, so links keep working.
 */
export function AudioSubtitlesSettings() {
  const page = useSettingsPage()
  if (page?.task === 'opensubtitles') return <OpenSubtitlesFlow />
  if (page?.task === 'fallback-languages') return <FallbackLanguagesFlow />
  return <AudioSubtitlesHub />
}

function AudioSubtitlesHub() {
  const query = useQuery(preferencesQuery)
  const update = useUpdatePreferences()
  const [draft, setDraft] = useState<LanguageDraft | null>(null)
  const [saved, setSaved] = useState<'first' | 'plain' | null>(null)
  const [error, setError] = useState<Notice | null>(null)
  const [flash, clearFlash] = useFlash('subtitles')
  const crumbs = settingsCrumbs('subtitles')
  const savedDraft = query.data ? draftOf(query.data) : null
  const patch = savedDraft && draft ? languagePatch(savedDraft, draft) : null
  const dirty = !!patch

  const save = () => {
    if (!savedDraft || !draft || !patch) return
    setError(null)
    update.mutate(patch, {
      onSuccess: (after) => {
        setDraft(null)
        setSaved(foreignAudioTurnedOn(savedDraft, patch, after) ? 'first' : 'plain')
        clearFlash()
      },
      onError: (e) => setError(languageSaveError(e, errorText(e))),
    })
  }

  const banner = (
    <>
      {error && <Message tone="err" title={error.title} focus>{error.body}</Message>}
      {saved === 'first' && (
        <Message tone="ok" title="Saved for your account">
          Subtitles now start when the audio isn’t in your languages, because you hadn’t chosen when they start. You can change this under Start subtitles.
        </Message>
      )}
      {saved === 'plain' && <Message tone="ok" title="Saved for your account">Your choices follow your account on every device.</Message>}
      {flash && <Message tone={flash.tone ?? 'ok'} title={flash.title}>{flash.body}</Message>}
    </>
  )

  return (
    <>
      <SaveBar dirty={dirty} saving={update.isPending} onDiscard={() => { setDraft(null); setError(null) }} onSave={save} />
      <LeaveCheck when={dirty} what="Your audio and subtitle choices aren’t saved." />
      <HubPage
        crumbs={crumbs}
        title="Audio & subtitles"
        lede={savedDraft ? `Yours, on every device. ${languageLede(savedDraft)}` : 'Yours, on every device.'}
        banner={banner}
        help={
          <>
            <h4>Which audio plays</h4>
            <p>The first track in your languages, in your order. On a device that can’t switch tracks, the server makes a copy with that track.</p>
            <h4>Which subtitles start</h4>
            <p>Your subtitle languages, in order. With no subtitle languages, your audio languages are used.</p>
          </>
        }
      >
        {savedDraft ? (
          <YourLanguages
            saved={savedDraft}
            draft={draft ?? savedDraft}
            disabled={update.isPending}
            onEdit={(next) => { setDraft(next); setSaved(null); setError(null) }}
          />
        ) : query.isError ? (
          <Message tone="err" title="Couldn’t load your choices" actions={<Button small onClick={() => void query.refetch()}>Try again</Button>}>
            Check your connection and try again. Your players keep using what you saved.
          </Message>
        ) : (
          <LoadingRows rows={4} />
        )}
      </HubPage>
      <SubtitleService />
    </>
  )
}

function YourLanguages({ saved, draft: d, disabled, onEdit }: { saved: LanguageDraft; draft: LanguageDraft; disabled: boolean; onEdit: (d: LanguageDraft) => void }) {
  const edit = (patch: Partial<LanguageDraft>) => onEdit({ ...d, ...patch })
  const reason = automaticReason(d)
  const suggestion = suggestedSubtitleLanguage(navigator.languages)
  const changed = <Pill tone="now">Changed</Pill>
  return (
    <>
      <Section title="Audio languages">
        <LanguageList kind="audio" addLabel="Add an audio language" languages={d.audio} saved={saved.audio} disabled={disabled} onChange={(audio) => edit({ audio })}
          empty="No audio language yet. Videos play the file’s default audio."
          suggestion={suggestion}
          hint="Videos start in the first of these they have, otherwise the file’s default. Commentary and audio description are never chosen for you." />
      </Section>
      <Section title="Subtitle languages">
        <LanguageList kind="subtitle" addLabel="Add a subtitle language" languages={d.subtitles} saved={saved.subtitles} disabled={disabled} onChange={(subtitles) => edit({ subtitles })}
          empty={d.audio.length ? emptySubtitleText(d) : 'None yet. When subtitles start, your audio languages are used.'}
          hint="If your language isn’t listed, enter its code, such as pt-BR." />
      </Section>
      <Section title="Start subtitles">
        <fieldset className="border-0 p-0">
          <legend className="sr-only">Start subtitles</legend>
          {subtitleChoices.map((c) => (
            <Choice key={c.id} name="subtitle-start" value={c.id} checked={d.choice === c.id} disabled={disabled} onChange={() => edit({ choice: c.id })}
              title={<span className="inline-flex flex-wrap items-center gap-2">{c.label}{d.choice === c.id && saved.choice !== c.id && changed}</span>}>
              {c.line}
            </Choice>
          ))}
        </fieldset>
        <div className="mt-2.5">
          <Choice type="checkbox" name="automatic-download" checked={d.automaticDownload} disabled={disabled || !!reason} reason={reason}
            onChange={(automaticDownload) => edit({ automaticDownload })}
            title={<span className="inline-flex flex-wrap items-center gap-2">Automatic download{d.automaticDownload !== saved.automaticDownload && changed}</span>}>
            When subtitles would start and no subtitle in the file matches, look online for one made for this exact video in your first subtitle language. Uses the server’s daily allowance.
          </Choice>
        </div>
        <p className="mt-2.5 text-sm text-neutral-400">A track you choose in the player always wins. If online subtitles are unavailable, the video keeps playing.</p>
      </Section>
    </>
  )
}
