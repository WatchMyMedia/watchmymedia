import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { refreshLiveSource, removeLiveSource, updateLiveSource, type LiveSource } from '../../../api/liveTV'
import { liveTVSettingsQuery, liveTVSourceRemovalQuery } from '../../../api/queries'
import { ApiError } from '../../../api/client'
import { visibleConfirmationError } from '../../../api/withConfirm'
import { Button, Card, HrefLink, HubPage, ItemRow, Message, RemoveSection, SaveNote, Spinner, Switch } from '../../../components/settings/Frame'
import { ConfirmDialog } from '../../../components/settings/Dialogs'
import { errorText, useGo, useInstantSave } from '../../../components/settings/hooks'
import { relativeTime } from '../../../lib/format'
import { settingsCrumbs } from '../../../lib/settingsRoutes'
import { liveViewers, refreshSummary, sourceChannels, tunerPill, tunerRefreshError, tunerRemoval, tunerSwitchNote, tunerUse, tunerUseLine, type Wording } from '../../../lib/liveTVSettings'
import { setFlash, tvHref, useLiveActivity, useLiveMutation, useLiveSettings } from './data'
import { FlashBanner, LoadFailed, Loading } from './Parts'

/** One tuner's page: its switch, Refresh channels, name and address, and Remove apart at the bottom. */
export function TunerPage({ id }: { id: string }) {
  const settings = useLiveSettings()
  const source = settings.data?.sources.find((s) => s.id === id)
  if (!settings.data) {
    return (
      <HubPage crumbs={settingsCrumbs('live-tv', { label: 'Tuner' })} title="Tuner">
        {settings.isError ? <LoadFailed error={settings.error} retry={() => void settings.refetch()} /> : <Loading />}
      </HubPage>
    )
  }
  if (!source) {
    return (
      <HubPage crumbs={settingsCrumbs('live-tv', { label: 'Tuner' })} title="This tuner isn’t here">
        <Message tone="info" title="There’s no tuner at this address">
          It may have been removed. <HrefLink href={tvHref()}>Back to Live TV</HrefLink>
        </Message>
      </HubPage>
    )
  }
  return <Tuner source={source} />
}

