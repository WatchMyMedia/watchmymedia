import { describe, expect, it } from 'vitest'
import {
  ago,
  canRetry,
  checkMessage,
  connectMessage,
  connectStep,
  disconnectMessage,
  excludeMessage,
  importLabel,
  importStep,
  linkSaveMessage,
  linkStep,
  linkSummary,
  minutesLeft,
  nameList,
  originHost,
  pauseNeedsDialog,
  plexCode,
  plexGuide,
  plexHubState,
  plexLinkDraft,
  plexNextStep,
  plexPeopleConflict,
  plexPersonLeftOut,
  plexPersonReady,
  problemBanner,
  restoreMessage,
  restoreStep,
  retryMessage,
  retryUntil,
  signInMessage,
  skipped,
  startMessage,
  syncLine,
  syncMessage,
  undoMessage,
  undoStep,
} from './plexSettings'
import type { PlexPerson, PlexSection } from './plex'

// A refusal as the API client throws it: the problem's detail is the reason code.
const refusal = (code: string, status = 409) => Object.assign(new Error(code), { name: 'ApiError', status })
const cancel = Object.assign(new Error(''), { name: 'ConfirmationCancelled' })
const offline = new TypeError('Failed to fetch')

const connected = { status: 'connected' as const, linkedUsers: 2, linkedLibraries: 2 }
const unlinked = { ...connected, linkedUsers: 0 }

describe('the hub', () => {
  it('is new, part way, or set up', () => {
    expect(plexHubState(undefined, false)).toBe('new')
    expect(plexHubState(unlinked, false)).toBe('mid')
    expect(plexHubState(connected, false)).toBe('mid')
    expect(plexHubState(connected, true)).toBe('set')
  })

  it('walks the setup guide in order, each task waiting on the one before', () => {
    const fresh = plexGuide(undefined, false)
    expect([fresh.state('connect'), fresh.state('link'), fresh.state('import')]).toEqual(['now', 'todo', 'todo'])
    expect(fresh.done).toBe(0)
    const mid = plexGuide(unlinked, false)
    expect([mid.state('connect'), mid.state('link'), mid.state('import')]).toEqual(['done', 'now', 'todo'])
    const linked = plexGuide(connected, false)
    expect([linked.state('link'), linked.state('import')]).toEqual(['done', 'now'])
    const set = plexGuide(connected, true)
    expect(set.done).toBe(3)
    expect(set.total).toBe(3)
  })

  it('needs a library as well as a person before the import is Now', () => {
    expect(plexGuide({ ...connected, linkedLibraries: 0 }, true).state('import')).toBe('todo')
  })

  it('offers one next step, and none once set up', () => {
    expect(plexNextStep(undefined, false, false)).toBe('connect')
    expect(plexNextStep({ ...connected, status: 'needs_reconnect' }, true, false)).toBe('reconnect')
    expect(plexNextStep(connected, false, true)).toBe('running')
    expect(plexNextStep(unlinked, false, false)).toBe('link')
    expect(plexNextStep(connected, false, false)).toBe('check')
    expect(plexNextStep(connected, true, false)).toBeNull()
  })

  it('asks before pausing only when it would cancel a running import', () => {
    expect(pauseNeedsDialog(true, true)).toBe(true)
    expect(pauseNeedsDialog(true, false)).toBe(false)
    expect(pauseNeedsDialog(false, true)).toBe(false)
  })
})

