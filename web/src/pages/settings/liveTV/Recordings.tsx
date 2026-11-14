import { useEffect, useState, type ReactNode } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { saveRecordingSettings, type RecordingSettings } from '../../../api/recordings'
import { liveTVRecordingSettingsQuery } from '../../../api/queries'
import { Button, Card, Choice, Field, HrefLink, HubPage, ItemRow, Message, NextStep, Outcome, RemoveSection, fieldInput } from '../../../components/settings/Frame'
import { FlowFrame } from '../../../components/settings/Flow'
import { ConfirmDialog } from '../../../components/settings/Dialogs'
import { errorText, useGo } from '../../../components/settings/hooks'
import { useRecordingMutation } from '../../../components/liveTV/useRecordings'
import { sameValue } from '../../../lib/drafts'
import { PADDING_MINUTES } from '../../../lib/liveRecordings'
import { settingsCrumbs, settingsHref } from '../../../lib/settingsRoutes'
import {
  RECORDING_STEPS, WARNING_SECONDS, keepFreeError, minutesWords, recordAccessLabel, recordingNeedWarning, recordingNeeds, recordingSaveError, recordingSetupStep,
  secondsWords, timingSummary, type RecordingNeed,
} from '../../../lib/liveTVSettings'
import { tvHref, useLibraries, useLiveActivity, useLiveSettings, useRecordingSettings } from './data'
import { LoadFailed, Loading, StayCheck } from './Parts'

type Library = { id: number; name: string; path: string }

/** Everything recordings need, loaded together. */
function useRecordingContext() {
  const settings = useLiveSettings()
  const recording = useRecordingSettings()
  const libraries = useLibraries()
  const error = settings.error ?? recording.error ?? libraries.query.error
  const ready = settings.data && recording.data && libraries.query.data
  const needs = ready ? recordingNeeds({ sources: settings.data!.sources, access: settings.data!.access, tvLibraries: libraries.tv, movieLibraries: libraries.movies.length }) : []
  const retry = () => { void settings.refetch(); void recording.refetch(); void libraries.query.refetch() }
  return { settings: settings.data, recording: recording.data, tv: libraries.tv, movies: libraries.movies, needs, error, retry, ready: !!ready }
}

function needHref(need: RecordingNeed, firstTuner?: string) {
  if (need.key === 'tuner') return firstTuner ? tvHref('tuners', firstTuner) : tvHref('tuners', 'add')
  if (need.key === 'access') return tvHref('access')
  return settingsHref('libraries', 'add')
}

/**
 * Recordings (boards tv-rec-0 and on): what's needed first, each with the way
 * to get it; once on, what is set, each part with Change, and turning off
 * apart at the bottom.
 */
