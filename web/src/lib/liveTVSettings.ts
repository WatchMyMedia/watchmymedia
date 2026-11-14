// The rules behind Settings › Live TV (PLAN-ADMIN-SETTINGS.md §5.4): which
// step each task opens, the hub's setup guide, the numbers the screens show,
// and the words for every refusal.
import { ApiError } from '../api/client'
import { resolveStep, guideProgress, type FlowStep, type GuideTask } from './flowSteps'
import { joinNames } from './liveRecordings'
import { liveTVChannelSetting, liveGuideMatch } from './liveTVSettingsRules'

export type LiveAccess = 'none' | 'admins' | 'everyone'
export type Wording = { title: string; body: string }

type SourceLike = { id: string; name: string; address: string; enabled: boolean; lastError?: string; device: { id: string; model: string; tuners: number } }
type ChannelLike = { id: string; number: string; name: string; sourceId: string; available: boolean; enabled: boolean; reason?: string }
type SessionLike = { userName: string; state: string; channel: { number: string; name: string; sourceId: string } }
type RecordingLike = { state: string; sourceId: string }

export const GUIDE_REFRESHES_A_DAY = 4

// ---- Who can watch -------------------------------------------------------

export const ACCESS_LABEL: Record<LiveAccess, string> = { none: 'No one', admins: 'Admins', everyone: 'Everyone' }

/** "admins can watch", for a one-line state. */
export function accessPhrase(access: LiveAccess) {
  return access === 'none' ? 'no one can watch' : `${access} can watch`
}

/** What changed for people, said on a finished screen. */
export function accessOutcome(access: LiveAccess) {
  switch (access) {
    case 'none': return 'Live TV stays hidden for now.'
    case 'admins': return 'Live TV is in the top bar for admins.'
    case 'everyone': return 'Live TV is in the top bar for every account.'
  }
}

/** People watching Live TV now: sessions that hold a tuner, on one tuner or all. */
export function liveViewers(sessions: readonly SessionLike[], sourceId?: string) {
  const active = sessions.filter((s) => (s.state === 'starting' || s.state === 'playing') && (!sourceId || s.channel.sourceId === sourceId))
  const names = [...new Set(active.map((s) => s.userName))]
  return { sessions: active, names, count: names.length }
}

/** The Save button of Who can watch: the cost is on the button first (§3.6). */
export function accessSaveLabel(viewers: number, changed: boolean) {
  if (!changed || viewers === 0) return 'Save'
  return `Save and stop ${viewers} ${viewers === 1 ? 'viewer' : 'viewers'}`
}

export function accessWarning(names: readonly string[]): Wording | null {
  if (!names.length) return null
  return {
    title: `${names.length === 1 ? '1 person is' : `${names.length} people are`} watching Live TV`,
    body: `Changing this stops ${joinNames([...names])}. They can start again straight away if they’re still allowed. Recordings in progress carry on.`,
  }
}

// ---- Tuners --------------------------------------------------------------

/** How many of a tuner's tuners are busy, and with whom. */
export function tunerUse(source: Pick<SourceLike, 'id'>, sessions: readonly SessionLike[], recordings: readonly RecordingLike[] = []) {
  const viewers = liveViewers(sessions, source.id)
  const recording = recordings.filter((r) => r.state === 'recording' && r.sourceId === source.id).length
  return { inUse: viewers.sessions.length + recording, viewers: viewers.names, recording }
}

export function tunerPill(source: Pick<SourceLike, 'enabled' | 'lastError' | 'device'>, inUse: number): { tone: 'ok' | 'warn' | 'off'; label: string } {
  if (!source.enabled) return { tone: 'off', label: 'Off' }
  if (source.lastError) return { tone: 'warn', label: 'Not answering' }
  return { tone: 'ok', label: inUse > 0 ? `On · ${inUse} of ${source.device.tuners} in use` : 'On' }
}

/** "4 tuners, 1 in use by Alex", the tuner page's line. */
export function tunerUseLine(tuners: number, inUse: number, viewers: readonly string[]) {
  const total = `${tuners} ${tuners === 1 ? 'tuner' : 'tuners'}`
  if (!inUse) return total
  return `${total}, ${inUse} in use${viewers.length ? ` by ${joinNames([...viewers])}` : ''}`
}

