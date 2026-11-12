import { visibleConfirmationError } from '../../api/withConfirm'
import { useEffect, useId, useRef, useState, type ReactNode } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { addLiveSource, enableLiveChannel, refreshLiveGuide, refreshLiveSource, removeLiveSource, setLiveAccess, setLiveGuide, setLiveGuideChannel, testLiveSource, updateLiveSource, type LiveChannel, type LiveSource } from '../../api/liveTV'
import { liveTVGuideSettingsQuery, liveTVSettingsQuery, serverQuery } from '../../api/queries'
import { CheckRow, PageHeader, Panel, StatusPill, primaryButton, secondaryButton, textInput } from '../../components/SettingsPanel'
import { liveGuideMatch, liveGuideStatus, liveTVChannelSetting } from '../../lib/liveTVSettingsRules'
import { GUIDE_API_LEVEL } from '../../lib/liveGuide'
import { RecordingSettings } from '../../components/liveTV/RecordingSettings'
import { RECORDING_API_LEVEL } from '../../lib/liveRecordings'
import { ServerOnly } from './SettingsLayout'

/** Every write refreshes navigation, setup and settings, including a failed lineup refresh. */
function useLiveMutation<T, V>(mutationFn: (variables: V) => Promise<T>) {
  const client = useQueryClient()
  return useMutation({ mutationFn, onSettled: () => client.invalidateQueries({ queryKey: ['live-tv'] }) })
}

export function LiveTVSettings() {
  return (
    <>
      <PageHeader title="Live TV">Watch your antenna channels through an HDHomeRun tuner on your network.</PageHeader>
      <ServerOnly><LiveTVSettingsContent /></ServerOnly>
    </>
  )
}

function LiveTVSettingsContent() {
  const settings = useQuery(liveTVSettingsQuery)
  const server = useQuery(serverQuery)
  if (!settings.data) return settings.isError
    ? <div className="space-y-3"><Notice>{settings.error.message}</Notice><button type="button" className={secondaryButton} onClick={() => void settings.refetch()}>Try again</button></div>
    : <p role="status" className="text-neutral-400">Loading Live TV settings…</p>
  const { sources, channels, access } = settings.data
  return (
    <div className="space-y-6">
      {settings.isError && <Notice>Couldn’t reload settings: {settings.error.message}</Notice>}
      {sources.length === 0 ? (
        <Panel title="Add a tuner" description="This server connects to the tuner itself. Viewers only need to reach this server, at home or away.">
          <div className="border-t border-line p-5"><LiveTunerSetup /></div>
        </Panel>
      ) : sources.map((source) => <TunerCard key={source.id} source={source} />)}
      <AccessSettings access={access} />
      {(server.data?.apiLevel ?? 0) >= RECORDING_API_LEVEL && <RecordingSettings />}
      {sources.length > 0 && (server.data?.apiLevel ?? 0) >= GUIDE_API_LEVEL && <GuideListings />}
      {sources.length > 0 && <ChannelSettings channels={channels} />}
    </div>
  )
}

