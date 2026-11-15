import { useEffect, useState } from 'react'
import { testLiveSource, updateLiveSource, type LiveSource } from '../../../api/liveTV'
import { visibleConfirmationError } from '../../../api/withConfirm'
import { Field, HrefLink, Message, fieldInput } from '../../../components/settings/Frame'
import { FlowFrame } from '../../../components/settings/Flow'
import { useGo } from '../../../components/settings/hooks'
import { settingsCrumbs } from '../../../lib/settingsRoutes'
import { liveViewers, tunerMoveError, type Wording } from '../../../lib/liveTVSettings'
import { joinNames } from '../../../lib/liveRecordings'
import { setFlash, tvHref, useLiveActivity, useLiveMutation, useLiveSettings } from './data'
import { LoadFailed, Loading, StayCheck } from './Parts'

/**
 * A tuner's name and address (board tv-move): one step with Cancel and Save.
 * Only an address change asks for the password, and the new address has to
 * answer as the same device; a different one is offered as another tuner.
 */
export function EditTunerFlow({ id }: { id: string }) {
  const settings = useLiveSettings()
  const source = settings.data?.sources.find((s) => s.id === id)
  if (!source) {
    const crumbs = settingsCrumbs('live-tv', { label: 'Tuner', href: tvHref('tuners', id) }, { label: 'Name and address' })
    return (
      <FlowFrame tab="live-tv" crumbs={crumbs} title="Name and address" back={{ label: 'Cancel', href: tvHref('tuners', id) }}>
        {settings.isError ? <LoadFailed error={settings.error} retry={() => void settings.refetch()} />
          : settings.data ? <Message tone="info" title="There’s no tuner at this address">It may have been removed. <HrefLink href={tvHref()}>Back to Live TV</HrefLink></Message>
            : <Loading rows={2} />}
      </FlowFrame>
    )
  }
  return <EditTuner source={source} />
}

function EditTuner({ source }: { source: LiveSource }) {
  const go = useGo()
  const { sessions } = useLiveActivity()
  const [name, setName] = useState(source.name)
  const [address, setAddress] = useState(source.address)
  const [refusal, setRefusal] = useState<(Wording & { addAsAnother?: boolean }) | null>(null)
  const [leaving, setLeaving] = useState<string | null>(null)
  const save = useLiveMutation((changes: { name: string; address: string }) => updateLiveSource(source, changes))
  const back = tvHref('tuners', source.id)
  const moved = address.trim() !== source.address
  const dirty = name.trim() !== source.name || moved
  const viewers = moved ? liveViewers(sessions, source.id).names : []
  const nameError = name.trim() ? undefined : 'Give the tuner a name.'
  // Leave once the leave check knows nothing is unsaved.
  useEffect(() => { if (leaving) go(leaving) }, [leaving, go])

  const submit = () => {
    if (save.isPending || nameError || !address.trim()) return
    if (!dirty) { setLeaving(back); return }
    setRefusal(null)
    const target = address.trim()
    save.mutate({ name: name.trim(), address: target }, {
      onSuccess: () => {
        setFlash(`tuner:${source.id}`, { title: moved ? 'Address changed' : 'Name changed', body: moved ? `${name.trim()} answered at ${target}. Recordings pick up again at the new address.` : undefined })
        setLeaving(back)
      },
      onError: async (error) => {
        if (!visibleConfirmationError(error)) return
        const words = tunerMoveError(error, target, { name: source.name, deviceId: source.device.id })
        setRefusal(words)
        // Name the device that answered, so a second tuner can be told apart.
        if (words.addAsAnother) {
          try {
            const found = await testLiveSource(target)
            setRefusal(tunerMoveError(error, target, { name: source.name, deviceId: source.device.id }, found.device.id))
          } catch { /* The words without its ID stand. */ }
        }
      },
    })
  }

  const crumbs = settingsCrumbs('live-tv', { label: source.name, href: back }, { label: 'Name and address' })
  return (
    <FlowFrame tab="live-tv" crumbs={crumbs} title="Name and address"
      back={{ label: 'Cancel', href: back }}
      next={{ label: 'Save', onClick: submit, guarded: moved, busy: save.isPending, busyLabel: 'Saving…', disabled: !!nameError || !address.trim() }}>
      <StayCheck when={dirty && !leaving} what="Your changes to the name and address aren’t saved." />
      <Field label="Name" error={nameError}>
        {(p) => <input {...p} value={name} maxLength={128} disabled={save.isPending} onChange={(e) => { setName(e.target.value); setRefusal(null) }} className={fieldInput} />}
      </Field>
      <Field label="HDHomeRun address" hint={`The new address has to answer as this tuner, device ${source.device.id}.`}>
        {(p) => <input {...p} value={address} disabled={save.isPending} autoComplete="off" autoCapitalize="none" spellCheck={false} onChange={(e) => { setAddress(e.target.value); setRefusal(null) }} className={`${fieldInput} font-mono`} />}
      </Field>
      <div className="mt-5 space-y-3">
        {viewers.length > 0 && (
          <Message tone="warn" title={`${joinNames(viewers)} ${viewers.length === 1 ? 'is' : 'are'} watching on this tuner`}>
            Changing the address stops them. Recordings pick up again at the new address.
          </Message>
        )}
        {refusal && (
          <Message tone="err" title={refusal.title} focus>
            {refusal.addAsAnother
              ? <>{refusal.body.replace(/ If that’s a second tuner, add it as another tuner\.$/, '')} If that’s a second tuner, <HrefLink href={tvHref('tuners', 'add', 'address', address.trim())}>add it as another tuner</HrefLink>.</>
              : refusal.body}
          </Message>
        )}
      </div>
    </FlowFrame>
  )
}