describe('the flows’ steps', () => {
  it('moves Connect on by itself once signed in, and finishes only after connecting', () => {
    expect(connectStep({ signedIn: false, connected: false })).toBe('sign-in')
    expect(connectStep({ signedIn: true, connected: false })).toBe('server')
    expect(connectStep({ signedIn: false, connected: true })).toBe('done')
  })

  it('opens Link at the first step not yet confirmed', () => {
    const none = { people: false, libraries: false, saved: false }
    expect(linkStep('review', none)).toBe('people')
    expect(linkStep('review', { ...none, people: true })).toBe('libraries')
    expect(linkStep('review', { people: true, libraries: true, saved: false })).toBe('review')
    expect(linkStep('saved', { people: true, libraries: true, saved: false })).toBe('review')
    expect(linkStep('saved', { people: true, libraries: true, saved: true })).toBe('saved')
    expect(linkStep('people', { people: true, libraries: true, saved: true })).toBe('people')
  })

  it('shows Check until the check is done, then What will change', () => {
    expect(importStep(undefined, { confirmed: false })).toBe('check')
    expect(importStep('how', { preview: { status: 'running' }, confirmed: true })).toBe('check')
    expect(importStep('check', { preview: { status: 'complete' }, confirmed: false })).toBe('changes')
    expect(importStep('how', { preview: { status: 'complete' }, confirmed: false })).toBe('changes')
    expect(importStep('how', { preview: { status: 'complete' }, confirmed: true })).toBe('how')
  })

  it('keeps an out-of-date check on What will change, where it asks for a fresh one', () => {
    expect(importStep('changes', { preview: { status: 'stale' }, confirmed: false })).toBe('changes')
    expect(importStep('changes', { preview: { status: 'failed' }, confirmed: false })).toBe('check')
  })

  it('has no Back once an import exists', () => {
    expect(importStep('changes', { preview: { status: 'complete' }, confirmed: true, run: { status: 'running' } })).toBe('import')
    expect(importStep('check', { confirmed: false, run: { status: 'partial' } })).toBe('import')
    expect(importStep('import', { confirmed: false, run: { status: 'cancelled' } })).toBe('import')
    expect(importStep('import', { confirmed: false, run: { status: 'complete' } })).toBe('done')
  })

  it('confirms an undo only after it was checked', () => {
    expect(undoStep('confirm', { checked: false, undone: false })).toBe('check')
    expect(undoStep('confirm', { checked: true, undone: false })).toBe('confirm')
    expect(undoStep('done', { checked: true, undone: false })).toBe('confirm')
    expect(undoStep('done', { checked: true, undone: true })).toBe('done')
    expect(undoStep('confirm', { checked: true, undone: true })).toBe('done')
    expect(undoStep('check', { checked: true, undone: false })).toBe('check')
  })

  it('shows Ready to check again only after starting fresh', () => {
    expect(restoreStep({ restarted: false })).toBe('what')
    expect(restoreStep({ restarted: true })).toBe('ready')
  })
})

describe('linking', () => {
  const people: PlexPerson[] = [
    { plexUserId: 1, title: 'Robin', kind: 'owner', status: 'ready', linkedUserId: 4 },
    { plexUserId: 2, title: 'Alex', kind: 'managed', status: 'ready' },
    { plexUserId: 3, title: 'Jordan', kind: 'managed', status: 'needs_pin', linkedUserId: 6 },
  ]
  const sections: PlexSection[] = [{ sectionId: 9, title: 'Films', type: 'movie', linkedLibraryId: 7 }]

  it('starts from today’s links, leaving out people who can’t be read', () => {
    expect(plexLinkDraft(people, sections)).toEqual({ users: { 1: 4, 2: 0, 3: 0 }, libraries: { 9: 7 } })
  })

  it('names the two people sent to one account', () => {
    const users = [{ id: 4, name: 'Robin' }]
    expect(plexPeopleConflict({ users: { 1: 4, 2: 4 }, libraries: {} }, people, users)?.title).toBe('Robin and Alex can’t both go to the account Robin')
    expect(plexPeopleConflict({ users: { 1: 4, 2: 0 }, libraries: {} }, people, users)).toBeNull()
  })

  it('says why a person is left out', () => {
    expect(plexPersonReady('needs_pin')).toEqual({ tone: 'warn', label: 'Needs a PIN' })
    expect(plexPersonReady('needs_profile_authorization').label).toBe('Can’t be read yet')
    expect(plexPersonLeftOut({ title: 'Casey', status: 'needs_profile_authorization' })).toBe('Check Casey’s access in Plex, then refresh. Left out for now.')
    expect(plexPersonLeftOut({ title: 'Jordan', status: 'needs_pin' })).toContain('PIN')
  })

  it('sums up the links for the review', () => {
    expect(linkSummary([['Robin', 'Robin'], ['Alex', 'Alex']], ['Casey', 'Jordan'])).toBe('Robin → Robin · Alex → Alex · Casey and Jordan left out')
    expect(linkSummary([], [])).toBe('Nothing linked')
  })
})

