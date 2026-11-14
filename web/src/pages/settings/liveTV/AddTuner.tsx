import { useEffect, useState, type ReactNode } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { addLiveSource, setLiveAccess, testLiveSource, type LiveSource } from '../../../api/liveTV'
import { liveTVSettingsQuery } from '../../../api/queries'
import { visibleConfirmationError } from '../../../api/withConfirm'
import { Button, Choice, Field, GuardedCaption, HrefLink, Message, Outcome, Spinner, fieldInput } from '../../../components/settings/Frame'
import { FlowFrame } from '../../../components/settings/Flow'
import { errorText, useGo } from '../../../components/settings/hooks'
import { settingsCrumbs, type Crumb } from '../../../lib/settingsRoutes'
import {
  ACCESS_LABEL, accessOutcome, accessPhrase, addTunerStep, addTunerSteps, asksAccess, channelCounts, existingTuner, sourceChannels,
  tunerAddError, tunerRepeat, tunerTestError, type LiveAccess, type Wording,
} from '../../../lib/liveTVSettings'
import { tvHref, useGuideSettings, useLiveLevels, useLiveMutation, useLiveSettings, useRecordingSettings } from './data'
import { LoadFailed, Loading, StayCheck } from './Parts'

type Inspection = Awaited<ReturnType<typeof testLiveSource>>
type Tested = { address: string; result: Inspection }

const crumbs: Crumb[] = settingsCrumbs('live-tv', { label: 'Add a tuner' })

/**
 * Add a tuner (PLAN-ADMIN-SETTINGS.md §5.4): Address → What was found → Who
 * can watch (the first tuner only) → Added. The same flow adds every later
 * tuner. Once the tuner is saved, its id is the address's last segment, so a
 * reload stays on the step.
 */
export function AddTunerFlow({ step: requested, rest }: { step?: string; rest?: string }) {
  const go = useGo()
  const client = useQueryClient()
  const settings = useLiveSettings()
  const sources = settings.data?.sources ?? []
  const added = requested === 'access' || requested === 'done' ? sources.find((s) => s.id === rest) : undefined
  const askAccess = asksAccess(sources, added?.id)
  const steps = addTunerSteps(askAccess)
  const [address, setAddress] = useState(() => (requested === 'address' && rest ? rest : ''))
  const [name, setName] = useState('')
  const [tested, setTested] = useState<Tested | null>(null)
  const test = useMutation({ mutationFn: testLiveSource })
  const add = useLiveMutation(({ address, name }: { address: string; name: string }) => addLiveSource(address, name))
  const trimmed = address.trim()
  const current = tested && tested.address === trimmed ? tested : null
  const step = addTunerStep({ requested, tested: !!current, added: !!added, askAccess })
  const target = step === 'access' || step === 'done' ? tvHref('tuners', 'add', step, added?.id) : tvHref('tuners', 'add', step, step === 'address' && requested === 'address' ? rest : undefined)
  const here = tvHref('tuners', 'add', requested, rest)
  useEffect(() => {
    if (settings.data && target !== here) go(target, { replace: true })
  }, [settings.data, target, here, go])

  const runTest = () => {
    if (!trimmed || test.isPending) return
    add.reset()
    test.mutate(trimmed, {
      onSuccess: (result) => { setTested({ address: trimmed, result }); go(tvHref('tuners', 'add', 'found')) },
    })
  }
  const save = () => {
    if (!current || add.isPending) return
    add.mutate({ address: current.address, name: name.trim() }, {
      onSuccess: (source) => {
        // The list holds the new tuner before the next step reads it.
        client.setQueryData(liveTVSettingsQuery.queryKey, (old) => (old ? { ...old, sources: [...old.sources.filter((s) => s.id !== source.id), source] } : old))
        go(tvHref('tuners', 'add', askAccess ? 'access' : 'done', source.id))
      },
    })
  }

  const common = { tab: 'live-tv' as const, crumbs, steps, current: step, stepHref: added ? undefined : (key: string) => tvHref('tuners', 'add', key) }
  const leave = <StayCheck when={!added && trimmed !== ''} within={tvHref('tuners', 'add')} what="The tuner isn’t saved. Nothing was changed on this server or the tuner." />
  if (!settings.data) {
    return <FlowFrame {...common} title="Add a tuner">{settings.isError ? <LoadFailed error={settings.error} retry={() => void settings.refetch()} /> : <Loading />}</FlowFrame>
  }

  if (step === 'address') {
    return (
      <FlowFrame {...common} title="Where is your HDHomeRun?" lede="Testing finds the device and reads its channel list. It doesn’t tune a channel or save anything."
        help={<AddressHelp />}
        next={{ label: 'Test this address', onClick: runTest, guarded: true, busy: test.isPending, busyLabel: 'Testing…', disabled: !trimmed, reason: trimmed ? undefined : 'Enter the tuner’s address first.' }}>
        {leave}
        <AddressScreen address={address} onChange={(v) => { setAddress(v); test.reset() }} busy={test.isPending}
          error={test.isError && visibleConfirmationError(test.error) ? tunerTestError(test.error, trimmed) : null} onRetry={runTest} />
      </FlowFrame>
    )
  }

  if (step === 'found' && current) {
    const repeat = existingTuner(sources, current.result.device.id)
    return (
      <FlowFrame {...common} title="Is this your tuner?"
        back={{ label: 'Address', href: tvHref('tuners', 'add', 'address') }}
        next={{ label: 'Save tuner', onClick: save, guarded: true, busy: add.isPending, busyLabel: 'Saving…', disabled: !!repeat, reason: repeat ? 'This tuner is already on this server.' : undefined }}>
        {leave}
        <FoundScreen inspection={current.result} name={name} onName={(v) => { setName(v); add.reset() }} busy={add.isPending} repeat={repeat}
          error={add.isError && visibleConfirmationError(add.error) ? tunerAddError(add.error, current.address, existingTuner(sources, current.result.device.id)) : null} />
      </FlowFrame>
    )
  }

  if (step === 'access' && added) return <AccessStep frame={common} source={added} access={settings.data.access} />
  if (step === 'done' && added) return <AddedStep frame={common} source={added} first={askAccess} />
  return <FlowFrame {...common} title="Add a tuner"><Loading /></FlowFrame>
}

