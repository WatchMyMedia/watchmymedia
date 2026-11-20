import { describe, expect, it } from 'vitest'
import { nearest } from '../../app/geometry'
import { REQUIRED_API_LEVEL } from '../../session/session'
import type { RememberedServer } from '../../session/servers'
import { accountWords, ACCOUNT_FOCUS, ADD_FOCUS, addedWords, addLine, alreadyAdded, alreadyAddedLine, MANAGE_FOCUS, manageDetail, manageLede, nowOn, panelFocus, panelReturn, removeWords, RETRY_FOCUS, serverFocus,
  serverRows, serversLine, signOutMove, switchFailedWords, versionLine } from './serverRows'

const account = { userId: 4, name: 'Sam', isAdmin: false, token: 't', sessionId: 's' }
const living: RememberedServer = { id: 'living', name: 'Living room server', baseUrl: 'https://home.example.net', version: 'v1.8.0', account, lastUsedAt: 300 }
const cabin: RememberedServer = { id: 'cabin', name: 'Cabin server', baseUrl: 'http://192.0.2.31:27587', version: 'v1.8.0', account, lastUsedAt: 100 }
const den: RememberedServer = { id: 'den', name: 'Den server', baseUrl: 'http://192.0.2.52:27587', version: 'v1.8.0', account, lastUsedAt: 200 }

describe('Servers’ rows', () => {
  it('lists the current server first, then by last use, with who is signed in and where', () => {
    expect(serverRows([cabin, den, living], 'cabin')).toEqual([
      { id: 'cabin', name: 'Cabin server', detail: 'Sam · 192.0.2.31:27587', state: 'current', canChoose: false },
      { id: 'living', name: 'Living room server', detail: 'Sam · home.example.net', state: 'ready', canChoose: true },
      { id: 'den', name: 'Den server', detail: 'Sam · 192.0.2.52:27587', state: 'ready', canChoose: true },
    ])
  })

  it('says when one is being switched to, is signed out here, or is too old to choose', () => {
    const signedOut = { ...den, account: undefined }
    const old = { ...cabin, apiLevel: REQUIRED_API_LEVEL - 1 }
    const rows = serverRows([living, signedOut, old], 'living', { state: 'switching', id: 'den', name: 'Den server' })
    expect(rows.map((r) => [r.name, r.state, r.detail, r.canChoose])).toEqual([
      ['Living room server', 'current', 'Sam · home.example.net', false],
      ['Den server', 'signedOut', 'Signed out here · sign in again', true],
      ['Cabin server', 'tooOld', 'Too old for this app', false],
    ])
    expect(serverRows([living, den], 'living', { state: 'switching', id: 'den', name: 'Den server' })[1]).toMatchObject({ state: 'switching', canChoose: true })
    // One that failed is a row like any other again; the message above the list says what happened.
    expect(serverRows([living, den], 'living', { state: 'failed', id: 'den', name: 'Den server', problem: 'unreachable' })[1]).toMatchObject({ state: 'ready' })
  })

  it('starts focus on the first server to switch to, on Add a server with one, on Try again after a failure', () => {
    const several = serverRows([living, den, cabin], 'living')
    expect(panelFocus(several, undefined, false)).toBe(serverFocus('den'))
    expect(panelFocus(serverRows([living], 'living'), undefined, false)).toBe(ADD_FOCUS)
    expect(panelFocus(serverRows([living], 'living'), undefined, true)).toBe(MANAGE_FOCUS)
    expect(panelFocus(several, { state: 'failed', id: 'cabin', name: 'Cabin server', problem: 'unreachable' }, false)).toBe(RETRY_FOCUS)
    expect(panelFocus(several, { state: 'switching', id: 'cabin', name: 'Cabin server' }, false)).toBe(serverFocus('cabin'))
    // One too old is skipped: focus opens on the next that can be chosen.
    expect(panelFocus(serverRows([living, { ...den, apiLevel: 1 }, cabin], 'living'), undefined, false)).toBe(serverFocus('cabin'))
  })

  it('puts focus back on the account button when Return closes the panel it opened', () => {
    expect(ACCOUNT_FOCUS).toBe('rail/account')
    expect(panelReturn('rail', true)).toBe(ACCOUNT_FOCUS)
    // After a switch the new Home takes focus; a panel a screen opened gives it back to the screen.
    expect(panelReturn('rail', false)).toBeUndefined()
    expect(panelReturn('screen', true)).toBeUndefined()
  })

  it('reaches the account button at the rail’s foot with ▼ from Settings, and comes back with ▲', () => {
    // The rail at 1920 wide: its sections from the top, a gap, and the account button at the foot.
    const item = (top: number) => ({ left: 18, top, width: 60, height: 60 })
    const sections = [44, 118, 192, 266, 340, 414].map(item)
    const accountButton = item(976)
    const settings = sections[5]
    const all = [...sections, accountButton]
    expect(all[nearest(settings, all, 'down')]).toBe(accountButton)
    expect(all[nearest(accountButton, all, 'up')]).toBe(settings)
    expect(nearest(accountButton, all, 'down')).toBe(-1)
  })
})

