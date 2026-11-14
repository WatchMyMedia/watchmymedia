import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { refreshLiveGuide, setLiveGuide, type LiveGuideSettings } from '../../../api/liveTV'
import { settingsQuery } from '../../../api/settings'
import { Button, Card, Choice, Field, HrefLink, HubPage, ItemRow, Message, NextStep, Outcome, RemoveSection, SaveNote, Spinner, Switch, fieldInput } from '../../../components/settings/Frame'
import { FlowFrame } from '../../../components/settings/Flow'
import { ConfirmDialog } from '../../../components/settings/Dialogs'
import { errorText, useGo, useInstantSave } from '../../../components/settings/hooks'
import { relativeTime } from '../../../lib/format'
import { settingsCrumbs, settingsHref } from '../../../lib/settingsRoutes'
import { liveGuideStatus } from '../../../lib/liveTVSettingsRules'
import { GUIDE_REFRESHES_A_DAY, GUIDE_STEPS, guideAsksEmail, guideMatchLine, guideNotice, guideRefreshError, guideSetupStep } from '../../../lib/liveTVSettings'
import { tvHref, useGuideMutation, useGuideSettings, useLiveLevels, useLiveMutation, useLiveSettings, useRecordingSettings } from './data'
import { LoadFailed, Loading, StayCheck } from './Parts'

const when = (iso?: string) => (iso ? new Date(iso).toLocaleString([], { weekday: 'long', hour: 'numeric', minute: '2-digit' }) : '')

/**
 * Get guide listings (PLAN-ADMIN-SETTINGS.md §5.4): Turn on → Pictures → On.
 * Turn on saves; Pictures saves at Finish. The HDHomeRun account email shows
 * only when the tuner's firmware asks for one.
 */
export function GuideSetupFlow({ step: requested }: { step?: string }) {
  const go = useGo()
  const guide = useGuideSettings()
  const settings = useLiveSettings()
  const [finished, setFinished] = useState(false)
  const crumbs = settingsCrumbs('live-tv', { label: 'Get guide listings' })
  const data = guide.data
  const step = data ? guideSetupStep(requested, data.enabled, finished) : undefined
  const target = step && tvHref('guide', 'setup', step)
  const here = tvHref('guide', 'setup', requested)
  useEffect(() => { if (target && target !== here) go(target, { replace: true }) }, [target, here, go])

  const common = { tab: 'live-tv' as const, crumbs, steps: GUIDE_STEPS, current: step ?? 'turn-on', stepHref: (key: string) => tvHref('guide', 'setup', key) }
  if (!data || !settings.data) {
    const error = guide.error ?? settings.error
    return <FlowFrame {...common} title="Get guide listings">{error ? <LoadFailed error={error} retry={() => { void guide.refetch(); void settings.refetch() }} /> : <Loading />}</FlowFrame>
  }
  if (!settings.data.sources.length) {
    return (
      <FlowFrame {...common} title="Get guide listings" skip={{ label: 'Back to Live TV', href: tvHref() }} next={{ label: 'Add a tuner', href: tvHref('tuners', 'add'), guarded: true }}>
        <Message tone="warn" title="Listings need a tuner">Listings are fetched for your tuner’s channels. Add one first.</Message>
      </FlowFrame>
    )
  }
  if (step === 'pictures') return <PicturesStep frame={common} data={data} onDone={() => { setFinished(true); go(tvHref('guide', 'setup', 'on')) }} />
  if (step === 'on') return <GuideOn frame={common} data={data} />
  return <TurnOnStep frame={common} data={data} />
}

type Frame = { tab: 'live-tv'; crumbs: ReturnType<typeof settingsCrumbs>; steps: typeof GUIDE_STEPS; current: string; stepHref: (key: string) => string }