/** Shared by Settings and first run; a successful test never allocates a tuner. */
export function LiveTunerSetup() {
  const [address, setAddress] = useState('')
  const [name, setName] = useState('')
  const test = useMutation({ mutationFn: testLiveSource })
  const add = useLiveMutation(({ address, name }: { address: string; name: string }) => addLiveSource(address, name))
  const busy = test.isPending || add.isPending
  const inspection = test.variables === address.trim() ? test.data : undefined
  const addressHelp = useId()
  return (
    <form className="space-y-4" onSubmit={(event) => {
      event.preventDefault()
      if (busy || !address.trim()) return
      if (inspection) add.mutate({ address: address.trim(), name: name.trim() })
      else test.mutate(address.trim())
    }}>
      <div className="flex flex-wrap items-end gap-3">
        <label className="min-w-0 flex-1 basis-56 text-sm text-neutral-300">
          HDHomeRun address
          <input value={address} required disabled={busy} autoComplete="off" autoCapitalize="none" spellCheck={false} aria-describedby={addressHelp}
            onChange={(event) => { setAddress(event.target.value); test.reset(); add.reset() }}
            className={`${textInput} mt-1 block min-h-[44px] w-full font-mono`} placeholder="hdhomerun.local" />
        </label>
        <button type="button" disabled={busy || !address.trim()} className={`${secondaryButton} min-h-[44px]`} onClick={() => { add.reset(); test.mutate(address.trim()) }}>
          {test.isPending ? 'Testing…' : 'Test'}
        </button>
      </div>
      <p id={addressHelp} className="text-sm text-neutral-400">Enter an IP address or hostname. Find it in the HDHomeRun app or your router’s device list.</p>
      {test.isPending && <p role="status" className="text-sm text-neutral-300">Reading the tuner’s details and channel list…</p>}
      {test.isError && visibleConfirmationError(test.error) && <Notice>{test.error.message}. Check the address and that this server can reach the tuner.</Notice>}
      {inspection && (
        <div className="space-y-4 border-t border-line pt-4">
          <div className="flex flex-wrap items-center gap-3" role="status">
            <div className="min-w-0 flex-1 basis-48">
              <p className="break-words font-semibold">{inspection.device.name}</p>
              <DeviceDetails device={inspection.device} />
            </div>
            <StatusPill tone={inspection.channels > 0 ? 'ok' : 'warn'}>{inspection.channels.toLocaleString()} channels</StatusPill>
          </div>
          {inspection.channels === 0 && <Notice>This tuner has no channels yet. You can save it now, run a channel scan in the HDHomeRun app, then refresh channels here.</Notice>}
          <p className="text-sm text-neutral-400">Testing didn’t tune a channel or change anything on the tuner. Copy-protected channels can’t be played here.</p>
          <label className="block text-sm text-neutral-300">
            Tuner name (optional)
            <input value={name} disabled={add.isPending} maxLength={128} placeholder={inspection.device.name} onChange={(event) => { setName(event.target.value); add.reset() }} className={`${textInput} mt-1 block min-h-[44px] w-full`} />
          </label>
          <div className="flex flex-wrap gap-2">
            <button type="submit" disabled={busy} className={`${primaryButton} min-h-[44px]`}>{add.isPending ? 'Saving…' : 'Save tuner'}</button>
            <button type="button" disabled={busy} className={`${secondaryButton} min-h-[44px]`} onClick={() => { test.reset(); add.reset(); setName('') }}>Cancel</button>
          </div>
        </div>
      )}
      {add.isError && visibleConfirmationError(add.error) && <Notice>{add.error.message}</Notice>}
      <p className="text-sm text-neutral-400">New servers allow admins only. Choose who can watch and which channels to show in Settings › Live TV.</p>
    </form>
  )
}

function TunerCard({ source }: { source: LiveSource }) {
  const [editing, setEditing] = useState(false)
  const [confirming, setConfirming] = useState(false)
  const update = useLiveMutation((changes: Partial<Pick<LiveSource, 'address' | 'name' | 'enabled'>>) => updateLiveSource(source, changes))
  const refresh = useLiveMutation(refreshLiveSource)
  const remove = useLiveMutation(removeLiveSource)
  const busy = update.isPending || refresh.isPending || remove.isPending
  return (
    <Panel title={<span className="break-words">{source.name}</span>} description={<DeviceDetails device={source.device} />} actions={<StatusPill tone={!source.enabled ? 'off' : source.lastError ? 'warn' : 'ok'}>{!source.enabled ? 'Disabled' : source.lastError ? 'Refresh failed' : 'Configured'}</StatusPill>}>
      <div className="space-y-3 border-t border-line px-5 py-4">
        <p className="break-all font-mono text-sm text-neutral-300">{source.address}</p>
        <p className="text-sm text-neutral-400">Last successful refresh: {source.refreshedAt ? <time dateTime={source.refreshedAt}>{new Date(source.refreshedAt).toLocaleString()}</time> : 'Not yet refreshed'}</p>
        {source.lastError && <Notice>Couldn’t refresh: {source.lastError}. The last successful channel list is kept. Check the address and connection, then try again.</Notice>}
        {refresh.isError && !source.lastError && <Notice>{refresh.error.message}</Notice>}
        {refresh.isSuccess && !source.lastError && <p role="status" className="text-sm text-emerald-400">Channels refreshed.</p>}
        {editing ? (
          <TunerEdit source={source} busy={busy} error={update.error?.message} onSave={(changes) => update.mutate(changes, { onSuccess: () => setEditing(false) })} onCancel={() => { setEditing(false); update.reset() }} />
        ) : (
          <>
            <div className="flex flex-wrap gap-2">
              <button type="button" disabled={busy} onClick={() => refresh.mutate(source.id)} className={`${secondaryButton} min-h-[44px]`}>{refresh.isPending ? 'Refreshing…' : 'Refresh channels'}</button>
              <button type="button" disabled={busy} onClick={() => { update.reset(); setEditing(true) }} className={`${secondaryButton} min-h-[44px]`}>Edit name or address</button>
              <button type="button" disabled={busy} onClick={() => { remove.reset(); setConfirming(true) }} className={`${secondaryButton} min-h-[44px]`}>Remove tuner…</button>
            </div>
            {update.isError && visibleConfirmationError(update.error) && <Notice>{update.error.message}</Notice>}
          </>
        )}
        <p className="text-sm text-neutral-400">Refresh reads the tuner’s existing channel list. To find new channels, run a channel scan in the HDHomeRun app first.</p>
      </div>
      <CheckRow label="Enable this tuner" hint="Disabling stops its active viewers and keeps its saved channels and address." checked={update.isPending ? update.variables.enabled ?? source.enabled : source.enabled} disabled={busy || editing} onChange={(enabled) => update.mutate({ enabled })} />
      {confirming && <RemoveTunerConfirmation source={source} busy={remove.isPending} error={remove.error?.message} onCancel={() => setConfirming(false)} onConfirm={() => remove.mutate(source.id, { onSuccess: () => setConfirming(false) })} />}
    </Panel>
  )
}