export function RecordingsPage() {
  const go = useGo()
  const ctx = useRecordingContext()
  const crumbs = settingsCrumbs('live-tv', { label: 'Recordings' })
  const [off, setOff] = useState<'idle' | 'asking' | 'done'>('idle')
  const qc = useQueryClient()
  const { recordings: running } = useLiveActivity()
  const save = useRecordingMutation(saveRecordingSettings)
  if (!ctx.ready) {
    return <HubPage crumbs={crumbs} title="Recordings">{ctx.error ? <LoadFailed error={ctx.error} retry={ctx.retry} what="recording settings" /> : <Loading />}</HubPage>
  }
  const r = ctx.recording!
  const warning = recordingNeedWarning(ctx.needs, ctx.movies.length)
  const firstTuner = ctx.settings!.sources[0]?.id
  const banner = off === 'done' ? <Message tone="ok" title="Recordings are off">Nothing records until you turn them on again. Finished recordings stay in your libraries.</Message> : undefined

  if (!r.enabled) {
    const firstUnmet = ctx.needs.find((n) => !n.met)
    return (
      <HubPage crumbs={crumbs} title="Recordings" lede="Record programmes into your libraries." pill={{ tone: 'off', label: 'Off' }} banner={banner}>
        {warning && firstUnmet && (
          <Message tone="warn" title={warning.title} actions={<Button small guarded={firstUnmet.key === 'tuner' && !firstTuner} onClick={() => go(needHref(firstUnmet, firstTuner))}>{firstUnmet.action}</Button>}>
            {warning.body}
          </Message>
        )}
        <Card>
          {ctx.needs.map((n) => (
            <ItemRow key={n.key} title={<span className={n.met ? '' : 'text-warn'}>{n.met ? '✓ ' : ''}{n.title}</span>} detail={n.detail}
              control={n.met ? undefined : <Button small onClick={() => go(needHref(n, firstTuner))}>{n.key === 'library' ? 'Add' : n.key === 'tuner' && !firstTuner ? 'Add' : 'Change'}</Button>} />
          ))}
        </Card>
        {!warning && (
          <NextStep title="Set up recordings" action={<Button variant="primary" onClick={() => go(tvHref('recordings', 'setup'))}>Set up recordings</Button>}>
            Who can record, where recordings go, and timing and space. Nothing is saved until the last step.
          </NextStep>
        )}
      </HubPage>
    )
  }

  const tvLib = ctx.tv.find((l) => l.id === r.tvLibraryId)
  const movieLib = ctx.movies.find((l) => l.id === r.movieLibraryId)
  const recordingNow = running.filter((x) => x.state === 'recording').length
  return (
    <HubPage crumbs={crumbs} title="Recordings" lede={`${recordAccessLabel(r.access)} can record.`} pill={{ tone: 'ok', label: 'On' }}
      banner={<>{banner}{warning && <Message tone="warn" title={warning.title}>{warning.body} Recordings can’t start until it’s fixed.</Message>}</>}>
      <Card>
        <ReviewRows r={r} tvLib={tvLib} movieLib={movieLib} href={(step) => tvHref('recordings', 'setup', step)} />
      </Card>
      <p className="text-sm text-neutral-400">Scheduled and finished recordings are listed on <HrefLink href="/live-tv/recordings" className="underline">Live TV › Recordings</HrefLink>.</p>
      <RemoveSection title="Turn off">
        <ItemRow title="Turn recordings off" detail="Any recording in progress is cancelled. Nothing records until they’re on again."
          control={<Button small variant="danger" onClick={() => { save.reset(); setOff('asking') }}>Turn off…</Button>} />
      </RemoveSection>
      {off === 'asking' && (
        <ConfirmDialog title="Turn recordings off?" confirmLabel="Turn off recordings" busy={save.isPending}
          stops={[...(recordingNow ? [`${recordingNow} ${recordingNow === 1 ? 'recording' : 'recordings'} in progress, cancelled now.`] : []), 'Scheduled recordings, until recordings are on again.']}
          stays={['Finished recordings in your libraries.', 'These settings, for when you turn recordings on again.']}
          error={save.isError ? `${errorText(save.error)} Recordings are still on.` : undefined}
          onClose={() => setOff('idle')}
          onConfirm={() => save.mutate({ ...r, enabled: false }, { onSuccess: (s) => { qc.setQueryData(liveTVRecordingSettingsQuery.queryKey, s); setOff('done') } })} />
      )}
    </HubPage>
  )
}

function ReviewRows({ r, tvLib, movieLib, href }: { r: RecordingSettings; tvLib?: Library; movieLib?: Library; href: (step: string) => string }) {
  const change = (step: string, label: string) => <HrefLink href={href(step)} aria-label={`Change ${label}`} className="text-sm text-neutral-200 underline">Change</HrefLink>
  return (
    <>
      <ItemRow title="Who can record" detail={recordAccessLabel(r.access)} control={change('who', 'who can record')} />
      <ItemRow title="Folders" detail={`TV: ${tvLib?.path ?? r.tvPath ?? 'not chosen'} · Movies: ${movieLib?.path ?? (r.moviePath || 'not recorded')}`} control={change('folders', 'folders')} />
      <ItemRow title="Timing and space" detail={timingSummary(r)} control={change('timing', 'timing and space')} />
    </>
  )
}

/**
 * Set up recordings (PLAN-ADMIN-SETTINGS.md §5.4): Who can record → Folders
 * → Timing and space → Review → On. Today's ten fields, a few at a time;
 * nothing saves until Review.
 */