function TurnOnStep({ frame, data }: { frame: Frame; data: LiveGuideSettings }) {
  const go = useGo()
  const [email, setEmail] = useState(data.email)
  const save = useGuideMutation(({ email }: { email: string }) => setLiveGuide(true, data.tmdb, email))
  const refresh = useLiveMutation(refreshLiveGuide)
  const asks = guideAsksEmail(data.status.state, data.email)
  const typed = email.trim()
  const changed = typed !== data.email
  const turnOn = () => {
    if (data.enabled && !changed) { go(tvHref('guide', 'setup', 'pictures')); return }
    save.mutate({ email: typed }, {
      onSuccess: () => {
        // A new email is tried at once, as long as a refresh is left today.
        if (data.enabled && changed && data.status.refreshesLeft > 0) refresh.mutate()
        go(tvHref('guide', 'setup', 'pictures'))
      },
    })
  }
  return (
    <FlowFrame {...frame} title="Get guide listings"
      lede="Listings come from HDHomeRun’s guide for your tuner: what’s on now and for the next two days, with descriptions."
      skip={{ label: 'Cancel', href: tvHref() }}
      next={{ label: data.enabled ? (changed ? 'Save and continue' : 'Continue') : 'Turn on listings', onClick: turnOn, busy: save.isPending, busyLabel: 'Turning on…' }}>
      <StayCheck when={changed && !save.isPending} within={tvHref('guide', 'setup')} what="The HDHomeRun account email isn’t saved." />
      <div className="space-y-4">
        <Card padded>
          <h3 className="font-semibold">What turning this on does</h3>
          <ul className="mt-2 list-disc space-y-1.5 pl-5 text-sm text-neutral-300 marker:text-neutral-500">
            <li>Fetches listings within 10 minutes, then about once a day.</li>
            <li>Only this server contacts HDHomeRun’s guide, about once a day. Nothing about who watches what is sent.</li>
            <li>Lets people set reminders, and recordings follow programme times.</li>
          </ul>
        </Card>
        {asks && (
          <Message tone="warn" title="Your tuner’s firmware asks for an HDHomeRun account">
            <p>Enter the email of the account the tuner is registered to. It’s sent to HDHomeRun with your tuners’ device IDs. Updating the tuner in the HDHomeRun app avoids it; leave it empty to stop sending it.</p>
            <Field label="HDHomeRun account email">
              {(p) => <input {...p} type="email" value={email} maxLength={254} autoComplete="email" placeholder="name@example.com" disabled={save.isPending} onChange={(e) => { setEmail(e.target.value); save.reset() }} className={fieldInput} />}
            </Field>
          </Message>
        )}
        {save.isError && <Message tone="err" title="Couldn’t turn listings on" focus>{errorText(save.error)} Listings are still {data.enabled ? 'on' : 'off'}.</Message>}
      </div>
    </FlowFrame>
  )
}

function PicturesStep({ frame, data, onDone }: { frame: Frame; data: LiveGuideSettings; onDone: () => void }) {
  const [tmdb, setTmdb] = useState(data.tmdb)
  const server = useQuery(settingsQuery)
  const save = useGuideMutation((tmdb: boolean) => setLiveGuide(true, tmdb, data.email))
  const finish = () => { if (tmdb === data.tmdb) onDone(); else save.mutate(tmdb, { onSuccess: onDone }) }
  return (
    <FlowFrame {...frame} title="Pictures for programmes" lede="HDHomeRun’s listings have pictures for some programmes. TMDB can fill in the rest."
      back={{ label: 'Turn on', href: tvHref('guide', 'setup', 'turn-on') }}
      next={{ label: 'Finish', onClick: finish, busy: save.isPending, busyLabel: 'Saving…' }}>
      <StayCheck when={tmdb !== data.tmdb && !save.isPending} within={tvHref('guide', 'setup')} what="Listings are on. The pictures choice on this step isn’t saved." />
      <Choice type="checkbox" name="guide-tmdb" checked={tmdb} onChange={setTmdb} title="Use TMDB pictures where the listings have none">
        Programme titles are looked up on TMDB with the server’s key. News and sport are never looked up.
      </Choice>
      {server.data && !server.data.tmdbConfigured && (
        <Message tone="info" className="mt-4" title="There’s no TMDB key yet">
          Pictures come only from the listings until you <HrefLink href={settingsHref('general', 'tmdb')}>add a key in General</HrefLink>.
        </Message>
      )}
      {save.isError && <Message tone="err" className="mt-4" title="Couldn’t save the pictures choice" focus>{errorText(save.error)} Listings are on; TMDB pictures are still {data.tmdb ? 'on' : 'off'}.</Message>}
    </FlowFrame>
  )
}

function GuideOn({ frame, data }: { frame: Frame; data: LiveGuideSettings }) {
  const levels = useLiveLevels()
  const recordings = useRecordingSettings(levels.recordings)
  const offerRecordings = levels.recordings && recordings.data && !recordings.data.enabled
  return (
    <FlowFrame {...frame} finished="ok" title="Guide listings are on" lede="The first fetch starts within 10 minutes."
      skip={offerRecordings ? { label: 'Back to Live TV', href: tvHref() } : undefined}
      next={offerRecordings ? { label: 'Set up recordings →', href: tvHref('recordings') } : { label: 'Back to Live TV', href: tvHref() }}>
      <Outcome
        changed={['Listings are fetched about once a day.', data.tmdb ? 'TMDB fills in missing pictures.' : 'Pictures come only from the listings.']}
        next={[
          'The guide fills in when the first fetch ends. The Live TV page shows when.',
          <>Channels that don’t line up with the listings are flagged on <HrefLink href={tvHref('channels')} className="underline">Channels</HrefLink>.</>,
          `You can refresh by hand ${GUIDE_REFRESHES_A_DAY} times a day.`,
        ]}
      />
    </FlowFrame>
  )
}

