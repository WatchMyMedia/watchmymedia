import { describe, expect, it } from 'vitest'
import { ApiError } from '../api/client'
import {
  addRefusal, addSteps, cantRequest, deleteRefusal, deleteSavedNote, downloadSavedNote, freeNameFor, nameList, nameProblem, nameTaken, passwordProblem,
  personId, personLine, possessive, resetRefusal, resolveAddStep, seerrRefusal, seerrState,
} from './usersSettings'

const refused = (status: number, detail: string) => new ApiError(new Response('', { status }), { detail })

describe('Add a person', () => {
  it('asks for the password away from home only when adding an admin', () => {
    expect(addSteps(false).map((s) => s.guarded ?? false)).toEqual([false, false, false])
    expect(addSteps(true)[1]).toMatchObject({ key: 'password', guarded: true })
  })
  it('opens Name and role until there is a name', () => {
    expect(resolveAddStep(undefined, { name: '', admin: false }).key).toBe('name')
    expect(resolveAddStep('password', { name: '  ', admin: false }).key).toBe('name')
    expect(resolveAddStep('added', { name: 'Sam', admin: true }).key).toBe('password')
    expect(resolveAddStep('password', { name: 'Sam', admin: true }).key).toBe('password')
    expect(resolveAddStep('name', { name: 'Sam', admin: true }).key).toBe('name')
  })
  it('goes back to Name and role when the server says the name is taken', () => {
    expect(resolveAddStep('password', { name: 'Sam', admin: false, nameError: 'taken' }).key).toBe('name')
  })
  it('shows only Added once the person is added, since the account can’t be unmade', () => {
    const created = { id: 4, name: 'Sam', admin: true, recordHistory: true }
    for (const step of [undefined, 'name', 'password', 'added', 'nope']) expect(resolveAddStep(step, { name: 'Sam', admin: true, created }).key).toBe('added')
  })
})

describe('names', () => {
  const people = ['Robin', 'Alex', 'Alex B']
  it('are taken whatever their case', () => {
    expect(nameTaken('alex', people)).toBe(true)
    expect(nameTaken(' ALEX ', people)).toBe(true)
    expect(nameTaken('Casey', people)).toBe(false)
  })
  it('suggest the next free letter', () => {
    expect(freeNameFor('Casey', people)).toBe('Casey B')
    expect(freeNameFor('Alex', people)).toBe('Alex C')
  })
  it('say why they can’t be used, as the board words it', () => {
    expect(nameProblem('Alex', ['Alex'])).toEqual({ title: 'There’s already a person called Alex', text: 'Names have to be different. Try Alex B.' })
    expect(nameProblem('  ', [])?.title).toBe('Enter a name')
    expect(nameProblem('x'.repeat(65), [])?.title).toBe('That name is too long')
    expect(nameProblem('Sam', ['Alex'])).toBeNull()
  })
})

describe('passwords', () => {
  it('need eight characters or more, typed once', () => {
    expect(passwordProblem('short')).toBe('Use 8 characters or more.')
    expect(passwordProblem('long enough')).toBeNull()
  })
})