describe('Servers’ words', () => {
  it('names the account button for the server it is on, and shows the server with more than one', () => {
    expect(accountWords('Sam', 'Living room server', 3)).toEqual({ label: 'Servers. Sam, on Living room server', visible: 'Living room server' })
    expect(accountWords('Sam', 'Living room server', 1).visible).toBe('Sam')
    expect(nowOn('Cabin server')).toBe('Now on Cabin server')
  })

  it('says why a switch didn’t happen, and that the app is still where it was', () => {
    const failed = (problem: 'unreachable' | 'moved' | 'tooOld' | 'notSetUp') => switchFailedWords({ state: 'failed', id: 'cabin', name: 'Cabin server', problem }, cabin, 'Living room server')
    expect(failed('unreachable')).toEqual({ title: 'Couldn’t reach Cabin server', body: 'It may be off, or only reachable on its own network. You’re still on Living room server.' })
    expect(failed('moved')).toEqual({ title: 'Cabin server isn’t at this address now', body: 'Another WatchMyMedia server answers at 192.0.2.31:27587. You’re still on Living room server.' })
    expect(failed('tooOld')).toEqual({ title: 'Cabin server is too old for this app', body: 'Update it, then switch. You’re still on Living room server.' })
    expect(failed('notSetUp').body).toMatch(/You’re still on Living room server\.$/)
  })

  it('words Settings’ Servers row, page and a server’s page', () => {
    // Beside the count a name ending in " server" loses the word; one server shows its name whole.
    expect(serversLine('Living room server', 3)).toBe('Living room and 2 more')
    expect(serversLine('Lakeside', 2)).toBe('Lakeside and 1 more')
    expect(serversLine('Living room server', 1)).toBe('Living room server')
    expect(manageLede(3)).toBe('This TV is signed in to each of these. Search looks in all of them.')
    expect(manageLede(1)).toBe('This TV is signed in to this server. Add another and search looks in both.')
    expect(manageDetail(living)).toBe('Sam · home.example.net · v1.8.0')
    expect(manageDetail({ ...cabin, account: undefined })).toBe('Signed out here · 192.0.2.31:27587 · v1.8.0')
    expect(versionLine('v1.8.0')).toBe('WatchMyMedia 1.8.0')
    expect(addLine(false)).toBe('By its address')
    expect(addLine(true)).toBe('This TV keeps up to 12 servers. Remove one in Settings › Account & devices › Servers, then add this one.')
    expect(alreadyAddedLine('Cabin server')).toBe('Cabin server is already added. Choose it in Servers to switch to it.')
    expect(alreadyAdded(['Living room server', 'Cabin server'])).toBe('Already added: Living room server, Cabin server.')
    expect(alreadyAdded([])).toBe('')
  })

  it('asks before removing, and says what adding one changed', () => {
    expect(removeWords('Cabin server')).toEqual({ title: 'Remove Cabin server from this TV?',
      lines: ['This TV is signed out of it.', 'The server, your account on it and what you’ve watched there aren’t changed. You can add it again.'] })
    expect(addedWords('Den server', 'Sam', 3)).toMatchObject({ title: 'Den server is added', lead: 'You’re on it now, signed in as Sam.',
      changed: 'This TV is signed in to 3 servers. Search looks in all of them.' })
    // Sign out of this TV moves to the one used last that is still signed in.
    expect(signOutMove([living, cabin, den], 'living')).toBe('Den server')
    expect(signOutMove([living, { ...den, account: undefined }], 'living')).toBeUndefined()
  })
})
