import { describe, expect, it } from 'vitest'
import { ApiError } from '../api/client'
import {
  accessSaveLabel, accessWarning, addTunerStep, addTunerSteps, asksAccess, channelCounts, channelRowNote, channelSaveError, guideMatchLine, guideNotice,
  guideRefreshError, guideSetupStep, keepFreeError, listingsPill, liveSetupGuide, liveViewers, recordingNeedWarning, recordingNeeds, recordingSaveError,
  recordingSetupStep, refreshSummary, secondsWords, timingSummary, tunerAddError, tunerMoveError, tunerPill, tunerRefreshError, tunerRemoval, tunerTestError,
  tunerUse, tunerUseLine,
} from './liveTVSettings'

const fail = (status: number, detail?: string) => new ApiError(new Response(null, { status }), detail === undefined ? undefined : { detail, type: 'about:blank' })

const tuner = { id: 's1', name: 'Attic antenna', address: '192.0.2.47', enabled: true, lastError: '', device: { id: '10AB23CD', model: 'HDHomeRun FLEX 4K', tuners: 4 } }
const session = (userName: string, number: string, sourceId = 's1', state = 'playing') => ({ userName, state, channel: { number, name: `Channel ${number}`, sourceId } })

describe('Add a tuner', () => {
  it('asks who can watch with the first tuner only', () => {
    expect(addTunerSteps(true).map((s) => s.key)).toEqual(['address', 'found', 'access', 'done'])
    expect(addTunerSteps(false).map((s) => s.key)).toEqual(['address', 'found', 'done'])
    expect(addTunerSteps(true).filter((s) => s.guarded).map((s) => s.key)).toEqual(['address', 'found'])
    expect(asksAccess([])).toBe(true)
    expect(asksAccess([{ id: 'a' }])).toBe(false)
    expect(asksAccess([{ id: 'a' }], 'a')).toBe(true)
    expect(asksAccess([{ id: 'a' }, { id: 'b' }], 'b')).toBe(false)
  })

  it('opens What was found only after a test, and never goes back once saved', () => {
    expect(addTunerStep({ requested: undefined, tested: false, added: false, askAccess: true })).toBe('address')
    expect(addTunerStep({ requested: 'found', tested: false, added: false, askAccess: true })).toBe('address')
    expect(addTunerStep({ requested: 'found', tested: true, added: false, askAccess: true })).toBe('found')
    expect(addTunerStep({ requested: 'access', tested: false, added: false, askAccess: true })).toBe('address')
    expect(addTunerStep({ requested: 'found', tested: true, added: true, askAccess: true })).toBe('access')
    expect(addTunerStep({ requested: 'access', tested: false, added: true, askAccess: false })).toBe('done')
    expect(addTunerStep({ requested: 'done', tested: false, added: true, askAccess: true })).toBe('done')
  })

  it('words each refusal of the test', () => {
    expect(tunerTestError(fail(503, 'the tuner did not respond'), '192.0.2.47').title).toBe('Nothing answered at 192.0.2.47')
    expect(tunerTestError(fail(422, 'the address did not return a supported HTTP HDHomeRun device'), 'x').title).toBe('That address isn’t an HDHomeRun')
    expect(tunerTestError(fail(422, 'enter an HDHomeRun LAN hostname or IP address, without a path or credentials'), 'x').title).toBe('That isn’t a tuner address')
    expect(tunerTestError(fail(422, 'the tuner returned an invalid channel lineup; the previous lineup was kept'), 'x').title).toBe('The tuner’s channel list couldn’t be read')
    expect(tunerTestError(new TypeError('Failed to fetch'), 'x').title).toBe('The server didn’t answer')
    expect(tunerTestError(fail(500), 'x')).toEqual({ title: 'Couldn’t test this address', body: 'Nothing was saved. Try again.' })
  })

  it('names the tuner that is already added', () => {
    const words = tunerAddError(fail(409, 'this tuner is already configured'), 'x', { name: 'Attic antenna' })
    expect(words).toMatchObject({ title: 'This HDHomeRun is already added', existing: true })
    expect(words.body).toContain('“Attic antenna”')
    expect(tunerAddError(fail(503), '192.0.2.47').body).toContain('The tuner wasn’t saved.')
    expect(tunerAddError(fail(500, 'disk full'), 'x')).toEqual({ title: 'Couldn’t save the tuner', body: 'Disk full.' })
  })
})

