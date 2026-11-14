import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { enableLiveChannel, setLiveGuideChannel, type LiveChannel, type LiveGuideMatch } from '../../../api/liveTV'
import { Button, Card, HrefLink, HubPage, Message, Pill, Switch, fieldInput } from '../../../components/settings/Frame'
import { useInstantSave } from '../../../components/settings/hooks'
import { settingsCrumbs } from '../../../lib/settingsRoutes'
import { filterLiveChannels } from '../../../lib/liveTV'
import { liveGuideMatch, liveTVChannelSetting } from '../../../lib/liveTVSettingsRules'
import { channelCounts, channelRowNote, channelSaveError, listingsPill, type Wording } from '../../../lib/liveTVSettings'
import { tvHref, useGuideSettings, useLiveLevels, useLiveSettings } from './data'
import { LoadFailed, Loading } from './Parts'

/**
 * Channels (board tv-channels): a switch per channel that saves as it is
 * flipped, with the result in its row; a locked row says why. The listings
 * column shows each channel's match, and wrong listings can be turned off.
 */
export function ChannelsPage() {
  const settings = useLiveSettings()
  const levels = useLiveLevels()
  const guide = useGuideSettings(levels.guide)
  const [search, setSearch] = useState('')
  const [failure, setFailure] = useState<Wording | null>(null)
  const crumbs = settingsCrumbs('live-tv', { label: 'Channels' })
  if (!settings.data) {
    return <HubPage crumbs={crumbs} title="Channels">{settings.isError ? <LoadFailed error={settings.error} retry={() => void settings.refetch()} what="channels" /> : <Loading rows={5} />}</HubPage>
  }
  const { channels, sources } = settings.data
  const listings = guide.data?.enabled ? new Map(guide.data.channels.map((c) => [c.id, c])) : undefined
  const counts = channelCounts(channels, guide.data?.enabled ? guide.data.channels : undefined)
  const shown = filterLiveChannels(channels, search)
  const names = new Map(sources.map((s) => [s.id, s.name]))
  return (
    <HubPage crumbs={crumbs} title="Channels" lede="Choose which channels people see. Off: hidden from members, and anyone watching it stops."
      pill={{ tone: 'off', label: `${counts.shown} of ${counts.watchable} shown` }}>
      {channels.length === 0 ? (
        <Message tone="info" title="No channels yet">
          Run a channel scan in the HDHomeRun app, then refresh channels on the tuner’s page.{' '}
          {sources[0] ? <HrefLink href={tvHref('tuners', sources[0].id)}>Open {sources[0].name}</HrefLink> : <HrefLink href={tvHref('tuners', 'add')}>Add a tuner</HrefLink>}
        </Message>
      ) : (
        <>
          <div>
            <label htmlFor="channel-search" className="block text-sm font-semibold">Find a channel</label>
            <input id="channel-search" type="search" value={search} onChange={(e) => setSearch(e.target.value)} placeholder="Channel number or name" className={`${fieldInput} mt-1.5 sm:max-w-sm`} />
          </div>
          {failure && <Message tone="err" title={failure.title} focus>{failure.body}</Message>}
          {shown.length === 0 ? <p role="status" className="text-sm text-neutral-400">No channels match “{search}”.</p> : (
            <Card>
              <div role="table" aria-label="Channels" className="divide-y divide-line">
                <div role="row" className="hidden grid-cols-[minmax(0,1fr)_14rem_4rem] gap-4 px-5 py-2.5 text-xs font-semibold tracking-wider text-neutral-400 uppercase md:grid">
                  <span role="columnheader">Channel</span>
                  {listings && <span role="columnheader">Listings</span>}
                  <span role="columnheader" className="col-start-3 text-right">Show</span>
                </div>
                {shown.map((c) => (
                  <ChannelRow key={c.id} channel={c} tuner={names.get(c.sourceId)} listing={listings?.get(c.id)} onFailure={setFailure} />
                ))}
              </div>
            </Card>
          )}
        </>
      )}
    </HubPage>
  )
}

function ChannelRow({ channel, tuner, listing, onFailure }: { channel: LiveChannel; tuner?: string; listing?: LiveGuideMatch; onFailure: (w: Wording | null) => void }) {
  const client = useQueryClient()
  const show = useInstantSave()
  const list = useInstantSave()
  const [pending, setPending] = useState<boolean | null>(null)
  const [listPending, setListPending] = useState<boolean | null>(null)
  const choice = liveTVChannelSetting(channel)
  const note = channelRowNote(channel)
  const label = `${channel.number} ${channel.name}`
  const flip = async (on: boolean) => {
    setPending(on)
    onFailure(null)
    let failed: unknown
    const ok = await show.run(async () => { try { await enableLiveChannel(channel.id, on) } catch (e) { failed = e; throw e } }, on ? 'Shown just now.' : 'Hidden just now.')
    if (!ok && failed) onFailure(channelSaveError(channel, on, failed))
    setPending(null)
    void client.invalidateQueries({ queryKey: ['live-tv'] })
  }
  const flipListings = async (on: boolean) => {
    setListPending(on)
    await list.run(() => setLiveGuideChannel(channel.id, on), on ? 'Listings on.' : 'Listings off; it still plays.')
    setListPending(null)
    void client.invalidateQueries({ queryKey: ['live-tv', 'guide-settings'] })
  }
  const match = listing ? liveGuideMatch(listing.match) : undefined
  const pill = listing ? listingsPill(listing.match) : undefined
  const listed = listPending ?? (listing ? listing.listings && !match?.locked : false)
  const saved = show.state.status === 'saved' ? show.state.message : show.state.status === 'saving' ? 'Saving…' : undefined
  return (
    <div role="row" className="grid grid-cols-[minmax(0,1fr)_auto] items-center gap-x-4 gap-y-2 px-5 py-3 md:grid-cols-[minmax(0,1fr)_14rem_4rem]">
      <div role="cell" className="min-w-0">
        <b className={`font-semibold tabular-nums ${choice.locked ? 'text-neutral-400' : ''}`}>{label}</b>
        <div className="text-[13px] break-words text-neutral-400">
          {[tuner, note, saved && (show.state.status === 'saved' ? `✓ ${saved}` : saved)].filter(Boolean).join(' · ')}
          {listing?.feedName && listing.feedName !== channel.name && <> · listed as {listing.feedName}</>}
        </div>
      </div>
      {listing && pill && (
        <div role="cell" className="col-span-2 row-start-2 flex flex-wrap items-center gap-2 md:col-span-1 md:col-start-2 md:row-start-1">
          <span className="text-xs text-neutral-400 md:hidden">Listings</span>
          <Pill tone={pill.tone}>{listed || !match || match.locked ? pill.label : 'Listings off'}</Pill>
          {pill.note && listed && <span className="text-xs text-neutral-400">{pill.note}</span>}
          {match && !match.locked && (
            <Button small variant="quiet" className="px-2 text-xs" busy={list.state.status === 'saving'} onClick={() => void flipListings(!listed)} aria-label={`${listed ? 'Turn off' : 'Turn on'} listings for ${label}`}>
              {listed ? 'Turn off' : 'Turn on'}
            </Button>
          )}
          {list.state.status === 'failed' && <span role="alert" className="text-xs text-red-300">{list.state.message} It’s back as it was.</span>}
        </div>
      )}
      <div role="cell" className="col-start-2 row-start-1 flex justify-end md:col-start-3">
        <Switch checked={pending ?? choice.checked} disabled={choice.locked || show.state.status === 'saving'} onChange={(on) => void flip(on)} label={`Show ${label}`} />
      </div>
    </div>
  )
}