function TunerEdit({ source, busy, error, onSave, onCancel }: {
  source: LiveSource; busy: boolean; error?: string
  onSave: (changes: { name: string; address: string }) => void; onCancel: () => void
}) {
  const [name, setName] = useState(source.name)
  const [address, setAddress] = useState(source.address)
  return (
    <form className="space-y-3" onSubmit={(event) => { event.preventDefault(); if (!busy && address.trim() && name.trim()) onSave({ name: name.trim(), address: address.trim() }) }}>
      <label className="block text-sm text-neutral-300">Tuner name<input value={name} required maxLength={128} disabled={busy} onChange={(event) => setName(event.target.value)} className={`${textInput} mt-1 block min-h-[44px] w-full`} /></label>
      <label className="block text-sm text-neutral-300">HDHomeRun address<input value={address} required disabled={busy} autoComplete="off" autoCapitalize="none" spellCheck={false} onChange={(event) => setAddress(event.target.value)} className={`${textInput} mt-1 block min-h-[44px] w-full font-mono`} /></label>
      <p className="text-sm text-neutral-400">The server checks that a new address belongs to device <span className="font-mono">{source.device.id}</span>. If you replaced the tuner, remove this one and add the new one.</p>
      {error && <Notice>{error}</Notice>}
      <div className="flex flex-wrap gap-2">
        <button type="submit" disabled={busy || !address.trim() || !name.trim()} className={`${primaryButton} min-h-[44px]`}>{busy ? 'Saving…' : 'Save changes'}</button>
        <button type="button" disabled={busy} onClick={onCancel} className={`${secondaryButton} min-h-[44px]`}>Cancel</button>
      </div>
    </form>
  )
}

function AccessSettings({ access }: { access: 'none' | 'admins' | 'everyone' }) {
  const save = useLiveMutation(setLiveAccess)
  const id = useId()
  const choices = [
    ['none', 'No one', 'Live TV is off and out of the menu. The tuner stays set up.'],
    ['admins', 'Admins', 'Admin accounts, wherever they sign in from.'],
    ['everyone', 'Everyone with an account', 'Live TV appears for everyone signed in to this server.'],
  ] as const
  return (
    <Panel title="Who can watch" description="Each viewer uses one tuner. Other apps may also be using the device; its tuner count is its total capacity.">
      <fieldset disabled={save.isPending}>
        <legend className="sr-only">Who can watch Live TV</legend>
        {choices.map(([value, label, hint]) => (
          <label key={value} className="flex min-h-[44px] items-start gap-3 border-t border-line px-5 py-4">
            <input type="radio" name={id} value={value} checked={(save.isPending ? save.variables : access) === value} onChange={() => save.mutate(value)} className="mt-1 h-[18px] w-[18px] shrink-0 accent-accent" />
            <span><span className="block text-sm font-medium">{label}</span><span className="mt-0.5 block text-sm text-neutral-400">{hint}</span></span>
          </label>
        ))}
      </fieldset>
      <div className="space-y-2 border-t border-line px-5 py-3">
        <p className="text-sm text-neutral-400">Changes save immediately. People who lose access are stopped.</p>
        {save.isPending && <p role="status" className="text-sm text-neutral-300">Saving access…</p>}
        {save.isError && <Notice>{save.error.message}</Notice>}
      </div>
    </Panel>
  )
}