describe('One tuner', () => {
  it('counts tuners in use and who is watching', () => {
    const sessions = [session('Alex', '4.1'), session('Robin', '9.2', 's2'), session('Sam', '4.3', 's1', 'stopped')]
    const use = tunerUse(tuner, sessions, [{ state: 'recording', sourceId: 's1' }, { state: 'done', sourceId: 's1' }])
    expect(use).toEqual({ inUse: 2, viewers: ['Alex'], recording: 1 })
    expect(tunerPill(tuner, 1)).toEqual({ tone: 'ok', label: 'On · 1 of 4 in use' })
    expect(tunerPill({ ...tuner, enabled: false }, 0).label).toBe('Off')
    expect(tunerPill({ ...tuner, lastError: 'offline' }, 0).label).toBe('Not answering')
    expect(tunerUseLine(4, 1, ['Alex'])).toBe('4 tuners, 1 in use by Alex')
    expect(tunerUseLine(1, 0, [])).toBe('1 tuner')
  })

  it('says what Refresh channels found', () => {
    const before = [{ id: 'a', reason: undefined }, { id: 'b', reason: undefined }]
    const after = [{ id: 'a', number: '4.1', name: 'KHRB' }, { id: 'b', number: '4.2', name: 'Two', reason: 'unavailable' }, { id: 'c', number: '12.3', name: 'Harbor Kids' }]
    expect(refreshSummary(before, after)).toBe('2 channels, 1 new (12.3 Harbor Kids), 1 missing from the tuner’s list')
    expect(refreshSummary(before, [{ id: 'a', number: '4.1', name: 'A' }, { id: 'b', number: '4.2', name: 'B' }])).toBe('2 channels, no changes')
  })

  it('words a failed refresh and a move to another device', () => {
    expect(tunerRefreshError(tuner).body).toBe('Attic antenna didn’t answer at 192.0.2.47. The last channel list is kept. If the tuner’s address changed, change it here.')
    expect(tunerRefreshError(tuner, fail(422, 'the tuner returned an invalid channel lineup')).body).toContain('didn’t make sense')
    const moved = tunerMoveError(fail(409, 'a different tuner is at this address; check its device ID'), '192.0.2.52', { name: 'Attic antenna', deviceId: '10AB23CD' }, '77EE01AA')
    expect(moved).toMatchObject({ title: '192.0.2.52 is a different HDHomeRun', addAsAnother: true })
    expect(moved.body).toContain('device 77EE01AA, not Attic antenna (10AB23CD)')
    expect(tunerMoveError(fail(503, 'the tuner did not respond'), '192.0.2.52', { name: 'A', deviceId: 'B' })).toEqual({ title: 'Nothing answered at 192.0.2.52', body: 'The address wasn’t changed. Check it and save again.' })
  })

  it('puts the removal numbers in the dialog, with scheduled recordings at risk rather than lost', () => {
    const words = tunerRemoval(tuner, [{ number: '4.1' }, { number: '4.2' }], [session('Alex', '4.1')], { scheduledRecordings: 6, recordingNow: 1 })
    expect(words.stops).toEqual(['Alex, who is watching 4.1.', '1 recording in progress.'])
    expect(words.deleted[0]).toBe('Its 2 channels, their listings, and which channels you chose to show.')
    expect(words.atRisk).toContain('6 scheduled recordings are on its channels. They may not record')
    expect(tunerRemoval(tuner, [], [], { scheduledRecordings: 0, recordingNow: 0 })).toMatchObject({ stops: [], atRisk: undefined })
  })
})

describe('Who can watch', () => {
  it('puts the viewers who would stop on the button and the screen', () => {
    const viewers = liveViewers([session('Alex', '4.1'), session('Alex', '9.2', 's2'), session('Robin', '4.3'), session('Sam', '4.4', 's1', 'stopping')])
    expect(viewers.names).toEqual(['Alex', 'Robin'])
    expect(accessSaveLabel(viewers.count, true)).toBe('Save and stop 2 viewers')
    expect(accessSaveLabel(1, true)).toBe('Save and stop 1 viewer')
    expect(accessSaveLabel(2, false)).toBe('Save')
    expect(accessSaveLabel(0, true)).toBe('Save')
    expect(accessWarning(viewers.names)).toEqual({
      title: '2 people are watching Live TV',
      body: 'Changing this stops Alex and Robin. They can start again straight away if they’re still allowed. Recordings in progress carry on.',
    })
    expect(accessWarning([])).toBeNull()
  })
})