/** Channels the server can show and how many are shown; copy-protected ones never can. */
export function channelCounts(channels: readonly ChannelLike[], guide?: readonly { id: string; match: string }[]) {
  const watchable = channels.filter((c) => !liveTVChannelSetting(c).locked)
  const shown = watchable.filter((c) => c.enabled)
  const matches = new Map(guide?.map((g) => [g.id, g.match]))
  const withoutListings = guide ? watchable.filter((c) => matches.get(c.id) === 'missing').length : 0
  return { total: channels.length, watchable: watchable.length, shown: shown.length, protected: channels.length - watchable.length, withoutListings }
}

export function sourceChannels<T extends Pick<ChannelLike, 'sourceId'>>(channels: readonly T[], sourceId: string) {
  return channels.filter((c) => c.sourceId === sourceId)
}

/** Refresh channels' result beside its button: "42 channels, 1 new (12.3 Harbor Kids)". */
export function refreshSummary(before: readonly Pick<ChannelLike, 'id' | 'reason'>[], after: readonly Pick<ChannelLike, 'id' | 'number' | 'name' | 'reason'>[]) {
  const was = new Map(before.map((c) => [c.id, c.reason]))
  const listed = after.filter((c) => c.reason !== 'unavailable')
  const added = after.filter((c) => !was.has(c.id))
  const gone = after.filter((c) => c.reason === 'unavailable' && was.has(c.id) && was.get(c.id) !== 'unavailable').length
  const parts = [`${listed.length} ${listed.length === 1 ? 'channel' : 'channels'}`]
  if (added.length) parts.push(`${added.length} new (${added.slice(0, 3).map((c) => `${c.number} ${c.name}`).join(', ')}${added.length > 3 ? ', …' : ''})`)
  if (gone) parts.push(`${gone} missing from the tuner’s list`)
  if (!added.length && !gone) parts.push('no changes')
  return parts.join(', ')
}

/** The device ID another tuner of this server already has, if the inspected one is a repeat. */
export function existingTuner<T extends Pick<SourceLike, 'device'>>(sources: readonly T[], deviceId: string | undefined) {
  return deviceId ? sources.find((s) => s.device.id === deviceId) : undefined
}

// ---- Add a tuner ---------------------------------------------------------

export type AddTunerStep = 'address' | 'found' | 'access' | 'done'

/** Address and What was found ask for the password; Who can watch is asked with the first tuner only. */
export function addTunerSteps(askAccess: boolean): FlowStep[] {
  return [
    { key: 'address', label: 'Address', guarded: true },
    { key: 'found', label: 'What was found', guarded: true },
    ...(askAccess ? [{ key: 'access', label: 'Who can watch' }] : []),
    { key: 'done', label: 'Added' },
  ]
}

/** Who can watch is the first tuner's step: none was saved before, or the one just added is the only one. */
export function asksAccess(sources: readonly Pick<SourceLike, 'id'>[], addedId?: string) {
  return addedId ? sources.length === 1 && sources[0].id === addedId : sources.length === 0
}

/**
 * The step an Add a tuner address opens. Once the tuner is saved there is no
 * going back to Address or What was found (§3.2); before it, What was found
 * needs a tested address.
 */
export function addTunerStep({ requested, tested, added, askAccess }: { requested?: string; tested: boolean; added: boolean; askAccess: boolean }): AddTunerStep {
  if (added) {
    if (requested === 'done') return 'done'
    return askAccess ? 'access' : 'done'
  }
  return requested === 'found' && tested ? 'found' : 'address'
}

const has = (error: unknown, text: string) => error instanceof ApiError && error.message.toLowerCase().includes(text)
const offline = (error: unknown) => error instanceof TypeError || (error instanceof ApiError && error.status === 503) || has(error, 'did not respond')

/** Address step: why the test found no tuner. */
export function tunerTestError(error: unknown, address: string): Wording {
  if (error instanceof TypeError) return { title: 'The server didn’t answer', body: 'Nothing was tested. Check this browser’s connection to the server and try again.' }
  if (has(error, 'lan hostname or ip address')) return { title: 'That isn’t a tuner address', body: 'Enter its IP address or hostname, such as 192.0.2.47 or hdhomerun.local, without http:// or a path.' }
  if (has(error, 'supported http hdhomerun')) return { title: 'That address isn’t an HDHomeRun', body: 'Something answered, but not a tuner. Check the address.' }
  if (has(error, 'invalid channel lineup')) return { title: 'The tuner’s channel list couldn’t be read', body: 'It answered, but its channel list didn’t make sense. Run a channel scan in the HDHomeRun app, then test again.' }
  if (offline(error)) return { title: `Nothing answered at ${address}`, body: 'Check the address, that the tuner is on, and that it’s on the same network as this server. In Docker, the container needs to reach your home network.' }
  return { title: 'Couldn’t test this address', body: fallback(error, 'Nothing was saved. Try again.') }
}