/** Listings from SiliconDust (PLAN-LIVE-TV-GUIDE.md §6.2): on or off, TMDB pictures, the status, Refresh now and each channel's match. */
function GuideListings() {
  const settings = useQuery({ ...liveTVGuideSettingsQuery, refetchInterval: (query) => liveGuideStatus(query.state.data?.status.state ?? '').busy ? 3000 : false })
  const save = useLiveMutation(({ enabled, tmdb, email }: { enabled: boolean; tmdb: boolean; email: string }) => setLiveGuide(enabled, tmdb, email))
  const refresh = useLiveMutation(refreshLiveGuide)
  const listing = useLiveMutation(({ id, listings }: { id: string; listings: boolean }) => setLiveGuideChannel(id, listings))
  const [email, setEmail] = useState<string | null>(null)
  const emailId = useId()
  const description = 'What’s on each channel, from SiliconDust, the HDHomeRun’s maker. This server fetches them about once a day; viewers never contact SiliconDust. A channel without listings still plays.'
  if (!settings.data) return <Panel title="Guide listings" description={description}>
    <div className="border-t border-line p-5">{settings.isError ? <Notice>{settings.error.message}</Notice> : <p role="status" className="text-sm text-neutral-400">Loading listings settings…</p>}</div>
  </Panel>
  const data = settings.data
  const { status } = data
  const saving = save.isPending ? save.variables : undefined
  const enabled = saving?.enabled ?? data.enabled
  const tmdb = saving?.tmdb ?? data.tmdb
  const typed = email ?? data.email
  const shown = liveGuideStatus(status.state)
  const when = (iso?: string) => iso && new Date(iso).toLocaleString([], { weekday: 'short', hour: 'numeric', minute: '2-digit' })
  const busy = save.isPending || refresh.isPending
  return (
    <Panel title="Guide listings" description={description}>
      <CheckRow label="Get listings" hint="2 days ahead, or 14 with SiliconDust’s DVR subscription. Turning listings off deletes them and their pictures." checked={enabled} disabled={busy} onChange={(on) => save.mutate({ enabled: on, tmdb: data.tmdb, email: data.email })} />
      {save.isError && <div className="px-5 pb-4"><Notice>{save.error.message}</Notice></div>}
      {enabled && <>
        <CheckRow label="TMDB pictures and descriptions" hint="An episode’s still or a movie’s backdrop, only when TMDB has the same programme. The selected programme shows it first, credited to TMDB. News and sport are never looked up." checked={tmdb} disabled={busy} onChange={(on) => save.mutate({ enabled: data.enabled, tmdb: on, email: data.email })} />
        <div className="space-y-3 border-t border-line px-5 py-4" aria-live="polite">
          <div className="flex flex-wrap items-center gap-3">
            <StatusPill tone={shown.tone}>{shown.label}</StatusPill>
            <div className="ml-auto flex flex-wrap items-center gap-3">
              <span className="text-sm text-neutral-400">{status.refreshesLeft} left today</span>
              <button type="button" className={`${secondaryButton} min-h-[44px]`} disabled={busy || shown.busy || status.refreshesLeft <= 0} onClick={() => refresh.mutate()}>{refresh.isPending || status.state === 'fetching' ? 'Updating…' : 'Refresh now'}</button>
            </div>
          </div>
          {status.message && (shown.tone === 'warn' ? <Notice>{status.message}</Notice> : <p className="text-sm text-neutral-400">{status.message}</p>)}
          {refresh.isError && <Notice>{refresh.error.message}</Notice>}
          {(status.state === 'firmware' || data.email) && <form className="space-y-2" onSubmit={(event) => {
            event.preventDefault()
            if (!busy) save.mutate({ enabled: true, tmdb: data.tmdb, email: typed.trim() }, { onSuccess: () => { setEmail(null); refresh.mutate() } })
          }}>
            <div className="flex flex-wrap items-end gap-3">
              <label htmlFor={emailId} className="min-w-0 flex-1 basis-64 text-sm text-neutral-300">HDHomeRun account email
                <input id={emailId} type="email" value={typed} maxLength={254} autoComplete="email" disabled={busy} onChange={(event) => setEmail(event.target.value)} className={`${textInput} mt-1 block min-h-[44px] w-full`} />
              </label>
              <button type="submit" disabled={busy} className={`${primaryButton} min-h-[44px]`}>{save.isPending ? 'Saving…' : 'Save and fetch'}</button>
            </div>
            <p className="text-sm text-neutral-400">Sent to SiliconDust with your tuners’ device IDs, instead of the code newer firmware gives. Updating the tuner in the HDHomeRun app avoids it. Leave it empty to stop sending it.</p>
          </form>}
          {status.lastSuccessAt && <ul className="flex flex-wrap gap-x-5 gap-y-1 text-sm text-neutral-400">
            <li>Updated <span className="text-neutral-200">{when(status.lastSuccessAt)}</span></li>
            {status.listedThrough && <li>Listed through <span className="text-neutral-200">{when(status.listedThrough)}</span></li>}
            <li><span className="text-neutral-200">{status.programmes.toLocaleString()}</span> programmes</li>
            {status.nextAt && <li>Next update <span className="text-neutral-200">about {when(status.nextAt)}</span></li>}
          </ul>}
        </div>
        <div className="border-t border-line px-5 pb-3 pt-4">
          <h4 className="text-sm font-semibold">Channel matches</h4>
          <p className="mt-1 text-sm text-neutral-400">{status.lastSuccessAt ? `${status.matched.toLocaleString()} of ${data.channels.length.toLocaleString()} channels have listings${status.flagged ? `; ${status.flagged} ${status.flagged === 1 ? 'name differs' : 'names differ'}` : ''}${status.missing ? `; ${status.missing} aren’t listed` : ''}.` : 'Channels are matched by number after the first update.'} Turn a channel’s listings off if they’re wrong; it still plays.</p>
        </div>
        {listing.isError && <div className="px-5 pb-3"><Notice>{listing.error.message}</Notice></div>}
        <ul className="border-t border-line">
          {data.channels.map((channel) => {
            const match = liveGuideMatch(channel.match)
            const checked = listing.isPending && listing.variables.id === channel.id ? listing.variables.listings : channel.listings && !match.locked
            return (
              <li key={channel.id} className="border-b border-line last:border-b-0">
                <label className="flex min-h-[56px] items-center gap-3 px-5 py-2.5 sm:gap-4">
                  <input type="checkbox" checked={checked} disabled={match.locked || listing.isPending} onChange={(event) => listing.mutate({ id: channel.id, listings: event.target.checked })} aria-label={`Listings for ${channel.number} ${channel.name}`} className="h-[18px] w-[18px] shrink-0 accent-accent" />
                  <span className="hidden h-6 w-8 shrink-0 sm:block">{channel.logoUrl && <img src={channel.logoUrl} alt="" loading="lazy" className="h-full w-full object-contain" />}</span>
                  <span className="w-12 shrink-0 break-all text-sm font-semibold tabular-nums sm:w-14">{channel.number}</span>
                  <span className="flex min-w-0 flex-1 flex-wrap items-center justify-between gap-x-4 gap-y-1">
                    <span className="min-w-0 break-words text-sm">{channel.name}{channel.feedName && channel.feedName !== channel.name && <span className="text-neutral-400"> · listed as {channel.feedName}</span>}</span>
                    <span className={`text-xs ${match.warn ? 'text-warn' : 'text-neutral-400'}`}>{match.label}</span>
                  </span>
                </label>
              </li>
            )
          })}
        </ul>
      </>}
    </Panel>
  )
}