/** Guide listings once they're on (board tv-guide): Refresh now, pictures, the account, channels, and turning off at the bottom. */
export function GuidePage() {
  const go = useGo()
  const guide = useGuideSettings()
  const crumbs = settingsCrumbs('live-tv', { label: 'Guide listings' })
  const tmdb = useInstantSave()
  const [tmdbPending, setTmdbPending] = useState<boolean | null>(null)
  const save = useGuideMutation(({ enabled, tmdb }: { enabled: boolean; tmdb: boolean }) => setLiveGuide(enabled, tmdb, guide.data?.email ?? ''))
  const refresh = useLiveMutation(refreshLiveGuide)
  const [confirmOff, setConfirmOff] = useState(false)
  const [turnedOff, setTurnedOff] = useState(false)

  if (!guide.data) {
    return <HubPage crumbs={crumbs} title="Guide listings">{guide.isError ? <LoadFailed error={guide.error} retry={() => void guide.refetch()} what="guide listings" /> : <Loading />}</HubPage>
  }
  const data = guide.data
  const { status } = data
  if (!data.enabled) {
    return (
      <HubPage crumbs={crumbs} title="Guide listings" lede="What’s on now and next, from HDHomeRun’s guide, with pictures." pill={{ tone: 'off', label: 'Off' }}
        banner={turnedOff ? <Message tone="ok" title="Listings are off">The programmes and pictures were deleted. Channels still play.</Message> : undefined}>
        <NextStep title="Get guide listings" action={<Button variant="primary" onClick={() => go(tvHref('guide', 'setup'))}>Get guide listings</Button>}>
          Fetched about once a day by this server. Without them, channels are a plain list.
        </NextStep>
      </HubPage>
    )
  }
  const shown = liveGuideStatus(status.state)
  const notice = guideNotice(status, relativeTime)
  const lede = status.lastSuccessAt
    ? `Fetched ${relativeTime(status.lastSuccessAt)}${status.listedThrough ? ` · listings through ${when(status.listedThrough)}` : ''}`
    : 'Not fetched yet'
  const flipTmdb = async (on: boolean) => {
    setTmdbPending(on)
    await tmdb.run(() => save.mutateAsync({ enabled: true, tmdb: on }), 'Every programme is looked up again.')
    setTmdbPending(null)
  }
  const refreshWords = refresh.isError ? guideRefreshError(refresh.error) : null
  return (
    <HubPage crumbs={crumbs} title="Guide listings" lede={lede} pill={{ tone: shown.tone, label: shown.tone === 'ok' ? 'On' : shown.label }}>
      <Card>
        <ItemRow title="Refresh now"
          detail={`${status.refreshesLeft} of ${GUIDE_REFRESHES_A_DAY} left today. Listings refresh by themselves about once a day${status.nextAt ? `; next about ${when(status.nextAt)}` : ''}.`}
          control={<Button small busy={refresh.isPending || status.state === 'fetching'} busyLabel="Fetching…" disabled={shown.busy || status.refreshesLeft <= 0} onClick={() => refresh.mutate()}>Refresh now</Button>} />
        <ItemRow title="TMDB pictures" detail="Fill in pictures the listings don’t have. Changing this looks every programme up again."
          control={<Switch checked={tmdbPending ?? data.tmdb} disabled={tmdb.state.status === 'saving'} onChange={(on) => void flipTmdb(on)} label="TMDB pictures" />}>
          <div className="mt-1"><SaveNote state={tmdb.state} /></div>
        </ItemRow>
        {guideAsksEmail(status.state, data.email) && (
          <ItemRow title="HDHomeRun account" detail={data.email || 'No email set'}
            control={<Button small onClick={() => go(tvHref('guide', 'setup', 'turn-on'))}>Change</Button>} />
        )}
        <ItemRow href={tvHref('channels')} title="Channels and their listings" detail={guideMatchLine(data.channels)} />
      </Card>
      {status.lastSuccessAt && <p className="text-sm text-neutral-400">{status.programmes.toLocaleString()} programmes in the guide.</p>}
      {notice && (
        <Message tone={notice.tone} title={notice.spin ? <span className="inline-flex items-center gap-2"><Spinner />{notice.title}</span> : notice.title}
          actions={notice.email ? <Button small onClick={() => go(tvHref('guide', 'setup', 'turn-on'))}>Enter the HDHomeRun account email</Button> : undefined}>
          {notice.body}
        </Message>
      )}
      {refreshWords && <Message tone="err" title={refreshWords.title} focus>{refreshWords.body}</Message>}
      <RemoveSection title="Turn off">
        <ItemRow title="Stop getting listings" detail="Deletes the programmes and pictures. Reminders and scheduled recordings that follow listings stop."
          control={<Button small variant="danger" onClick={() => { save.reset(); setConfirmOff(true) }}>Turn off…</Button>} />
      </RemoveSection>
      {confirmOff && (
        <ConfirmDialog title="Stop getting listings?" confirmLabel="Turn off listings" busy={save.isPending}
          deleted={[`The ${status.programmes.toLocaleString()} programmes in the guide, and their pictures.`]}
          stops={['Reminders, and scheduled recordings that follow listings.']}
          stays={['Your channels, which still play without listings.', 'Finished recordings.']}
          error={save.isError ? `${errorText(save.error)} Listings are still on.` : undefined}
          onClose={() => setConfirmOff(false)}
          onConfirm={() => save.mutate({ enabled: false, tmdb: data.tmdb }, { onSuccess: () => { setConfirmOff(false); setTurnedOff(true) } })} />
      )}
    </HubPage>
  )
}