function AddressHelp() {
  return (
    <>
      <h4>Finding the address</h4>
      <p>Open the HDHomeRun app, or your router’s list of devices. The tuner’s label also has its Device ID.</p>
      <h4>Why a test first?</h4>
      <p>So the next step can show you the device and its channels before anything is saved.</p>
    </>
  )
}

/** Address: the field and why a test found nothing. Shared with first-run setup. */
export function AddressScreen({ address, onChange, busy, error, onRetry }: { address: string; onChange: (v: string) => void; busy: boolean; error: Wording | null; onRetry: () => void }) {
  return (
    <div className="space-y-4">
      <Field label="HDHomeRun address" hint="Its address on your network, or hdhomerun.local." error={error && error.title.startsWith('That isn’t') ? error.body : undefined}>
        {(p) => <input {...p} value={address} disabled={busy} autoComplete="off" autoCapitalize="none" spellCheck={false} placeholder="hdhomerun.local" onChange={(e) => onChange(e.target.value)} className={`${fieldInput} font-mono`} />}
      </Field>
      {busy && <p role="status" className="flex items-center gap-2 text-sm text-neutral-300"><Spinner />Reading the tuner’s details and channel list…</p>}
      {error && !error.title.startsWith('That isn’t') && (
        <Message tone="err" title={error.title} focus actions={<Button small onClick={onRetry}>Test again</Button>}>{error.body}</Message>
      )}
    </div>
  )
}

