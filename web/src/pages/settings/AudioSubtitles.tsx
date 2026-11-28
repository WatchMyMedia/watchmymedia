import { useState, type KeyboardEvent, type ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { preferencesQuery, serverQuery, useUpdatePreferences } from '../../api/queries'
import { Button, Card, Choice, HubPage, LoadingRows, Message, Pill, SaveBar, Section } from '../../components/settings/Frame'
import { LeaveCheck } from '../../components/settings/Dialogs'
import { errorText, useSettingsPage } from '../../components/settings/hooks'
import { draftOf, emptySubtitleText, foreignAudioTurnedOn, joinPatches, languagePatch, soundSizeOf, soundSizePatch, subtitleChoices, type LanguageDraft, type SoundSizeDraft } from '../../lib/languagePreferences'
import { SOUND_LEVELS, SOUND_NOTE, SOUND_ROWS, soundAndSubtitlesOn, type SoundLevel, type SoundSetting } from '../../lib/sound'
import { PREVIEW_LINE, SIZE_KEEPS, SUBTITLE_SCALE, SUBTITLE_SIZES, subtitleSizeLabel, type SubtitleSize } from '../../lib/subtitleSize'
import { settingsCrumbs } from '../../lib/settingsRoutes'
import { suggestedSubtitleLanguage } from '../../lib/subtitleIntent'
import { automaticReason, languageLede, languageSaveError, type Notice } from '../../lib/youSettings'
import { LanguageList } from './you/LanguageList'
import { FallbackLanguagesFlow, OpenSubtitlesFlow, SubtitleService } from './you/SubtitleService'
import { useFlash } from './you/hooks'

/**
 * Settings › You › Audio & subtitles:
 * the person's audio and subtitle languages,
 * how subtitles start and Automatic download, which depend on each other and
 * so are one draft with the save bar; on a server at API level 35 the sound
 * (Boost dialogue, Reduce loud sounds) and the subtitle size join the same
 * draft, so the page keeps one way of saving. Then, for admins, the server
 * subtitle service, with its two one-step tasks at their own addresses. The
 * route stays /settings/subtitles, so links keep working.
 */
export function AudioSubtitlesSettings() {
  const page = useSettingsPage()
  if (page?.task === 'opensubtitles') return <OpenSubtitlesFlow />
  if (page?.task === 'fallback-languages') return <FallbackLanguagesFlow />
  return <AudioSubtitlesHub />
}

function AudioSubtitlesHub() {
  const query = useQuery(preferencesQuery)
  const server = useQuery(serverQuery)
  // Older servers refuse the sound and size fields: the page doesn't offer them.
  const newer = soundAndSubtitlesOn(server.data?.apiLevel)
  const update = useUpdatePreferences()
  const [draft, setDraft] = useState<LanguageDraft | null>(null)
  const [extra, setExtra] = useState<SoundSizeDraft | null>(null)
  const [saved, setSaved] = useState<'first' | 'plain' | null>(null)
  const [error, setError] = useState<Notice | null>(null)
  const [flash, clearFlash] = useFlash('subtitles')
  const crumbs = settingsCrumbs('subtitles')
  const savedDraft = query.data ? draftOf(query.data) : null
  const savedExtra = query.data ? soundSizeOf(query.data) : null
  const languages = savedDraft && draft ? languagePatch(savedDraft, draft) : null
  const patch = joinPatches(languages, newer && savedExtra && extra ? soundSizePatch(savedExtra, extra) : null)
  const dirty = !!patch

  const save = () => {
    if (!savedDraft || !patch) return
    setError(null)
    update.mutate(patch, {
      onSuccess: (after) => {
        setDraft(null)
        setExtra(null)
        setSaved(foreignAudioTurnedOn(savedDraft, patch, after) ? 'first' : 'plain')
        clearFlash()
      },
      onError: (e) => setError(languageSaveError(e, errorText(e))),
    })
  }
  const edited = () => { setSaved(null); setError(null) }

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
      <SaveBar dirty={dirty} saving={update.isPending} onDiscard={() => { setDraft(null); setExtra(null); setError(null) }} onSave={save} />
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
            {newer && (
              <>
                <h4>Sound</h4>
                <p>Boost dialogue and Reduce loud sounds apply on all your devices, to Live TV and recordings too, and you can change them in the player. Downloads keep the film’s own sound.</p>
              </>
            )}
            <h4>Which subtitles start</h4>
            <p>Your subtitle languages, in order. With no subtitle languages, your audio languages are used.</p>
            {newer && (
              <>
                <h4>Subtitle size</h4>
                <p>For subtitles the app draws, on all your devices. Subtitles that are part of the picture, and styled ones, keep their own size.</p>
              </>
            )}
          </>
        }
      >
        {savedDraft && savedExtra ? (
          <YourLanguages
            saved={savedDraft}
            draft={draft ?? savedDraft}
            disabled={update.isPending}
            onEdit={(next) => { setDraft(next); edited() }}
            extra={newer ? { saved: savedExtra, draft: extra ?? savedExtra, onEdit: (next) => { setExtra(next); edited() } } : undefined}
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

type Extra = { saved: SoundSizeDraft; draft: SoundSizeDraft; onEdit: (d: SoundSizeDraft) => void }

function YourLanguages({ saved, draft: d, disabled, onEdit, extra }: { saved: LanguageDraft; draft: LanguageDraft; disabled: boolean; onEdit: (d: LanguageDraft) => void; extra?: Extra }) {
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
      {extra && <SoundSection extra={extra} disabled={disabled} changed={changed} />}
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
      {extra && <SizeSection extra={extra} disabled={disabled} changed={changed} />}
    </>
  )
}

/** Off, Medium or High for one sound setting, under its name on a phone and beside it from sm up. */
function SoundSection({ extra, disabled, changed }: { extra: Extra; disabled: boolean; changed: ReactNode }) {
  const set = (id: SoundSetting, level: SoundLevel) => extra.onEdit({ ...extra.draft, sound: { ...extra.draft.sound, [id]: level } })
  return (
    <Section title="Sound">
      <Card>
        {SOUND_ROWS.map((row) => (
          <div key={row.id} className="flex flex-wrap items-center gap-x-4 gap-y-2 px-5 py-3.5">
            <div className="min-w-0 flex-[1_1_15rem]">
              <h4 id={`sound-${row.id}`} className="flex flex-wrap items-center gap-2 font-medium">
                {row.label}
                {extra.draft.sound[row.id] !== extra.saved.sound[row.id] && changed}
              </h4>
              <p id={`sound-${row.id}-help`} className="mt-0.5 text-sm text-neutral-400">{row.help}</p>
            </div>
            <Segmented
              label={`sound-${row.id}`}
              options={SOUND_LEVELS}
              value={extra.draft.sound[row.id]}
              disabled={disabled}
              onPick={(level) => set(row.id, level)}
              className="max-sm:w-full"
            />
          </div>
        ))}
      </Card>
      <p className="mt-2.5 text-sm text-neutral-400">{SOUND_NOTE}</p>
    </Section>
  )
}

/** The four sizes with a preview of a cue at the one chosen, drawn as the player draws it. */
function SizeSection({ extra, disabled, changed }: { extra: Extra; disabled: boolean; changed: ReactNode }) {
  const size = extra.draft.size
  return (
    <Section title={<span id="subtitle-size">Subtitle size</span>}>
      <Card padded>
        <div className="flex flex-wrap items-center gap-x-3 gap-y-2.5">
          <Segmented label="subtitle-size" describedBy="subtitle-size-help" options={SUBTITLE_SIZES} value={size} disabled={disabled}
            onPick={(next: SubtitleSize) => extra.onEdit({ ...extra.draft, size: next })} className="max-sm:w-full" />
          {size !== extra.saved.size && changed}
        </div>
        <SizePreview size={size} />
        <p id="subtitle-size-help" className="mt-2.5 text-sm text-neutral-400">
          Subtitles grow with the picture, so they look like this in any window and on full screen. {SIZE_KEEPS}
        </p>
      </Card>
    </Section>
  )
}

/** A still from a film with one cue at the chosen size: 4.2% of the picture's height at Normal, never under 14 px, as the player draws it. */
function SizePreview({ size }: { size: SubtitleSize }) {
  return (
    <div
      role="img"
      aria-label={`Preview: a subtitle at ${subtitleSizeLabel(size)} size on a still from a film`}
      className="still-night relative mt-4 aspect-video w-full overflow-hidden rounded-[10px] ring-1 ring-inset ring-white/7 [container-type:inline-size]"
    >
      <div aria-hidden="true" className="absolute left-[58%] top-[22%] aspect-square w-[22%] rounded-full bg-radial from-amber-200/45 to-transparent to-68%" />
      <p aria-hidden="true" className="player-cue absolute inset-x-[8%] bottom-[6%]" style={{ fontSize: `calc(max(14px, 2.3625cqw) * ${SUBTITLE_SCALE[size]})` }}>
        <span>{PREVIEW_LINE}</span>
      </p>
    </div>
  )
}

/** A choice of a few, as one control (the seg look): each a radio. */
function Segmented<T extends string>({ label, describedBy, options, value, disabled, onPick, className = '' }: {
  label: string; describedBy?: string; options: readonly { id: T; label: string }[]; value: T; disabled?: boolean; onPick: (v: T) => void; className?: string
}) {
  const move = (e: KeyboardEvent<HTMLDivElement>) => {
    const by = e.key === 'ArrowRight' || e.key === 'ArrowDown' ? 1 : e.key === 'ArrowLeft' || e.key === 'ArrowUp' ? -1 : 0
    if (!by) return
    e.preventDefault()
    const at = options.findIndex((o) => o.id === value)
    const next = options[(at + by + options.length) % options.length]
    onPick(next.id)
    e.currentTarget.querySelector<HTMLElement>(`[data-value="${next.id}"]`)?.focus()
  }
  return (
    <div role="radiogroup" aria-labelledby={label} aria-describedby={describedBy ?? `${label}-help`} onKeyDown={move} className={`seg ${className}`}>
      {options.map((o) => (
        <button key={o.id} type="button" role="radio" data-value={o.id} aria-checked={o.id === value} tabIndex={o.id === value ? 0 : -1} disabled={disabled}
          onClick={() => onPick(o.id)} className="min-h-[34px] justify-center px-[13px] text-[13.5px] hover:text-neutral-100 disabled:cursor-not-allowed max-sm:flex-1">
          {o.label}
        </button>
      ))}
    </div>
  )
}