describe('importing', () => {
  const now = Date.parse('2026-11-14T14:00:00Z')

  it('counts a check down in whole minutes', () => {
    expect(minutesLeft('2026-11-14T14:12:00Z', now)).toBe(12)
    expect(minutesLeft('2026-11-14T14:00:20Z', now)).toBe(1)
    expect(minutesLeft('2026-11-14T13:59:00Z', now)).toBe(0)
    expect(minutesLeft(undefined, now)).toBe(0)
  })

  it('names the count on the import button', () => {
    expect(importLabel(124)).toBe('Import 124 watched marks')
    expect(importLabel(1)).toBe('Import 1 watched mark')
    expect(importLabel(0)).toBe('Import now')
  })

  it('offers Retry for 7 days, on the same links, while connected', () => {
    const run = { status: 'partial', generation: 3, startedAt: '2026-11-14T13:00:00Z' }
    const c = { status: 'connected' as const, generation: 3 }
    expect(retryUntil(run.startedAt).toISOString()).toBe('2026-11-21T13:00:00.000Z')
    expect(canRetry(run, c, now).ok).toBe(true)
    expect(canRetry({ ...run, status: 'complete' }, c, now).ok).toBe(false)
    expect(canRetry(run, c, Date.parse('2026-11-21T13:00:01Z')).why).toContain('no longer offered')
    expect(canRetry(run, { ...c, generation: 4 }, now).why).toContain('links changed')
    expect(canRetry(run, { ...c, status: 'needs_reconnect' }, now).why).toContain('sign in again')
  })

  it('adds up what was skipped', () => {
    expect(skipped({ unmatched: 8, ambiguous: 2, unavailable: 1 })).toBe(11)
  })
})

describe('words', () => {
  const now = Date.parse('2026-11-14T14:00:00Z')

  it('lists names', () => {
    expect(nameList([])).toBe('')
    expect(nameList(['Robin'])).toBe('Robin')
    expect(nameList(['Robin', 'Alex'])).toBe('Robin and Alex')
    expect(nameList(['Robin', 'Alex', 'Casey'])).toBe('Robin, Alex and Casey')
  })

  it('says how long ago, up to a day', () => {
    expect(ago('2026-11-14T13:59:40Z', now)).toBe('a moment ago')
    expect(ago('2026-11-14T13:54:00Z', now)).toBe('6 minutes ago')
    expect(ago('2026-11-14T11:00:00Z', now)).toBe('3 hours ago')
    expect(ago('2026-11-12T11:00:00Z', now)).toBeNull()
  })

  it('says when Plex is checked next', () => {
    expect(syncLine('2026-11-14T13:54:00Z', now)).toBe('Plex was last checked 6 minutes ago. Next check in about 9 minutes.')
    expect(syncLine('2026-11-14T13:30:00Z', now)).toBe('Plex was last checked 30 minutes ago. The next check is due now.')
    expect(syncLine(undefined, now)).toBe('Plex is checked about every 15 minutes.')
  })

  it('shows a server by its host', () => {
    expect(originHost('http://192.0.2.10:32400')).toBe('192.0.2.10')
    expect(originHost('not a url')).toBe('not a url')
  })
})

