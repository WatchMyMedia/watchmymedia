import { useState, type ReactNode } from 'react'
import { Button, Card, HrefLink, HubPage, ItemRow, Message, NextStep, Pill, Section, SetupGuide, type GuideItem } from '../../../components/settings/Frame'
import { useGo } from '../../../components/settings/hooks'
import { relativeTime } from '../../../lib/format'
import { settingsCrumbs } from '../../../lib/settingsRoutes'
import {
  ACCESS_LABEL, accessPhrase, channelCounts, guideNotice, liveSetupGuide, readDismissed, recordAccessLabel, sourceChannels, tunerPill, tunerUse, writeDismissed,
  type LiveGuideKey,
} from '../../../lib/liveTVSettings'
import { joinNames } from '../../../lib/liveRecordings'
import { tvHref, useGuideSettings, useLiveActivity, useLiveLevels, useLiveSettings, useRecordingSettings } from './data'
import { FlashBanner, LoadFailed, Loading } from './Parts'

/**
 * Settings › Live TV: the setup guide, the
 * tuners as a list with Add another tuner, and the pages for who can watch,
 * listings, recordings and channels. Problems are banners with their fix.
 */
export function LiveTVHub() {
  const go = useGo()
  const settings = useLiveSettings()
  const levels = useLiveLevels()
  const guide = useGuideSettings(levels.guide)
  const recordings = useRecordingSettings(levels.recordings)
  const { sessions, recordings: running } = useLiveActivity()
  const [dismissed, setDismissed] = useState(readDismissed)
  const crumbs = settingsCrumbs('live-tv')

  if (!settings.data) {
    return (
      <HubPage crumbs={crumbs} title="Live TV">
        {settings.isError ? <LoadFailed error={settings.error} retry={() => void settings.refetch()} /> : <Loading />}
      </HubPage>
    )
  }
  const { sources, channels, access } = settings.data
  const counts = channelCounts(channels, guide.data?.channels)
  const setup = liveSetupGuide({
    tuners: sources.length,
    guide: levels.guide ? { enabled: guide.data?.enabled ?? false } : undefined,
    recordings: levels.recordings ? { enabled: recordings.data?.enabled ?? false } : undefined,
    dismissed,
  })
  const notNow = (key: LiveGuideKey) => { const next = [...dismissed, key]; setDismissed(next); writeDismissed(next) }

  const words: Record<LiveGuideKey, { title: string; todo: string; done: ReactNode; action: ReactNode }> = {
    tuner: {
      title: 'Add a tuner', todo: 'Test the address, see its channels, save it.', done: `${joinNames(sources.map((s) => s.name))}.`,
      action: null,
    },
    access: {
      title: sources.length ? 'Who can watch' : 'Choose who can watch', todo: 'No one, admins or everyone. Starts as admins.', done: `${ACCESS_LABEL[access]}.`,
      action: <Button small onClick={() => go(tvHref('access'))}>Change</Button>,
    },
    guide: {
      title: guide.data?.enabled ? 'Guide listings' : 'Get guide listings', todo: 'What’s on now and next, with pictures.',
      done: guide.data ? `On · TMDB pictures ${guide.data.tmdb ? 'on' : 'off'}${guide.data.status.lastSuccessAt ? ` · fetched ${relativeTime(guide.data.status.lastSuccessAt)}` : ' · first fetch soon'}.` : 'On.',
      action: guide.data?.enabled
        ? <Button small onClick={() => go(tvHref('guide'))}>Manage</Button>
        : <><Button small variant="primary" onClick={() => go(tvHref('guide', 'setup'))}>Get guide listings</Button><Button small variant="quiet" onClick={() => notNow('guide')}>Not now</Button></>,
    },
    recordings: {
      title: 'Set up recordings', todo: 'Record programmes into your libraries.',
      done: recordings.data ? `On · ${recordAccessLabel(recordings.data.access).toLowerCase()} can record.` : 'On.',
      action: recordings.data?.enabled
        ? <Button small onClick={() => go(tvHref('recordings'))}>Manage</Button>
        : <><Button small variant="primary" onClick={() => go(tvHref('recordings'))}>Set up recordings</Button><Button small variant="quiet" onClick={() => notNow('recordings')}>Not now</Button></>,
    },
  }
  const items: GuideItem[] = setup.items.map((t) => ({
    key: t.key, title: words[t.key].title, state: t.state, waits: t.waits, optional: t.optional,
    description: t.state === 'done' ? words[t.key].done : words[t.key].todo,
    // A dimmed task waits; its button shows once it can start.
    action: t.key === 'tuner' || (t.state === 'todo' && t.waits) ? undefined : words[t.key].action,
  }))

  const anyOn = sources.some((s) => s.enabled)
  const pill = !sources.length ? { tone: 'off' as const, label: 'Not set up' }
    : access === 'none' ? { tone: 'off' as const, label: 'Hidden' }
      : !anyOn ? { tone: 'warn' as const, label: 'Tuners off' } : { tone: 'ok' as const, label: 'On' }
  const lede = sources.length
    ? `${sources.length} ${sources.length === 1 ? 'tuner' : 'tuners'} · ${counts.watchable} ${counts.watchable === 1 ? 'channel' : 'channels'} · ${accessPhrase(access)}`
    : 'Watch and record over-the-air channels through an HDHomeRun tuner on your network.'

  const notice = guide.data?.enabled ? guideNotice(guide.data.status, relativeTime) : null
  const banners = (
    <>
      <FlashBanner page="hub" />
      {sources.filter((s) => s.enabled && s.lastError).map((s) => (
        <Message key={s.id} tone="err" title={`${s.name} isn’t answering`}
          actions={<HrefLink href={tvHref('tuners', s.id)} className="inline-flex min-h-[36px] items-center rounded-md border border-line-2 px-3 text-sm text-neutral-100 no-underline hover:bg-surface-2">Open {s.name}</HrefLink>}>
          The last time its channels were read, nothing answered at {s.address}. The last channel list is kept, and its channels may not play.
        </Message>
      ))}
      {notice && notice.tone === 'err' && (
        <Message tone="err" title={notice.title}>
          {notice.body}{' '}
          {notice.email ? <HrefLink href={tvHref('guide', 'setup', 'turn-on')}>Enter the HDHomeRun account email</HrefLink> : <HrefLink href={tvHref('guide')}>Open Guide listings</HrefLink>}
        </Message>
      )}
    </>
  )

  const more: { key: string; href: string; title: string; detail: string; show: boolean }[] = [
    { key: 'access', href: tvHref('access'), title: 'Who can watch', detail: ACCESS_LABEL[access], show: !setup.show },
    { key: 'guide', href: tvHref('guide'), title: 'Guide listings', detail: guide.data?.enabled ? 'On' : 'Off', show: levels.guide && (!setup.show || dismissed.includes('guide')) },
    { key: 'recordings', href: tvHref('recordings'), title: 'Recordings', detail: recordings.data?.enabled ? `On · ${recordAccessLabel(recordings.data.access).toLowerCase()} can record` : 'Off', show: levels.recordings && (!setup.show || dismissed.includes('recordings')) },
    { key: 'channels', href: tvHref('channels'), title: 'Channels', detail: `${counts.shown} of ${counts.watchable} shown${counts.withoutListings ? ` · ${counts.withoutListings} without listings` : ''}`, show: true },
  ]

  return (
    <HubPage crumbs={crumbs} title="Live TV" lede={lede} pill={pill} banner={banners}>
      {!sources.length && (
        <NextStep title="Add your HDHomeRun" action={<Button variant="primary" guarded onClick={() => go(tvHref('tuners', 'add'))}>Add a tuner</Button>}>
          You’ll need its address on your network. Nothing is tuned while it’s being added.
        </NextStep>
      )}
      {sources.length > 0 && (
        <Section title="Tuners">
          <Card>
            {sources.map((s) => {
              const use = tunerUse(s, sessions, running)
              const p = tunerPill(s, use.inUse)
              const n = sourceChannels(channels, s.id).length
              return (
                <ItemRow key={s.id} href={tvHref('tuners', s.id)} title={s.name} pill={<Pill tone={p.tone}>{p.label}</Pill>}
                  detail={`${s.device.model} · ${s.address} · ${n} ${n === 1 ? 'channel' : 'channels'}`} />
              )
            })}
            <ItemRow title={<span className="font-normal text-neutral-400">Have another HDHomeRun? Its channels join the same guide.</span>}
              control={<Button small guarded onClick={() => go(tvHref('tuners', 'add'))}>Add another tuner</Button>} />
          </Card>
        </Section>
      )}
      {setup.show && <SetupGuide items={items} done={setup.done} total={setup.total} />}
      {sources.length > 0 && (
        <Section title="More">
          <Card>
            {more.filter((m) => m.show).map((m) => <ItemRow key={m.key} href={m.href} title={m.title} detail={m.detail} />)}
          </Card>
        </Section>
      )}
    </HubPage>
  )
}