describe('Channels', () => {
  const channels = [
    { id: 'a', number: '4.1', name: 'KHRB', sourceId: 's1', available: true, enabled: true },
    { id: 'b', number: '4.3', name: 'Classics', sourceId: 's1', available: true, enabled: true },
    { id: 'c', number: '9.2', name: 'Shop', sourceId: 's1', available: true, enabled: false },
    { id: 'd', number: '12.4', name: 'Premiere', sourceId: 's1', available: false, enabled: true, reason: 'protected_channel' },
  ]
  it('counts what can be shown, leaving out copy-protected channels', () => {
    expect(channelCounts(channels, [{ id: 'b', match: 'missing' }, { id: 'a', match: 'name' }])).toEqual({ total: 4, watchable: 3, shown: 2, protected: 1, withoutListings: 1 })
  })
  it('says why a row is locked and why a switch failed', () => {
    expect(channelRowNote(channels[3])).toBe('Copy-protected by the broadcaster, so it can’t be shown.')
    expect(channelRowNote({ reason: 'source_disabled', available: false })).toBe('Its tuner is off.')
    expect(channelRowNote(channels[0])).toBeUndefined()
    expect(channelSaveError(channels[2], false, new TypeError('x'))).toEqual({ title: 'Couldn’t hide 9.2 Shop', body: 'The server didn’t answer, so it’s still shown. Try the switch again.' })
    expect(channelSaveError(channels[2], true, fail(404, 'source or channel not found')).body).toBe('Source or channel not found. It’s still hidden. Try the switch again.')
  })
  it('labels each channel’s listings', () => {
    expect(listingsPill('name').label).toBe('Matched')
    expect(listingsPill('missing')).toEqual({ tone: 'warn', label: 'No listings', note: 'still plays' })
    expect(listingsPill(undefined).label).toBe('—')
    expect(guideMatchLine([{ match: 'name' }, { match: 'number' }, { match: 'missing' }])).toBe('2 matched · 1 without listings')
    expect(guideMatchLine([{ match: 'pending' }])).toBe('Matched after the first fetch')
  })
})

describe('Guide listings', () => {
  it('opens Pictures once listings are on, and On only after Finish', () => {
    expect(guideSetupStep(undefined, false, false)).toBe('turn-on')
    expect(guideSetupStep('pictures', false, false)).toBe('turn-on')
    expect(guideSetupStep('pictures', true, false)).toBe('pictures')
    expect(guideSetupStep('on', true, false)).toBe('pictures')
    expect(guideSetupStep('on', true, true)).toBe('on')
    expect(guideSetupStep('turn-on', true, false)).toBe('turn-on')
  })
  it('shows one message for the listings’ state', () => {
    const ago = () => '2 days ago'
    expect(guideNotice({ state: 'ok', refreshesLeft: 4 }, ago)).toBeNull()
    expect(guideNotice({ state: 'fetching', refreshesLeft: 4, lastAttemptAt: 'x' }, ago)).toMatchObject({ title: 'Fetching listings', spin: true })
    expect(guideNotice({ state: 'firmware', refreshesLeft: 4 }, ago)).toMatchObject({ title: 'HDHomeRun refused the listings request', email: true })
    const stale = guideNotice({ state: 'stale', refreshesLeft: 3, lastAttemptAt: 'x', message: 'HDHomeRun’s guide didn’t answer.' }, ago)!
    expect(stale.title).toBe('Listings are out of date')
    expect(stale.body).toBe('The last fetch failed 2 days ago: HDHomeRun’s guide didn’t answer. The guide shows what it has. It tries again tonight, or you can refresh now (3 left today).')
    expect(guideNotice({ state: 'failed', refreshesLeft: 0 }, ago)!.refresh).toBe(false)
  })
  it('says when no refreshes are left', () => {
    expect(guideRefreshError(fail(429))).toEqual({ title: 'No refreshes left today', body: 'You’ve used all 4. Listings refresh by themselves tonight.' })
    expect(guideRefreshError(fail(503, 'SiliconDust is busy')).body).toBe('SiliconDust is busy.')
  })
})

