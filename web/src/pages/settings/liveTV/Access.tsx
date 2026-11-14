import { useEffect, useState } from 'react'
import { setLiveAccess } from '../../../api/liveTV'
import { Choice, Message } from '../../../components/settings/Frame'
import { FlowFrame } from '../../../components/settings/Flow'
import { errorText, useGo } from '../../../components/settings/hooks'
import { settingsCrumbs } from '../../../lib/settingsRoutes'
import { ACCESS_LABEL, accessSaveLabel, accessWarning, liveViewers, type LiveAccess } from '../../../lib/liveTVSettings'
import { setFlash, tvHref, useLiveActivity, useLiveMutation, useLiveSettings } from './data'
import { LoadFailed, Loading, StayCheck } from './Parts'

const CHOICES: [LiveAccess, string, string | undefined][] = [
  ['none', 'No one', 'Live TV and the guide are hidden. Recording stops being offered.'],
  ['admins', 'Admins', undefined],
  ['everyone', 'Everyone', 'Live TV appears in the top bar for every account.'],
]

/**
 * Who can watch (board tv-watch): a one-step task, because any change stops
 * everyone watching. The viewers who would stop are on the screen and on the
 * button before it saves (§3.6).
 */
export function AccessFlow() {
  const settings = useLiveSettings()
  const crumbs = settingsCrumbs('live-tv', { label: 'Who can watch' })
  if (!settings.data) {
    return (
      <FlowFrame tab="live-tv" crumbs={crumbs} title="Who can watch Live TV?" back={{ label: 'Cancel', href: tvHref() }}>
        {settings.isError ? <LoadFailed error={settings.error} retry={() => void settings.refetch()} /> : <Loading rows={3} />}
      </FlowFrame>
    )
  }
  return <Access saved={settings.data.access} />
}

function Access({ saved }: { saved: LiveAccess }) {
  const go = useGo()
  const [choice, setChoice] = useState(saved)
  const [leaving, setLeaving] = useState(false)
  const save = useLiveMutation(setLiveAccess)
  const { sessions } = useLiveActivity()
  const viewers = liveViewers(sessions)
  const changed = choice !== saved
  const warning = changed ? accessWarning(viewers.names) : null
  useEffect(() => { if (leaving) go(tvHref()) }, [leaving, go])
  const submit = () => {
    if (!changed) { setLeaving(true); return }
    const stopped = viewers.count
    save.mutate(choice, {
      onSuccess: () => {
        setFlash('hub', {
          title: `${ACCESS_LABEL[choice]} can watch Live TV now`,
          body: `${choice === 'none' ? 'Live TV and the guide are hidden.' : choice === 'admins' ? 'Live TV is in the top bar for admins only.' : 'Live TV is in the top bar for every account.'}${stopped === 1 ? ' The person watching was stopped and can start again if they’re still allowed.' : stopped ? ` The ${stopped} people watching were stopped and can start again if they’re still allowed.` : ''}`,
        })
        setLeaving(true)
      },
    })
  }
  return (
    <FlowFrame tab="live-tv" crumbs={settingsCrumbs('live-tv', { label: 'Who can watch' })} title="Who can watch Live TV?"
      back={{ label: 'Cancel', href: tvHref() }}
      next={{ label: accessSaveLabel(viewers.count, changed), onClick: submit, busy: save.isPending, busyLabel: 'Saving…' }}>
      <StayCheck when={changed && !leaving} what="Who can watch hasn’t changed. Nothing was saved and no one was stopped." />
      <fieldset>
        <legend className="sr-only">Who can watch Live TV</legend>
        {CHOICES.map(([value, title, hint]) => (
          <Choice key={value} name="live-access" value={value} checked={choice === value} onChange={() => { setChoice(value); save.reset() }} title={title}>{hint}</Choice>
        ))}
      </fieldset>
      <div className="mt-5 space-y-3">
        {warning && <Message tone="warn" title={warning.title}>{warning.body}</Message>}
        {save.isError && <Message tone="err" title="Couldn’t save who can watch" focus>{errorText(save.error)} Who can watch is still {ACCESS_LABEL[saved].toLowerCase()}, and no one was stopped.</Message>}
      </div>
    </FlowFrame>
  )
}