/** What was found: the device, its channels and an optional name. Shared with first-run setup. */
export function FoundScreen({ inspection, name, onName, busy, error, repeat }: {
  inspection: Inspection; name: string; onName: (v: string) => void; busy: boolean; error: (Wording & { existing?: boolean }) | null; repeat?: LiveSource
}) {
  const { device, channels } = inspection
  const refused = error ?? (repeat ? tunerRepeat(repeat) : null)
  return (
    <div className="space-y-5">
      <dl className="grid grid-cols-[auto_minmax(0,1fr)] gap-x-6 gap-y-2 rounded-xl border border-line bg-surface p-5 text-sm">
        <dt className="text-neutral-400">Device</dt><dd className="break-words">{device.model} · {device.tuners} {device.tuners === 1 ? 'tuner' : 'tuners'}</dd>
        <dt className="text-neutral-400">Device ID</dt><dd><code className="font-mono">{device.id}</code></dd>
        <dt className="text-neutral-400">Channels</dt><dd>{channels.toLocaleString()} found</dd>
      </dl>
      <Field label="Name" optional hint="Shown where there’s more than one tuner.">
        {(p) => <input {...p} value={name} disabled={busy} maxLength={128} placeholder={device.name} onChange={(e) => onName(e.target.value)} className={fieldInput} />}
      </Field>
      {channels === 0 && (
        <Message tone="warn" title="No channels found">
          The tuner answered but has no channel list. Run a channel scan in the HDHomeRun app, then test again. You can save it now and refresh channels later.
        </Message>
      )}
      <p className="text-sm text-neutral-400">Copy-protected channels can’t be played here, so they stay hidden.</p>
      {refused && (
        <Message tone="err" title={refused.title} focus={!!error}
          actions={refused.existing && repeat ? <OpenTuner source={repeat} /> : undefined}>
          {refused.body}
        </Message>
      )}
    </div>
  )
}

function OpenTuner({ source }: { source: LiveSource }) {
  return <HrefLink href={tvHref('tuners', source.id)} className="inline-flex min-h-[36px] items-center rounded-md border border-line-2 px-3 text-sm text-neutral-100 hover:bg-surface-2">Open {source.name}</HrefLink>
}

type Frame = { tab: 'live-tv'; crumbs: Crumb[]; steps: ReturnType<typeof addTunerSteps>; current: string; stepHref?: (key: string) => string }

const ACCESS_CHOICES: [LiveAccess, string, string][] = [
  ['none', 'No one', 'Live TV stays hidden for now.'],
  ['admins', 'Admins', 'Try it yourself first.'],
  ['everyone', 'Everyone', 'Live TV appears in the top bar for every account.'],
]

/** Who can watch, the first tuner's third step. The tuner is saved, so there is no Back. */
function AccessStep({ frame, source, access }: { frame: Frame; source: LiveSource; access: LiveAccess }) {
  const go = useGo()
  const [choice, setChoice] = useState<LiveAccess>(access)
  const save = useLiveMutation(setLiveAccess)
  const finish = () => {
    if (choice === access) { go(tvHref('tuners', 'add', 'done', source.id)); return }
    save.mutate(choice, { onSuccess: () => go(tvHref('tuners', 'add', 'done', source.id)) })
  }
  return (
    <FlowFrame {...frame} title="Who can watch Live TV?" lede={`${source.name} is saved. This also decides who sees the guide and, later, who can be allowed to record.`}
      next={{ label: 'Save and finish', onClick: finish, busy: save.isPending, busyLabel: 'Saving…' }}>
      <fieldset>
        <legend className="sr-only">Who can watch Live TV</legend>
        {ACCESS_CHOICES.map(([value, title, hint]) => (
          <Choice key={value} name="live-access" value={value} checked={choice === value} onChange={() => { setChoice(value); save.reset() }} title={title}>{hint}</Choice>
        ))}
      </fieldset>
      {save.isError && <Message tone="err" className="mt-4" title="Couldn’t save who can watch" focus>{errorText(save.error)} {source.name} is saved, and who can watch is still {ACCESS_LABEL[access].toLowerCase()}.</Message>}
    </FlowFrame>
  )
}