export function RecordingsSetupFlow({ step }: { step?: string }) {
  const go = useGo()
  const ctx = useRecordingContext()
  const crumbs = settingsCrumbs('live-tv', { label: 'Set up recordings' })
  const blocked = ctx.ready && ctx.needs.some((n) => !n.met) && !ctx.recording!.enabled
  useEffect(() => { if (blocked) go(tvHref('recordings'), { replace: true }) }, [blocked, go])
  if (!ctx.ready || blocked) {
    return (
      <FlowFrame tab="live-tv" crumbs={crumbs} steps={RECORDING_STEPS} current="who" title="Set up recordings" skip={{ label: 'Cancel', href: tvHref('recordings') }}>
        {ctx.error ? <LoadFailed error={ctx.error} retry={ctx.retry} what="recording settings" /> : <Loading />}
      </FlowFrame>
    )
  }
  return <RecordingsSetup requested={step} saved={ctx.recording!} tv={ctx.tv} movies={ctx.movies} tuners={ctx.settings!.sources.reduce((n, s) => n + s.device.tuners, 0)} liveAccess={ctx.settings!.access} />
}

function RecordingsSetup({ requested, saved, tv, movies, tuners, liveAccess }: { requested?: string; saved: RecordingSettings; tv: Library[]; movies: Library[]; tuners: number; liveAccess: string }) {
  const go = useGo()
  const qc = useQueryClient()
  const [draft, setDraft] = useState<RecordingSettings>(() => startingDraft(saved, tv))
  const [keepFree, setKeepFree] = useState(() => String(Math.round(saved.minFreeBytes / 1e9)))
  const [reached, setReached] = useState<string[]>([])
  const [done, setDone] = useState<RecordingSettings | null>(null)
  const save = useRecordingMutation(saveRecordingSettings)
  const step = recordingSetupStep(requested, reached, !!done, saved.enabled)
  const target = tvHref('recordings', 'setup', step)
  const here = tvHref('recordings', 'setup', requested)
  useEffect(() => { if (target !== here) go(target, { replace: true }) }, [target, here, go])
  const patch = (p: Partial<RecordingSettings>) => { setDraft((d) => ({ ...d, ...p })); save.reset() }
  const advance = (from: string, to: string) => { setReached((r) => (r.includes(from) ? r : [...r, from])); go(tvHref('recordings', 'setup', to)) }
  const dirty = !done && !sameValue({ ...draft, enabled: true }, { ...saved, enabled: true })
  const freeError = keepFreeError(keepFree)
  const tvLib = tv.find((l) => l.id === draft.tvLibraryId)
  const movieLib = movies.find((l) => l.id === draft.movieLibraryId)

  const frame = {
    tab: 'live-tv' as const, crumbs: settingsCrumbs('live-tv', { label: saved.enabled ? 'Recordings' : 'Set up recordings', href: saved.enabled ? tvHref('recordings') : undefined }, ...(saved.enabled ? [{ label: 'Change' }] : [])),
    steps: RECORDING_STEPS, current: step, stepHref: (key: string) => tvHref('recordings', 'setup', key),
  }
  const leave = <StayCheck when={dirty} within={tvHref('recordings', 'setup')} what="Nothing on these steps is saved yet. Recordings stay as they were." />
  const cancel = { label: 'Cancel', href: saved.enabled ? tvHref('recordings') : tvHref() }

  if (step === 'who') {
    return (
      <FlowFrame {...frame} title="Who can record?" lede={`Recording also needs Live TV access. Right now ${liveAccess === 'none' ? 'no one' : liveAccess} can watch.`}
        skip={cancel} next={{ label: 'Continue to folders', onClick: () => advance('who', 'folders') }}>
        {leave}
        <fieldset>
          <legend className="sr-only">Who can record</legend>
          <Choice name="rec-access" value="admins" checked={draft.access === 'admins'} onChange={() => patch({ access: 'admins' })} title="Admins" />
          <Choice name="rec-access" value="everyone" checked={draft.access === 'everyone'} onChange={() => patch({ access: 'everyone' })} title="Everyone who can watch Live TV">
            Anyone can fill the disk, so set a free-space floor on the Timing and space step.
          </Choice>
        </fieldset>
      </FlowFrame>
    )
  }

  if (step === 'folders') {
    return (
      <FlowFrame {...frame} title="Where do recordings go?" lede="Into your libraries’ folders, so recordings show up like everything else."
        back={{ label: 'Who can record', href: tvHref('recordings', 'setup', 'who') }}
        next={{ label: 'Continue', onClick: () => advance('folders', 'timing'), disabled: !tvLib, reason: tvLib ? undefined : 'Choose a TV Shows library first.' }}
        help={<><h4>Why a library?</h4><p>Recorded episodes are saved as files in the library’s folder and show in it like everything else. Movies are recorded only if you choose a Movies library.</p></>}>
        {leave}
        <fieldset>
          <legend className="text-sm font-semibold">TV recordings</legend>
          <p className="mb-2 text-[13px] text-neutral-400">Series, news and manual recordings go here.</p>
          {tv.map((l) => (
            <Choice key={l.id} name="rec-tv" value={String(l.id)} checked={draft.tvLibraryId === l.id} onChange={() => patch({ tvLibraryId: l.id, tvPath: l.path })} title={l.name}>
              <span className="font-mono">{l.path}</span>
            </Choice>
          ))}
        </fieldset>
        <fieldset className="mt-6">
          <legend className="text-sm font-semibold">Movie recordings <span className="font-normal text-neutral-400">Optional</span></legend>
          <p className="mb-2 text-[13px] text-neutral-400">Films from the guide go here.{movies.length ? '' : ' Add a Movies library to record them.'}</p>
          <Choice name="rec-movie" value="0" checked={!movieLib} onChange={() => patch({ movieLibraryId: 0, moviePath: '' })} title="Don’t record movies" />
          {movies.map((l) => (
            <Choice key={l.id} name="rec-movie" value={String(l.id)} checked={draft.movieLibraryId === l.id} onChange={() => patch({ movieLibraryId: l.id, moviePath: l.path })} title={l.name}>
              <span className="font-mono">{l.path}</span>
            </Choice>
          ))}
        </fieldset>
      </FlowFrame>
    )
  }

  if (step === 'timing') {
    const reserve = Array.from({ length: Math.min(16, Math.max(tuners, draft.tunersReserved)) + 1 }, (_, i) => i)
    const warnings = WARNING_SECONDS.includes(draft.warningSeconds) ? WARNING_SECONDS : [...WARNING_SECONDS, draft.warningSeconds].sort((a, b) => a - b)
    const padding = (v: number) => (PADDING_MINUTES.includes(v) ? PADDING_MINUTES : [...PADDING_MINUTES, v].sort((a, b) => a - b))
    return (
      <FlowFrame {...frame} title="Timing and space" lede="Each starts at today’s setting, so you can go on without changing any of them."
        back={{ label: 'Folders', href: tvHref('recordings', 'setup', 'folders') }}
        next={{ label: 'Review', onClick: () => { if (!freeError) advance('timing', 'review') }, disabled: !!freeError, reason: freeError ? 'Fix Keep free first.' : undefined }}
        help={<><h4>Keep the server awake</h4><p>The server computer must stay awake for scheduled recordings. While recording, the Mac app keeps it awake.</p><h4>Space</h4><p>HD broadcasts use about 3–7 GB an hour.</p></>}>
        {leave}
        <div className="grid gap-x-6 sm:grid-cols-2 [&>div]:mt-4 [&>div:nth-child(-n+2)]:sm:mt-0">
          <Select label="Start early" hint="In case a programme starts ahead of time." value={draft.padBefore} options={padding(draft.padBefore).map((n) => [n, minutesWords(n)])} onChange={(padBefore) => patch({ padBefore })} />
          <Select label="End late" hint="In case it runs over." value={draft.padAfter} options={padding(draft.padAfter).map((n) => [n, minutesWords(n)])} onChange={(padAfter) => patch({ padAfter })} />
          <Field label="Keep free on the disk" hint="Recording stops before the disk gets this full. In GB." error={freeError}>
            {(p) => <input {...p} inputMode="decimal" value={keepFree} onChange={(e) => { setKeepFree(e.target.value); if (!keepFreeError(e.target.value)) patch({ minFreeBytes: Math.round(Number(e.target.value) * 1e9) }) }} className={`${fieldInput} sm:max-w-[10rem]`} />}
          </Field>
          <Select label="Tuners kept for watching" hint={`Of ${tuners}. Recordings never take these.`} value={draft.tunersReserved} options={reserve.map((n) => [n, String(n)])} onChange={(tunersReserved) => patch({ tunersReserved })} />
          <Select label="Warn before taking a tuner" hint="How long a viewer gets before a recording takes theirs." value={draft.warningSeconds} options={warnings.map((n) => [n, secondsWords(n)])} onChange={(warningSeconds) => patch({ warningSeconds })} />
        </div>
        <div className="mt-5">
          <Choice type="checkbox" name="rec-ads" checked={draft.findAds} onChange={(findAds) => patch({ findAds })} title="Find ad breaks">
            Marks ads after a recording ends so they can be skipped. Uses the processor for a few minutes per recording. Each person’s Playback › Ads choice decides whether to skip.
          </Choice>
        </div>
      </FlowFrame>
    )
  }

  if (step === 'review') {
    const refusal = save.isError ? recordingSaveError(save.error, draft.tvPath) : null
    const submit = () => save.mutate({ ...draft, enabled: true }, { onSuccess: (s) => { qc.setQueryData(liveTVRecordingSettingsQuery.queryKey, s); void qc.invalidateQueries({ queryKey: ['live-tv', 'capabilities'] }); setDone(s); go(tvHref('recordings', 'setup', 'on')) } })
    return (
      <FlowFrame {...frame} title={saved.enabled ? 'Check and save' : 'Check and turn on'}
        back={{ label: 'Timing and space', href: tvHref('recordings', 'setup', 'timing') }}
        next={{ label: saved.enabled ? 'Save recordings' : 'Turn on recordings', onClick: submit, busy: save.isPending, busyLabel: 'Saving…' }}>
        {leave}
        <Card><ReviewRows r={draft} tvLib={tvLib} movieLib={movieLib} href={(s) => tvHref('recordings', 'setup', s)} /></Card>
        {refusal && <Message tone="err" className="mt-4" title={refusal.title} focus>{refusal.body}</Message>}
      </FlowFrame>
    )
  }

  const result = done ?? saved
  const lib = tv.find((l) => l.id === result.tvLibraryId)
  const who = result.access === 'everyone' ? 'everyone who can watch Live TV' : 'admins'
  return (
    <FlowFrame {...frame} finished="ok" title="Recordings are on" lede={`${recordAccessLabel(result.access)} can record.`} next={{ label: 'Back to Live TV', href: tvHref() }}>
      <Outcome
        changed={[`Record appears on programmes in the guide for ${who}.`, `Recorded episodes go to ${result.tvPath} and show in ${lib?.name ?? 'TV Shows'}.`]}
        next={[
          <>Scheduled recordings are listed under <HrefLink href="/live-tv/recordings" className="underline">Live TV › Recordings</HrefLink>.</>,
          'Change any of this from Settings › Live TV › Recordings. Turning recordings off cancels any in progress, and it says so first.',
        ]}
      />
    </FlowFrame>
  )
}

/** Today's settings, with recording access as Admins when it was No one, and the only TV Shows library chosen. */
function startingDraft(saved: RecordingSettings, tv: Library[]): RecordingSettings {
  const d = { ...saved }
  if (d.access === 'none') d.access = 'admins'
  if (!tv.some((l) => l.id === d.tvLibraryId) && tv.length === 1) { d.tvLibraryId = tv[0].id; d.tvPath = tv[0].path }
  return d
}

function Select({ label, hint, value, options, onChange }: { label: ReactNode; hint?: ReactNode; value: number; options: [number, string][]; onChange: (v: number) => void }) {
  return (
    <Field label={label} hint={hint}>
      {(p) => <select {...p} value={value} onChange={(e) => onChange(Number(e.target.value))} className={fieldInput}>{options.map(([v, text]) => <option key={v} value={v}>{text}</option>)}</select>}
    </Field>
  )
}