function ChannelSettings({ channels }: { channels: LiveChannel[] }) {
  const [search, setSearch] = useState('')
  const save = useLiveMutation(({ id, enabled }: { id: string; enabled: boolean }) => enableLiveChannel(id, enabled))
  const filtered = channels.filter((channel) => `${channel.number} ${channel.name}`.toLocaleLowerCase().includes(search.trim().toLocaleLowerCase()))
  const selected = channels.filter((channel) => liveTVChannelSetting(channel).checked).length
  return (
    <Panel title="Channels" description={`${selected.toLocaleString()} of ${channels.length.toLocaleString()} channels selected. Hiding a channel stops anyone watching it.`}>
      <div className="border-t border-line p-5">
        <label className="block text-sm text-neutral-300">Find a channel<input type="search" value={search} onChange={(event) => setSearch(event.target.value)} placeholder="Channel number or name" className={`${textInput} mt-1 block min-h-[44px] w-full`} /></label>
      </div>
      {save.isError && <div className="px-5 pb-4"><Notice>{save.error.message}</Notice></div>}
      {channels.length === 0 ? <p className="border-t border-line p-5 text-sm text-neutral-400">No channels yet. Run a channel scan in the HDHomeRun app, then choose Refresh channels above.</p>
        : filtered.length === 0 ? <p role="status" className="border-t border-line p-5 text-sm text-neutral-400">No channels match “{search}”.</p>
          : <ul className="border-t border-line">
            {filtered.map((channel) => {
              const choice = liveTVChannelSetting(channel)
              return (
                <li key={channel.id} className="border-b border-line last:border-b-0">
                  <label className="flex min-h-[64px] items-center gap-3 px-5 py-3 sm:gap-4">
                    <input type="checkbox" checked={save.isPending && save.variables.id === channel.id ? save.variables.enabled : choice.checked} disabled={choice.locked || save.isPending} onChange={(event) => save.mutate({ id: channel.id, enabled: event.target.checked })} aria-label={`Show ${channel.number} ${channel.name}`} className="h-[18px] w-[18px] shrink-0 accent-accent" />
                    <span className="w-12 shrink-0 break-all text-sm font-semibold tabular-nums sm:w-16">{channel.number}</span>
                    <span className="flex min-w-0 flex-1 flex-wrap items-center justify-between gap-x-4 gap-y-1">
                      <span className={`min-w-0 break-words text-sm ${choice.locked ? 'text-neutral-400' : ''}`}>{channel.name}</span>
                      <span className={`text-xs ${!channel.available && !choice.locked ? 'text-warn' : 'text-neutral-400'}`}>{choice.status}</span>
                    </span>
                  </label>
                </li>
              )
            })}
          </ul>}
      <p className="border-t border-line px-5 py-3 text-sm text-neutral-400" role="status">{save.isPending ? 'Saving channel choice…' : 'Channel choices save immediately.'}</p>
    </Panel>
  )
}