/** The tested device is a tuner this server already has. */
export function tunerRepeat(existing?: { name: string }): Wording & { existing: true } {
  return { title: 'This HDHomeRun is already added', body: existing ? `It’s here as “${existing.name}”. To change its address, open that tuner’s page.` : 'To change its address, open that tuner’s page.', existing: true }
}

/** What was found step: why Save tuner was refused. */
export function tunerAddError(error: unknown, address: string, existing?: { name: string }): Wording & { existing?: boolean } {
  if (error instanceof ApiError && error.status === 409) return tunerRepeat(existing)
  const test = tunerTestError(error, address)
  return test.title.startsWith('Couldn’t test') ? { title: 'Couldn’t save the tuner', body: fallback(error, 'Nothing was saved. Try again.') } : { ...test, body: `${test.body} The tuner wasn’t saved.` }
}

/** Name and address: why the change was refused. `other` is the device found at the new address, when known. */
export function tunerMoveError(error: unknown, address: string, source: { name: string; deviceId: string }, other?: string): Wording & { addAsAnother?: boolean } {
  if (has(error, 'different tuner') || (error instanceof ApiError && error.status === 409)) {
    return {
      title: `${address} is a different HDHomeRun`,
      body: `It answered as ${other ? `device ${other}` : 'another device'}, not ${source.name} (${source.deviceId}). If that’s a second tuner, add it as another tuner.`,
      addAsAnother: true,
    }
  }
  if (has(error, 'lan hostname or ip address')) return { title: 'That isn’t a tuner address', body: 'Enter its IP address or hostname, without http:// or a path. The address wasn’t changed.' }
  if (has(error, 'supported http hdhomerun')) return { title: `${address} isn’t an HDHomeRun`, body: 'Something answered, but not a tuner. The address wasn’t changed.' }
  if (offline(error)) return { title: `Nothing answered at ${address}`, body: 'The address wasn’t changed. Check it and save again.' }
  return { title: 'Couldn’t save the change', body: fallback(error, 'Nothing was changed. Try again.') }
}

/** Refresh channels failed: the last list is kept, and the address may have moved. */
export function tunerRefreshError(source: Pick<SourceLike, 'name' | 'address'>, error?: unknown): Wording {
  if (error && has(error, 'invalid channel lineup')) return { title: 'Couldn’t refresh channels', body: `${source.name} answered, but its channel list didn’t make sense. The last channel list is kept. Run a channel scan in the HDHomeRun app, then refresh again.` }
  if (error && !offline(error) && error instanceof ApiError && error.status !== 422) return { title: 'Couldn’t refresh channels', body: `${fallback(error, 'Try again.')} The last channel list is kept.` }
  return { title: 'Couldn’t refresh channels', body: `${source.name} didn’t answer at ${source.address}. The last channel list is kept. If the tuner’s address changed, change it here.` }
}

/** The tuner's switch, in its row once saved. */
export function tunerSwitchNote(enabled: boolean) {
  return enabled ? 'Its channels can be watched again.' : 'Its channels are off, and anyone watching on it was stopped.'
}

/** Remove a tuner: what stops, what is deleted, what stays, with the server's numbers (§3.8). */
export function tunerRemoval(source: { name: string }, channels: readonly Pick<ChannelLike, 'number'>[], viewers: readonly SessionLike[], removal?: { scheduledRecordings: number; recordingNow: number }) {
  const stops: string[] = []
  const byPerson = new Map<string, string[]>()
  for (const v of viewers) byPerson.set(v.userName, [...(byPerson.get(v.userName) ?? []), v.channel.number])
  for (const [name, numbers] of byPerson) stops.push(`${name}, who is watching ${joinNames(numbers)}.`)
  if (removal?.recordingNow) stops.push(`${removal.recordingNow} ${removal.recordingNow === 1 ? 'recording' : 'recordings'} in progress.`)
  const deleted = [`Its ${channels.length} ${channels.length === 1 ? 'channel' : 'channels'}, their listings, and which channels you chose to show.`]
  const atRisk = removal?.scheduledRecordings
    ? `${removal.scheduledRecordings} scheduled ${removal.scheduledRecordings === 1 ? 'recording is' : 'recordings are'} on its channels. They may not record: only another tuner with the same channel number can record them.`
    : undefined
  return { stops, deleted, atRisk, stays: ['Finished recordings in your libraries.', `${source.name} itself: nothing changes on the device, and you can add it again later.`] }
}