function AddedStep({ frame, source, first }: { frame: Frame; source: LiveSource; first: boolean }) {
  const settings = useLiveSettings()
  const levels = useLiveLevels()
  const guide = useGuideSettings(levels.guide)
  const recordings = useRecordingSettings(levels.recordings)
  const channels = sourceChannels(settings.data?.channels ?? [], source.id)
  const counts = channelCounts(channels)
  const access = settings.data?.access ?? 'admins'
  const guideOff = levels.guide && guide.data && !guide.data.enabled
  const next = guideOff
    ? { label: 'Get guide listings →', href: tvHref('guide', 'setup') }
    : { label: 'Back to Live TV', href: tvHref() }
  const changed: ReactNode[] = [
    first ? accessOutcome(access) : 'Its channels join the same guide as your other tuners.',
    `${counts.watchable} ${counts.watchable === 1 ? 'channel' : 'channels'} can be watched.${counts.protected ? ` ${counts.protected} copy-protected ${counts.protected === 1 ? 'one is' : 'ones are'} hidden.` : ''}`,
  ]
  const after: ReactNode[] = [
    guideOff ? 'Get guide listings to see what’s on. Without them, channels are a plain list.' : guide.data?.enabled ? 'Its channels get listings with the next fetch.' : null,
    <><HrefLink href={tvHref('channels')} className="underline">Choose which channels show</HrefLink>.</>,
    levels.recordings && recordings.data && !recordings.data.enabled ? 'Recordings need a TV Shows library and can be set up any time.' : null,
  ]
  return (
    <FlowFrame {...frame} finished="ok" title={`${source.name} is added`} lede={`${counts.total} ${counts.total === 1 ? 'channel' : 'channels'} · ${accessPhrase(access)}`}
      skip={guideOff ? { label: 'Back to Live TV', href: tvHref() } : undefined} next={next}>
      <Outcome changed={changed} next={after} />
    </FlowFrame>
  )
}

/**
 * First-run setup's Live TV step: the same Address and What was found
 * screens as Settings, in place, with their own buttons.
 */
export function SetupTuner() {
  const [address, setAddress] = useState('')
  const [name, setName] = useState('')
  const [tested, setTested] = useState<Tested | null>(null)
  const settings = useLiveSettings()
  const test = useMutation({ mutationFn: testLiveSource })
  const add = useLiveMutation(({ address, name }: { address: string; name: string }) => addLiveSource(address, name))
  const trimmed = address.trim()
  const runTest = () => {
    if (!trimmed || test.isPending) return
    add.reset()
    test.mutate(trimmed, { onSuccess: (result) => setTested({ address: trimmed, result }) })
  }
  if (tested) {
    const repeat = existingTuner(settings.data?.sources ?? [], tested.result.device.id)
    return (
      <form className="space-y-4" onSubmit={(e) => { e.preventDefault(); if (!repeat && !add.isPending) add.mutate({ address: tested.address, name: name.trim() }) }}>
        <h3 className="font-semibold">Is this your tuner?</h3>
        <FoundScreen inspection={tested.result} name={name} onName={(v) => { setName(v); add.reset() }} busy={add.isPending} repeat={repeat}
          error={add.isError && visibleConfirmationError(add.error) ? tunerAddError(add.error, tested.address, repeat) : null} />
        <div className="flex flex-wrap items-center gap-3">
          <Button onClick={() => { setTested(null); add.reset() }} disabled={add.isPending}><span aria-hidden="true">←</span> Address</Button>
          <span className="flex-1" />
          <GuardedCaption />
          <Button type="submit" variant="primary" guarded busy={add.isPending} busyLabel="Saving…" disabled={!!repeat}>Save tuner</Button>
        </div>
      </form>
    )
  }
  return (
    <form className="space-y-4" onSubmit={(e) => { e.preventDefault(); runTest() }}>
      <p className="text-sm text-neutral-400">Testing finds the device and reads its channel list. It doesn’t tune a channel or save anything.</p>
      <AddressScreen address={address} onChange={(v) => { setAddress(v); test.reset() }} busy={test.isPending}
        error={test.isError && visibleConfirmationError(test.error) ? tunerTestError(test.error, trimmed) : null} onRetry={runTest} />
      <div className="flex flex-wrap items-center justify-end gap-3">
        <GuardedCaption />
        <Button type="submit" guarded busy={test.isPending} busyLabel="Testing…" disabled={!trimmed}>Test this address</Button>
      </div>
    </form>
  )
}