function Tuner({ source }: { source: LiveSource }) {
  const go = useGo()
  const client = useQueryClient()
  const settings = useLiveSettings()
  const { sessions, recordings } = useLiveActivity()
  const channels = sourceChannels(settings.data?.channels ?? [], source.id)
  const use = tunerUse(source, sessions, recordings)
  const pill = tunerPill(source, use.inUse)
  const toggle = useInstantSave()
  const [pending, setPending] = useState<boolean | null>(null)
  const [refresh, setRefresh] = useState<{ status: 'idle' | 'busy' | 'done' | 'failed'; summary?: string; error?: Wording }>({ status: 'idle' })
  const [removing, setRemoving] = useState(false)

  const flip = async (enabled: boolean) => {
    setPending(enabled)
    await toggle.run(() => updateLiveSource(source, { enabled }), tunerSwitchNote(enabled))
    setPending(null)
    void client.invalidateQueries({ queryKey: ['live-tv'] })
  }
  const runRefresh = async () => {
    const before = channels
    setRefresh({ status: 'busy' })
    try {
      await refreshLiveSource(source.id)
      const fresh = await client.fetchQuery({ ...liveTVSettingsQuery, staleTime: 0 })
      setRefresh({ status: 'done', summary: refreshSummary(before, sourceChannels(fresh.channels, source.id)) })
    } catch (error) {
      setRefresh({ status: 'failed', error: tunerRefreshError(source, error) })
    } finally {
      void client.invalidateQueries({ queryKey: ['live-tv'] })
    }
  }
  // A failure the server remembered from an earlier refresh shows too.
  const failure = refresh.status === 'failed' ? refresh.error : refresh.status !== 'done' && source.lastError ? tunerRefreshError(source) : undefined
  const channelLine = refresh.status === 'done'
    ? <span className="text-emerald-300">✓ Refreshed just now · {refresh.summary}</span>
    : `${channels.length} ${channels.length === 1 ? 'channel' : 'channels'} · ${source.refreshedAt ? `read ${relativeTime(source.refreshedAt)}` : 'not read yet'}`
  const checked = pending ?? source.enabled

  return (
    <HubPage crumbs={settingsCrumbs('live-tv', { label: source.name })} title={source.name}
      lede={`${source.device.model} · ID ${source.device.id} · ${tunerUseLine(source.device.tuners, use.inUse, use.viewers)}`}
      pill={pill} banner={<FlashBanner page={`tuner:${source.id}`} />}
      help={<><h4>Refreshing channels</h4><p>Refresh reads the tuner’s existing channel list. To find new channels, run a channel scan in the HDHomeRun app first.</p></>}>
      <Card>
        <ItemRow title="Use this tuner" detail="Off: its channels can’t be watched or recorded, and anyone watching stops."
          control={<Switch checked={checked} disabled={toggle.state.status === 'saving'} onChange={(on) => void flip(on)} label="Use this tuner" />}>
          <div className="mt-1"><SaveNote state={toggle.state} /></div>
        </ItemRow>
        <ItemRow title="Channels" detail={refresh.status === 'busy' ? <span className="inline-flex items-center gap-2"><Spinner />Reading the tuner’s channel list…</span> : channelLine}
          control={<Button small busy={refresh.status === 'busy'} busyLabel="Refreshing…" onClick={() => void runRefresh()}>Refresh channels</Button>} />
        <ItemRow title="Name and address" detail={`${source.name} · ${source.address}`}
          control={<Button small guarded onClick={() => go(tvHref('tuners', source.id, 'edit'))}>Change</Button>} />
      </Card>
      {failure && (
        <Message tone="err" title={failure.title} focus={refresh.status === 'failed'}
          actions={<><Button small onClick={() => void runRefresh()} busy={refresh.status === 'busy'}>Refresh again</Button><Button small guarded onClick={() => go(tvHref('tuners', source.id, 'edit'))}>Change address</Button></>}>
          {failure.body}
        </Message>
      )}
      <RemoveSection>
        <ItemRow title="Remove this tuner" detail="Its channels and their listings are deleted."
          control={<Button small variant="danger" guarded onClick={() => setRemoving(true)}>Remove…</Button>} />
      </RemoveSection>
      {removing && <RemoveTuner source={source} onClose={() => setRemoving(false)} onRemoved={() => {
        setFlash('hub', { title: `${source.name} was removed`, body: 'Its channels and their listings are gone. Finished recordings stay in your libraries.' })
        go(tvHref())
      }} />}
    </HubPage>
  )
}

/** Remove a tuner: a named dialog with the server's numbers; away from home it asks for the password. */
function RemoveTuner({ source, onClose, onRemoved }: { source: LiveSource; onClose: () => void; onRemoved: () => void }) {
  const settings = useLiveSettings()
  const { sessions } = useLiveActivity()
  const removal = useQuery(liveTVSourceRemovalQuery(source.id))
  const remove = useLiveMutation(removeLiveSource)
  const channels = sourceChannels(settings.data?.channels ?? [], source.id)
  const viewers = liveViewers(sessions, source.id).sessions
  const gone = removal.error instanceof ApiError && removal.error.status === 404
  const words = tunerRemoval(source, channels, viewers, removal.data)
  const counting = removal.isPending ? ['Counting recordings…'] : []
  return (
    <ConfirmDialog title={`Remove ${source.name}?`} confirmLabel="Remove tuner" guarded busy={remove.isPending}
      stops={[...words.stops, ...counting]} deleted={words.deleted} stays={words.stays}
      error={gone ? 'This tuner was already removed.' : remove.isError && visibleConfirmationError(remove.error) ? `${errorText(remove.error)} The tuner wasn’t removed.` : undefined}
      onClose={onClose} onConfirm={() => (gone ? onRemoved() : remove.mutate(source.id, { onSuccess: onRemoved }))}>
      {words.atRisk && <Message tone="warn" className="mt-3" title="Recordings at risk">{words.atRisk}</Message>}
      {removal.isError && !gone && <p className="mt-3 text-sm text-neutral-400">The scheduled recordings couldn’t be counted. Any on its channels may not record.</p>}
    </ConfirmDialog>
  )
}
