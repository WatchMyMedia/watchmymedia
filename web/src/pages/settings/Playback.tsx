import { useId } from 'react'
import { useQuery } from '@tanstack/react-query'
import { preferencesQuery, useUpdatePreferences } from '../../api/queries'
import { Button, Card, HubPage, LoadingRows, Message, Section, Switch } from '../../components/settings/Frame'
import { useGuideExtras } from '../../components/liveTV/useGuideExtras'
import { LEAD_OPTIONS, leadMinutes } from '../../lib/liveReminders'
import { settingsCrumbs } from '../../lib/settingsRoutes'
import { SKIP_KINDS, SKIP_MODES, choiceSaveError, playbackLede, skipModeLabel, skipModeOf, skipSavedLine } from '../../lib/youSettings'
import { selectInput, useRowSave, type RowSave } from './you/hooks'
import { RowNote } from './you/RowNote'

/**
 * Settings › You › Playback (PLAN-ADMIN-SETTINGS.md §5.10): how this
 * person's episodes skip and carry on, on every device. Every control is a
 * choice, so each saves at once and confirms in its own row; only that row
 * waits while it saves.
 */
export function PlaybackSettings() {
  const prefs = useQuery(preferencesQuery)
  // Reminders come with listings at API level 15, for someone who may watch Live TV.
  const reminders = useGuideExtras()
  const update = useUpdatePreferences()
  const { rows, run } = useRowSave<string | boolean | number>()
  const p = prefs.data
  const crumbs = settingsCrumbs('playback')

  if (!p) {
    return (
      <HubPage crumbs={crumbs} title="Playback" lede="Yours, on every device.">
        {prefs.isError ? (
          <Message tone="err" title="Couldn’t load your playback choices" actions={<Button small onClick={() => void prefs.refetch()}>Try again</Button>}>
            The server didn’t answer. Nothing has changed. Your players keep using the choices you saved.
          </Message>
        ) : (
          <LoadingRows rows={5} />
        )}
      </HubPage>
    )
  }

  const skip = (kind: string, mode: string) => {
    const before = skipModeLabel(skipModeOf(p.skip, kind))
    void run(`skip-${kind}`, mode, () => update.mutateAsync({ skip: { [kind]: mode } }), skipSavedLine(kind, mode), (e) => choiceSaveError(e, before))
  }
  const autoplay = (on: boolean) => {
    const before = p.autoplayNext ? 'on' : 'off'
    void run('autoplay', on, () => update.mutateAsync({ autoplayNext: on }),
      on ? 'The next episode now plays by itself, on every device.' : 'The Next Episode card now waits for you, on every device.',
      (e) => choiceSaveError(e, before))
  }
  const lead = leadMinutes(p.liveTVReminderMinutes)
  const leadLabel = (m: number) => LEAD_OPTIONS.find((o) => o.minutes === m)?.label ?? `${m} minutes before`
  const remind = (minutes: number) => {
    void run('reminder', minutes, () => update.mutateAsync({ liveTVReminderMinutes: minutes }), `Reminders now come ${leadLabel(minutes).toLowerCase()}, on every device.`, (e) => choiceSaveError(e, leadLabel(lead)))
  }
  const shown = <T,>(key: string, saved: T): T => (rows[key]?.status === 'saving' ? (rows[key].value as T) : saved)

  return (
    <HubPage
      crumbs={crumbs}
      title="Playback"
      lede={`Yours, on every device. ${playbackLede(p)}`}
      help={
        <>
          <h4>What the choices do</h4>
          <p><b className="text-neutral-200">Show a Skip button</b> appears for parts 3 seconds or longer.</p>
          <p><b className="text-neutral-200">Skip after 3 seconds</b> shows the button, then skips by itself.</p>
          <p><b className="text-neutral-200">Skip right away</b> doesn’t skip a part you seeked into, or while the Next Episode card is up.</p>
          <p><b className="text-neutral-200">Do nothing</b> shows no button; the part plays through.</p>
          <h4>Where parts come from</h4>
          <p>Chapter marks in the file, and comparing the audio across a season.</p>
        </>
      }
    >
      <Section title="Skipping">
        <Card>
          {SKIP_KINDS.map((k) => (
            <ChoiceRow key={k.kind} label={k.label} hint={k.hint} state={rows[`skip-${k.kind}`]}
              value={shown(`skip-${k.kind}`, skipModeOf(p.skip, k.kind))}
              options={SKIP_MODES.map((m) => ({ value: m.value, label: m.label }))}
              onChange={(v) => skip(k.kind, v)} />
          ))}
        </Card>
      </Section>
      <Section title="Next episode">
        <Card>
          <div className="flex flex-wrap items-center gap-x-4 gap-y-2 px-5 py-3.5">
            <div className="min-w-0 flex-1 basis-60">
              <h4 className="font-medium">Play the next episode by itself</h4>
              <div className="mt-0.5 text-sm text-neutral-400">
                <RowNote state={rows.autoplay} idle="The Next Episode card counts down 10 seconds from the credits. Off, it waits for you." />
              </div>
            </div>
            <Switch label="Play the next episode by itself" checked={shown('autoplay', p.autoplayNext)} disabled={rows.autoplay?.status === 'saving'} onChange={autoplay} />
          </div>
        </Card>
      </Section>
      {reminders && (
        <Section title="Live TV reminders">
          <Card>
            <ChoiceRow label="Remind me" hint="Before a programme you asked to be reminded of. Shows while WatchMyMedia is open in a tab." state={rows.reminder}
              value={String(shown('reminder', lead))}
              options={LEAD_OPTIONS.map((o) => ({ value: String(o.minutes), label: o.label }))}
              onChange={(v) => remind(Number(v))} />
          </Card>
        </Section>
      )}
    </HubPage>
  )
}

/** A choice from a list that saves at once, with its result in the row. */
function ChoiceRow({ label, hint, value, options, onChange, state }: {
  label: string
  hint: string
  value: string
  options: { value: string; label: string }[]
  onChange: (value: string) => void
  state?: RowSave<unknown>
}) {
  const id = useId()
  return (
    <div className="flex flex-wrap items-center gap-x-4 gap-y-2 px-5 py-3.5">
      <div className="min-w-0 flex-1 basis-56">
        <label htmlFor={id} className="font-medium">{label}</label>
        <div id={`${id}-note`} className="mt-0.5 text-sm text-neutral-400"><RowNote state={state} idle={hint} /></div>
      </div>
      <select id={id} aria-describedby={`${id}-note`} value={value} disabled={state?.status === 'saving'} onChange={(e) => onChange(e.target.value)} className={selectInput}>
        {options.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
      </select>
    </div>
  )
}