describe('refusals', () => {
  it('reads the reason code from a refusal', () => {
    expect(plexCode(refusal('run_active'))).toBe('run_active')
    expect(plexCode(new Error('Something else went wrong'))).toBeNull()
    expect(plexCode('nothing')).toBe('nothing')
  })

  it('says nothing when the password dialog was cancelled', () => {
    for (const words of [signInMessage, (e: unknown) => connectMessage(e, 'Maple House'), (e: unknown) => checkMessage(e, 'Maple House'), (e: unknown) => disconnectMessage(e, 'Maple House')])
      expect(words(cancel)).toBeNull()
  })

  it('words each sign-in refusal', () => {
    expect(signInMessage(refusal('too_many_sign_ins'))?.title).toBe('Too many sign-ins are waiting')
    expect(signInMessage(refusal('too_many_sign_ins'))?.fix).toBeUndefined()
    expect(signInMessage(refusal('sign_in_not_found', 404))).toMatchObject({ title: 'The sign-in ran out of time', fix: { do: 'start-again' } })
    expect(signInMessage(refusal('restricted_account', 422))).toMatchObject({ title: 'That Plex account doesn’t own a server', fix: { label: 'Sign in with another account' } })
    expect(signInMessage(refusal('bad_sign_in_link'))?.title).toBe('Plex’s sign-in link could not be checked')
    expect(signInMessage(offline)?.title).toBe('The server didn’t answer')
    expect(signInMessage(refusal('server_stopping'))?.body).toContain('stopping')
  })

  it('words each connect refusal, naming the server', () => {
    expect(connectMessage(refusal('server_not_owned', 422), 'Maple House')).toMatchObject({ title: 'This account doesn’t own a Plex server', fix: { do: 'sign-in-again' } })
    expect(connectMessage('no_owned_server', 'Maple House')?.title).toBe('This account doesn’t own a Plex server')
    expect(connectMessage(refusal('server_identity_mismatch', 422), 'Maple House')?.body).toContain('It isn’t Maple House')
    expect(connectMessage(refusal('transport', 502), 'Maple House')).toMatchObject({ title: 'Couldn’t reach Maple House at that address', fix: { do: 'try-again' } })
    expect(connectMessage(refusal('one_server_only'), 'Maple House')).toMatchObject({ title: 'Another Plex server is already connected', fix: { do: 'exit' } })
    expect(connectMessage(refusal('different_owner'), 'Maple House')?.title).toBe('Another Plex server is already connected')
    expect(connectMessage(refusal('origin_not_allowed', 422), 'Maple House')?.field).toBe(true)
    expect(connectMessage(refusal('sign_in_not_found', 404), 'Maple House')?.title).toBe('The sign-in ran out of time')
  })

  it('words each check refusal', () => {
    expect(checkMessage(refusal('run_active'), 'Maple House')).toMatchObject({ title: 'An import is still running', fix: { do: 'see-import' } })
    expect(checkMessage(refusal('nothing_linked'), 'Maple House')).toMatchObject({ title: 'Nobody is linked yet', fix: { do: 'link' } })
    expect(checkMessage(refusal('transport', 502), 'Maple House')).toMatchObject({ title: 'Couldn’t reach Maple House', fix: { do: 'check-again' } })
    expect(checkMessage(refusal('needs_reconnect'), 'Maple House')?.fix?.do).toBe('reconnect')
    expect(checkMessage('stale', 'Maple House')?.title).toBe('This check is out of date')
    expect(checkMessage('preview_not_found', 'Maple House')?.title).toBe('This check ran out')
    expect(checkMessage(refusal('preview_running'), 'Maple House')?.title).toBe('A check is already running')
    expect(checkMessage('pagination_drift', 'Maple House')?.body).toContain('changed while it was being checked')
  })

  it('words a refused import start', () => {
    expect(startMessage(refusal('preview_not_ready'), 'Maple House')).toMatchObject({ title: 'Something changed since the check', fix: { do: 'check-again' } })
    expect(startMessage(refusal('stale_generation'), 'Maple House')?.title).toBe('Something changed since the check')
    expect(startMessage(refusal('run_active'), 'Maple House')?.fix?.do).toBe('see-import')
  })

  it('words a refused save of the links', () => {
    expect(linkSaveMessage(refusal('stale_generation'), 'Maple House')).toMatchObject({ body: expect.stringContaining('Maple House’s people or libraries changed'), fix: { do: 'refresh' } })
    expect(linkSaveMessage(refusal('unknown_user', 422), 'Maple House')?.body).toContain('Nothing was saved')
    expect(linkSaveMessage(refusal('run_active'), 'Maple House')?.fix?.do).toBe('see-import')
  })

  it('words leaving items out, retrying, undoing and restoring', () => {
    expect(excludeMessage(refusal('run_active'))?.title).toBe('An import is running')
    expect(retryMessage(refusal('run_scope_expired'))?.title).toBe('This import can’t be retried any more')
    expect(retryMessage(refusal('stale_generation'))?.fix?.do).toBe('check-again')
    expect(undoMessage('nothing', 'check')?.title).toBe('This import can’t be undone any more')
    expect(undoMessage(refusal('run_not_found', 404), 'check')?.title).toBe('This import can’t be undone any more')
    expect(undoMessage(refusal('undo_preview_stale'), 'undo')).toMatchObject({ title: 'The undo check ran out', fix: { do: 'check-undo' } })
    expect(undoMessage(refusal('undo_preview_limit'), 'check')?.body).toContain('Several undo checks')
    expect(restoreMessage(refusal('stale_generation'))?.body).toBe('Nothing was paused. Try again.')
  })

  it('says the switch is back as it was when Pause or Resume fails', () => {
    expect(syncMessage(offline, true, 'Maple House')).toMatchObject({ title: 'Couldn’t pause updates', body: 'The server didn’t answer. Updates are still on. Try again.' })
    expect(syncMessage(refusal('import_first'), false, 'Maple House')?.body).toContain('Updates are still paused.')
    expect(syncMessage(refusal('needs_reconnect'), true, 'Maple House')?.fix?.do).toBe('reconnect')
  })

  it('turns a connection problem into a banner with its fix', () => {
    expect(problemBanner(undefined)).toBeNull()
    expect(problemBanner('source_identity_changed')?.fix?.do).toBe('restore')
    expect(problemBanner('incomplete_snapshot')?.body).toContain('complete snapshot')
  })
})