describe('Recordings', () => {
  it('says what is needed first, each with the way to get it', () => {
    const needs = recordingNeeds({ sources: [tuner], access: 'admins', tvLibraries: [], movieLibraries: 1 })
    expect(needs.map((n) => [n.key, n.met])).toEqual([['tuner', true], ['access', true], ['library', false]])
    expect(needs[2].detail).toBe('None yet. You have Movies only.')
    expect(recordingNeedWarning(needs, 1)).toEqual({ title: 'Recordings need a TV Shows library', body: 'Recorded episodes are saved into one. You have Movies only.' })
    const off = recordingNeeds({ sources: [{ ...tuner, enabled: false }], access: 'none', tvLibraries: [{ name: 'TV Shows' }], movieLibraries: 0 })
    expect(off.map((n) => n.met)).toEqual([false, false, true])
    expect(off[0].action).toBe('Turn a tuner on')
    expect(recordingNeedWarning(off, 0)!.title).toBe('Recordings need a tuner that’s on')
  })

  it('saves nothing before Review, and lets an existing setup open any step', () => {
    expect(recordingSetupStep(undefined, [], false, false)).toBe('who')
    expect(recordingSetupStep('timing', ['who'], false, false)).toBe('folders')
    expect(recordingSetupStep('timing', ['who', 'folders'], false, false)).toBe('timing')
    expect(recordingSetupStep('on', ['who', 'folders', 'timing'], false, false)).toBe('review')
    expect(recordingSetupStep('on', ['who', 'folders', 'timing'], true, false)).toBe('on')
    expect(recordingSetupStep('timing', [], false, true)).toBe('timing')
    expect(recordingSetupStep(undefined, [], false, true)).toBe('review')
  })

  it('checks Keep free and sums up Timing and space', () => {
    expect(keepFreeError('50')).toBeUndefined()
    expect(keepFreeError('')).toBe('Enter a number of gigabytes, 0 or more.')
    expect(keepFreeError('-1')).toBe('Enter a number of gigabytes, 0 or more.')
    expect(keepFreeError('200000')).toBe('Enter 100,000 GB or less.')
    expect(secondsWords(120)).toBe('2 minutes')
    expect(secondsWords(30)).toBe('30 seconds')
    expect(secondsWords(0)).toBe('No warning')
    expect(timingSummary({ padBefore: 1, padAfter: 2, minFreeBytes: 50e9, tunersReserved: 0, warningSeconds: 120, findAds: false }))
      .toBe('Start 1 min early, end 2 min late · keep 50 GB free · no tuners kept back · 2 min warning · ad breaks off')
  })

  it('words a refused save', () => {
    expect(recordingSaveError(fail(422, 'invalid recording settings: folder unavailable'), '/media/tv').title).toBe('The server can’t write to /media/tv')
    expect(recordingSaveError(fail(422, 'invalid recording settings'), '/media/tv').title).toBe('These settings were refused')
    expect(recordingSaveError(fail(409, 'recording unavailable'), '/media/tv').title).toBe('Recordings aren’t available')
  })
})

describe('The hub’s setup guide', () => {
  it('starts with Add a tuner and the rest waiting for it', () => {
    const g = liveSetupGuide({ tuners: 0, guide: { enabled: false }, recordings: { enabled: false }, dismissed: [] })
    expect(g.items.map((i) => [i.key, i.state, i.waits ?? ''])).toEqual([['tuner', 'now', ''], ['access', 'todo', 'After a tuner'], ['guide', 'todo', 'After a tuner'], ['recordings', 'todo', 'After a tuner']])
    expect([g.done, g.total, g.show]).toEqual([0, 4, true])
  })
  it('moves on to the optional tasks once a tuner is added', () => {
    const g = liveSetupGuide({ tuners: 1, guide: { enabled: true }, recordings: { enabled: false }, dismissed: [] })
    expect(g.items.map((i) => i.state)).toEqual(['done', 'done', 'done', 'now'])
    expect([g.done, g.total]).toEqual([3, 4])
  })
  it('goes away when the optional tasks are done or put away with Not now', () => {
    expect(liveSetupGuide({ tuners: 1, guide: { enabled: false }, recordings: { enabled: false }, dismissed: ['guide', 'recordings'] }).show).toBe(false)
    expect(liveSetupGuide({ tuners: 1, guide: { enabled: true }, recordings: { enabled: true }, dismissed: [] }).show).toBe(false)
    const one = liveSetupGuide({ tuners: 1, guide: { enabled: false }, recordings: { enabled: false }, dismissed: ['guide'] })
    expect(one.items.map((i) => i.key)).toEqual(['tuner', 'access', 'recordings'])
    expect(one.total).toBe(3)
  })
  it('leaves out tasks this server can’t do', () => {
    expect(liveSetupGuide({ tuners: 1, dismissed: [] }).items.map((i) => i.key)).toEqual(['tuner', 'access'])
  })
})