function RemoveTunerConfirmation({ source, busy, error, onCancel, onConfirm }: {
  source: LiveSource; busy: boolean; error?: string; onCancel: () => void; onConfirm: () => void
}) {
  const ref = useRef<HTMLDialogElement>(null)
  const title = useId()
  const description = useId()
  useEffect(() => { ref.current?.showModal() }, [])
  return (
    <dialog ref={ref} aria-labelledby={title} aria-describedby={description} onCancel={(event) => { event.preventDefault(); if (!busy) onCancel() }} className="fixed inset-0 m-auto max-h-[90dvh] w-[calc(100%-2rem)] max-w-lg overflow-y-auto rounded-xl border border-line-2 bg-surface p-6 text-neutral-100 shadow-xl backdrop:bg-black/80">
      <h2 id={title} className="break-words text-xl font-semibold">Remove {source.name}?</h2>
      <p id={description} className="mt-3 text-sm text-neutral-300">This stops its active viewers and removes its saved channels from WatchMyMedia. Nothing changes on the tuner. You can add it again later.</p>
      {error && <div className="mt-4"><Notice>{error}</Notice></div>}
      <div className="mt-6 flex flex-wrap justify-end gap-2">
        <button type="button" autoFocus disabled={busy} onClick={onCancel} className={`${secondaryButton} min-h-[44px]`}>Keep tuner</button>
        <button type="button" disabled={busy} onClick={onConfirm} className={`${primaryButton} min-h-[44px]`}>{busy ? 'Removing…' : 'Remove tuner'}</button>
      </div>
    </dialog>
  )
}

function DeviceDetails({ device }: { device: LiveSource['device'] }) {
  return <span className="block break-words text-sm text-neutral-400">{device.model} · {device.tuners} tuner{device.tuners === 1 ? '' : 's'} · firmware {device.firmware}</span>
}

function Notice({ children }: { children: ReactNode }) {
  return <p role="alert" className="rounded-lg border border-orange-900 bg-orange-950/25 px-4 py-3 text-sm text-warn">{children}</p>
}