// ---- Guide listings ------------------------------------------------------

export const GUIDE_STEPS: FlowStep[] = [
  { key: 'turn-on', label: 'Turn on' },
  { key: 'pictures', label: 'Pictures' },
  { key: 'on', label: 'On' },
]

export function guideSetupStep(requested: string | undefined, enabled: boolean, finished: boolean) {
  return resolveStep(GUIDE_STEPS, requested, (key) => (key === 'turn-on' ? enabled : key === 'pictures' ? finished : false)).key
}

/** The firmware asks for an HDHomeRun account: show the email field. */
export function guideAsksEmail(state: string, email: string) {
  return state === 'firmware' || state === 'refused' || email !== ''
}

export type GuideNotice = { tone: 'info' | 'err' | 'warn'; title: string; body: string; spin?: boolean; email?: boolean; refresh?: boolean }

/** The listings' state as one message, or none when they're fine. */
export function guideNotice(status: { state: string; message?: string; refreshesLeft: number; lastAttemptAt?: string }, ago: (iso?: string) => string): GuideNotice | null {
  switch (status.state) {
    case 'ok': case 'off': return null
    case 'fetching': return { tone: 'info', spin: true, title: 'Fetching listings', body: `Started ${status.lastAttemptAt ? ago(status.lastAttemptAt) : 'just now'}. The guide updates when it’s done.` }
    case 'waiting': return { tone: 'info', title: 'Waiting for the first fetch', body: 'It starts within 10 minutes of turning listings on. The guide fills in when it ends.' }
    case 'firmware': case 'refused': return { tone: 'err', email: true, title: 'HDHomeRun refused the listings request', body: status.state === 'firmware' ? 'The tuner’s firmware asks for an account. Enter the HDHomeRun account email.' : `${status.message ? `${trimDot(status.message)}. ` : ''}Check the HDHomeRun account email, or update the tuner in the HDHomeRun app.` }
    case 'no_tuner': return { tone: 'warn', title: 'No tuner to get listings for', body: 'Listings follow your tuners. Add one, or turn one back on.' }
    default: return {
      tone: 'err', refresh: status.refreshesLeft > 0, title: 'Listings are out of date',
      body: `The last fetch failed${status.lastAttemptAt ? ` ${ago(status.lastAttemptAt)}` : ''}${status.message ? `: ${trimDot(lowerFirst(status.message))}` : ''}. The guide shows what it has. It tries again tonight${status.refreshesLeft > 0 ? `, or you can refresh now (${status.refreshesLeft} left today)` : ''}.`,
    }
  }
}

export function guideRefreshError(error: unknown): Wording {
  if (error instanceof ApiError && error.status === 429) return { title: 'No refreshes left today', body: `You’ve used all ${GUIDE_REFRESHES_A_DAY}. Listings refresh by themselves tonight.` }
  return { title: 'Couldn’t refresh listings', body: fallback(error, 'The guide keeps what it has. Try again.') }
}

/** "36 matched · 3 without listings". */
export function guideMatchLine(channels: readonly { match: string }[]) {
  const matched = channels.filter((c) => !liveGuideMatch(c.match).locked).length
  const missing = channels.filter((c) => c.match === 'missing').length
  if (channels.every((c) => c.match === 'pending')) return 'Matched after the first fetch'
  return `${matched} matched${missing ? ` · ${missing} without listings` : ''}`
}

/** A channel's listings column: Matched, No listings (it still plays), Names differ. */
export function listingsPill(match: string | undefined): { tone: 'ok' | 'warn' | 'off'; label: string; note?: string } {
  switch (match) {
    case 'name': case 'number': return { tone: 'ok', label: 'Matched' }
    case 'differs': return { tone: 'warn', label: 'Names differ', note: 'check before trusting them' }
    case 'missing': return { tone: 'warn', label: 'No listings', note: 'still plays' }
    case 'pending': return { tone: 'off', label: 'After the first fetch' }
    default: return { tone: 'off', label: '—' }
  }
}

// ---- Channels ------------------------------------------------------------

/** Why a channel's switch is locked or what is wrong with it, said in its row. */
export function channelRowNote(channel: Pick<ChannelLike, 'reason' | 'available'>) {
  switch (channel.reason) {
    case 'protected_channel': return 'Copy-protected by the broadcaster, so it can’t be shown.'
    case 'source_disabled': return 'Its tuner is off.'
    case 'unavailable': return 'Missing from the tuner’s last channel list.'
    default: return channel.reason || !channel.available ? 'Unavailable right now.' : undefined
  }
}