describe('refusals', () => {
  it('sends a taken name back to its field with a suggestion', () => {
    expect(addRefusal(refused(422, 'a user named "Sam" already exists'), 'Sam', ['Alex'])).toEqual({
      field: { field: 'name', text: 'There’s already a person called Sam. Names have to be different. Try Sam B.' },
    })
  })
  it('keeps a short password under its field', () => {
    expect(addRefusal(refused(422, 'password must be at least 8 characters'), 'Sam', []).field).toEqual({ field: 'password', text: 'Use 8 characters or more.' })
  })
  it('puts anything else above the footer', () => {
    const r = addRefusal(refused(500, 'disk full'), 'Sam', [])
    expect(r.step).toEqual({ title: 'Sam wasn’t added', text: 'Disk full.' })
    expect(addRefusal(new TypeError('fetch failed'), 'Sam', []).step?.text).toMatch(/didn’t answer/)
  })
  it('words a reset', () => {
    expect(resetRefusal(refused(422, 'password must be at least 8 characters'), 'Alex')).toMatch(/^Use 8 characters/)
    expect(resetRefusal(refused(404, 'user not found'), 'Alex')).toBe('Alex was deleted, so there’s no password to reset.')
    expect(resetRefusal(refused(409, 'change your own password under Account & devices'), 'Alex')).toBe('Change your own password under Account & devices.')
  })
  it('words a delete', () => {
    expect(deleteRefusal(refused(404, 'user not found'), 'Alex')).toBe('Alex was already deleted.')
    expect(deleteRefusal(refused(409, 'you cannot delete your own account'), 'Alex')).toBe('You can’t delete your own account.')
    expect(deleteRefusal(refused(500, ''), 'Alex')).toMatch(/Alex wasn’t deleted/)
  })
  it('words a Seerr link', () => {
    expect(seerrRefusal(refused(422, 'Seerr has no user 7'), 'alex', 'Alex')).toEqual({
      title: 'Seerr has no user “alex” any more',
      text: 'The choice wasn’t saved. Pick another Seerr user, or None and Alex can’t request.',
    })
    expect(seerrRefusal(refused(503, 'integrations are off'), 'alex', 'Alex').title).toBe('Integrations are off on this server')
    expect(seerrRefusal(refused(502, 'could not reach the app: timeout'), 'alex', 'Alex').title).toBe('Couldn’t reach Seerr')
  })
})

describe('a person’s row', () => {
  const seerr = [{ id: 1, canRequest: true }, { id: 2, canRequest: false }]
  it('knows their Seerr state only once Seerr’s users are read', () => {
    expect(seerrState(1, undefined)).toBeUndefined()
    expect(seerrState(undefined, seerr)).toBe('none')
    expect(seerrState(1, seerr)).toBe('linked')
    expect(seerrState(2, seerr)).toBe('cant')
    expect(seerrState(9, seerr)).toBe('gone')
    expect([undefined, 'linked', 'none', 'gone', 'cant'].map((s) => cantRequest(s as never))).toEqual([false, false, true, true, true])
  })
  it('says what is recorded and linked', () => {
    expect(personLine({ recordHistory: true, plexLinked: true, devices: 3 })).toBe('History recorded · linked to Plex · played on 3 devices')
    expect(personLine({ recordHistory: false, seerr: 'none' })).toBe('History not recorded · no Seerr user')
    expect(personLine({ recordHistory: true, devices: 1 })).toBe('History recorded · played on 1 device')
  })
  it('reads an address', () => {
    expect(personId('4')).toBe(4)
    expect(personId('x')).toBe(0)
    expect(personId(undefined)).toBe(0)
  })
})

describe('what everyone can do', () => {
  const people = [{ name: 'Robin', isAdmin: true }, { name: 'Alex', isAdmin: false }, { name: 'Casey', isAdmin: false }]
  it('names the people who lose downloads', () => {
    expect(downloadSavedNote('everyone', 'admins', people)).toBe('Alex and Casey’s downloads are removed from their devices the next time each app syncs.')
    expect(downloadSavedNote('admins', 'none', people)).toBe('Robin’s downloads are removed from their devices the next time each app syncs.')
    expect(downloadSavedNote('everyone', 'none', [...people, { name: 'Sam', isAdmin: false }])).toMatch(/^4 people’s downloads/)
  })
  it('says what opened up', () => {
    expect(downloadSavedNote('admins', 'everyone', people)).toBe('Everyone with an account can now download to watch offline.')
    expect(downloadSavedNote('none', 'admins', people)).toBe('Admins can download to watch offline.')
    expect(deleteSavedNote('everyone')).toMatch(/^Everyone/)
    expect(deleteSavedNote('admins')).toMatch(/^Only admins/)
  })
  it('words names', () => {
    expect(possessive('Alex')).toBe('Alex’s')
    expect(possessive('Chris')).toBe('Chris’')
    expect(nameList(['Alex', 'Casey', 'Sam'])).toBe('Alex, Casey and Sam')
  })
})