export function channelSaveError(channel: Pick<ChannelLike, 'number' | 'name'>, show: boolean, error: unknown): Wording {
  const still = show ? 'it’s still hidden' : 'it’s still shown'
  const why = error instanceof TypeError ? `The server didn’t answer, so ${still}.` : `${fallback(error, 'The server refused the change.')} ${capital(still)}.`
  return { title: `Couldn’t ${show ? 'show' : 'hide'} ${channel.number} ${channel.name}`, body: `${why} Try the switch again.` }
}

// ---- Recordings ----------------------------------------------------------

export const RECORDING_STEPS: FlowStep[] = [
  { key: 'who', label: 'Who can record' },
  { key: 'folders', label: 'Folders' },
  { key: 'timing', label: 'Timing and space' },
  { key: 'review', label: 'Review' },
  { key: 'on', label: 'On' },
]

/** Nothing saves until Review; the steps before it are done once continued, or when recordings are already on. */
export function recordingSetupStep(requested: string | undefined, reached: readonly string[], saved: boolean, enabled: boolean) {
  return resolveStep(RECORDING_STEPS, requested, (key) => (key === 'review' ? saved : key === 'on' ? false : enabled || reached.includes(key))).key
}

export type RecordingNeed = { key: 'tuner' | 'access' | 'library'; met: boolean; title: string; detail: string; action: string }

/** What recordings need before they can be set up, each with the way to get it. */
export function recordingNeeds({ sources, access, tvLibraries, movieLibraries }: { sources: readonly Pick<SourceLike, 'name' | 'enabled'>[]; access: LiveAccess; tvLibraries: readonly { name: string }[]; movieLibraries: number }): RecordingNeed[] {
  const on = sources.filter((s) => s.enabled)
  return [
    { key: 'tuner', met: on.length > 0, title: 'A tuner that’s on', detail: on.length ? joinNames(on.map((s) => s.name)) : sources.length ? 'Every tuner is off.' : 'None yet.', action: sources.length ? 'Turn a tuner on' : 'Add a tuner' },
    { key: 'access', met: access !== 'none', title: 'Someone can watch Live TV', detail: access === 'none' ? 'No one can watch right now.' : ACCESS_LABEL[access], action: 'Choose who can watch' },
    { key: 'library', met: tvLibraries.length > 0, title: 'A TV Shows library', detail: tvLibraries.length ? joinNames(tvLibraries.map((l) => l.name)) : movieLibraries ? 'None yet. You have Movies only.' : 'None yet.', action: 'Add a TV Shows library' },
  ]
}

/** The first need that isn't met, as the page's warning. */
export function recordingNeedWarning(needs: readonly RecordingNeed[], movieLibraries: number): Wording | null {
  const need = needs.find((n) => !n.met)
  if (!need) return null
  switch (need.key) {
    case 'tuner': return { title: 'Recordings need a tuner that’s on', body: 'Recordings are made on your HDHomeRun.' }
    case 'access': return { title: 'Recordings need someone who can watch Live TV', body: 'Recording follows Live TV access, and right now no one has it.' }
    case 'library': return { title: 'Recordings need a TV Shows library', body: `Recorded episodes are saved into one.${movieLibraries ? ' You have Movies only.' : ''}` }
  }
}

export const WARNING_SECONDS = [0, 30, 60, 120, 300, 600]

/** "2 minutes", "30 seconds", "No warning". */
export function secondsWords(seconds: number) {
  if (seconds === 0) return 'No warning'
  if (seconds < 60 || seconds % 60) return `${seconds} seconds`
  const m = seconds / 60
  return `${m} ${m === 1 ? 'minute' : 'minutes'}`
}

export function minutesWords(minutes: number) {
  return `${minutes} ${minutes === 1 ? 'minute' : 'minutes'}`
}

/** Keep free, typed in GB: a whole number from 0 up. */
export function keepFreeError(typed: string): string | undefined {
  const t = typed.trim()
  if (t === '' || !/^\d+(\.\d+)?$/.test(t)) return 'Enter a number of gigabytes, 0 or more.'
  if (Number(t) > 100_000) return 'Enter 100,000 GB or less.'
}

/** Review's one line for Timing and space. */
export function timingSummary(s: { padBefore: number; padAfter: number; minFreeBytes: number; tunersReserved: number; warningSeconds: number; findAds: boolean }) {
  return [
    `Start ${s.padBefore} min early, end ${s.padAfter} min late`,
    `keep ${Math.round(s.minFreeBytes / 1e9).toLocaleString()} GB free`,
    s.tunersReserved ? `${s.tunersReserved} ${s.tunersReserved === 1 ? 'tuner' : 'tuners'} kept back` : 'no tuners kept back',
    s.warningSeconds ? `${secondsWords(s.warningSeconds).replace(' minutes', ' min').replace(' minute', ' min').replace(' seconds', ' s')} warning` : 'no warning',
    s.findAds ? 'ad breaks found' : 'ad breaks off',
  ].join(' · ')
}

export function recordAccessLabel(access: LiveAccess) {
  return access === 'everyone' ? 'Everyone who can watch Live TV' : access === 'admins' ? 'Admins' : 'No one'
}

/** Review's refusal: the server checks the folders and the numbers. */
export function recordingSaveError(error: unknown, tvPath: string): Wording {
  if (has(error, 'folder unavailable')) return { title: `The server can’t write to ${tvPath}`, body: 'Give the account the server runs as write access to that folder (in Docker, PUID and PGID), then save again. Nothing was saved.' }
  if (error instanceof ApiError && error.status === 422) return { title: 'These settings were refused', body: 'A folder isn’t a library’s folder any more, or a number is out of range. Check each step and save again. Nothing was saved.' }
  if (error instanceof ApiError && error.status === 409) return { title: 'Recordings aren’t available', body: 'The server can’t record right now. Nothing was saved.' }
  return { title: 'Couldn’t save recordings', body: fallback(error, 'Nothing was saved. Try again.') }
}

// ---- The hub's setup guide -----------------------------------------------

export type LiveGuideKey = 'tuner' | 'access' | 'guide' | 'recordings'

/**
 * The hub's setup guide (§5.4): Add a tuner, Who can watch, then the optional
 * Guide listings and Recordings, each waiting for a tuner. Who can watch is
 * asked with the first tuner and starts as Admins, so it's done once one is.
 * The guide shows until every task is done or put away with Not now.
 */
export function liveSetupGuide({ tuners, guide, recordings, dismissed }: { tuners: number; guide?: { enabled: boolean }; recordings?: { enabled: boolean }; dismissed: readonly string[] }) {
  const waitsFor = tuners ? undefined : 'After a tuner'
  const tasks: (GuideTask & { key: LiveGuideKey })[] = [
    { key: 'tuner', done: tuners > 0 },
    { key: 'access', done: tuners > 0, waitsFor },
  ]
  if (guide) tasks.push({ key: 'guide', done: guide.enabled, optional: true, dismissed: !guide.enabled && dismissed.includes('guide'), waitsFor })
  if (recordings) tasks.push({ key: 'recordings', done: recordings.enabled, optional: true, dismissed: !recordings.enabled && dismissed.includes('recordings'), waitsFor })
  const progress = guideProgress(tasks)
  const shown = tasks.filter((t) => !t.dismissed)
  return {
    items: shown.map((t) => ({ key: t.key, optional: t.optional, waits: t.waitsFor, state: (t.done ? 'done' : t.key === progress.now ? 'now' : 'todo') as 'done' | 'now' | 'todo' })),
    done: progress.done,
    total: progress.total,
    show: shown.some((t) => !t.done),
  }
}

const DISMISSED = 'wmm.liveTVGuideDismissed'

export function readDismissed(): string[] {
  try {
    const v = JSON.parse(localStorage.getItem(DISMISSED) ?? '[]') as unknown
    return Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string') : []
  } catch { return [] }
}

export function writeDismissed(keys: readonly string[]) {
  try { localStorage.setItem(DISMISSED, JSON.stringify(keys)) } catch { /* Blocked storage: Not now lasts until reload. */ }
}

// ---- Words -----------------------------------------------------------------

function fallback(error: unknown, otherwise: string) {
  if (error instanceof ApiError && error.message && !error.message.startsWith('Request failed with status')) return `${capital(trimDot(error.message))}.`
  return otherwise
}
function trimDot(s: string) { return s.trim().replace(/[.!]+$/, '') }
function capital(s: string) { return s ? s[0].toUpperCase() + s.slice(1) : s }
// "HDHomeRun…" keeps its capitals; "The tuner…" doesn't.
function lowerFirst(s: string) { return s && !/^.[A-Z]/.test(s) ? s[0].toLowerCase() + s.slice(1) : s }
