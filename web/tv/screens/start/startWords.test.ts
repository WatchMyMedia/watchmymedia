import { describe, expect, it } from 'vitest'
import { AddressError } from '../../session/address'
import { addressFailed, lastUsed, lookingFor, problemDetails, serverDetail, startProblemWords } from './startWords'

const server = { id: 's', name: 'Office Mac mini', baseUrl: 'http://192.168.1.31:27587', version: 'v1.7.0' }
const NOW = new Date(2026, 10, 23, 20, 12).getTime()
const account = { userId: 4, name: 'Robin', isAdmin: false, token: 't', sessionId: 's' }

describe('the start screens’ words', () => {
  it('names who is signed in and when a server was last used, never its address', () => {
    expect(serverDetail({ ...server, account, lastUsedAt: new Date(2026, 10, 23, 9).getTime() }, NOW, 'en-US')).toBe('Robin · last used today')
    expect(serverDetail({ ...server, lastUsedAt: NOW }, NOW)).toBe('Needs signing in again')
    expect(serverDetail({ ...server, account, apiLevel: 1, lastUsedAt: NOW }, NOW)).toBe('Needs an update before this app can use it')
  })

  it('starts on the server last used', () => {
    expect(lastUsed([{ id: 'a', lastUsedAt: 1 }, { id: 'b', lastUsedAt: 3 }, { id: 'c', lastUsedAt: 2 }])).toBe('b')
    expect(lastUsed([])).toBeUndefined()
  })

  it('says what an address that didn’t answer means', () => {
    expect(addressFailed(new TypeError('Failed to fetch')).title).toBe('No WatchMyMedia server answered at that address.')
    expect(addressFailed(new AddressError()).title).toBe('That isn’t a server address')
    expect(lookingFor(' https://home.example.net ')).toBe('Looking for a server at home.example.net…')
  })

  it('words each problem with why, what to do and its first action', () => {
    expect(startProblemWords('unreachable', server)).toMatchObject({ title: 'Can’t reach Office Mac mini', first: 'Try again', details: true })
    const old = startProblemWords('tooOld', server)
    expect(old.title).toBe('Office Mac mini needs an update')
    expect(old.why).toBe('This TV app works with WatchMyMedia 1.8 or newer. The server has 1.7.0.')
    expect(old.details).toBe(false)
    expect(startProblemWords('tooOld', { ...server, version: undefined }).why).toMatch(/The server has an older one\.$/)
    expect(startProblemWords('signedOut', server)).toMatchObject({ first: 'Sign in again', title: 'This TV was signed out', why: 'Someone signed this TV out of Office Mac mini from another device.' })
    expect(startProblemWords('moved', server).first).toBe('Connect to it')
  })

  it('keeps the address and what the TV heard back for Details', () => {
    expect(problemDetails('unreachable', server)).toEqual([
      { label: 'Address', value: '192.168.1.31:27587' },
      { label: 'What this TV heard back', value: 'Nothing: no server answered at this address.' },
    ])
    expect(problemDetails('notSetUp', server, { name: 'Office Mac mini', version: 'v1.8.0' })).toEqual([
      { label: 'Open in a browser', value: 'http://192.168.1.31:27587' },
      { label: 'What this TV heard back', value: 'WatchMyMedia 1.8.0, waiting to be set up' },
    ])
    expect(problemDetails('moved', server, { name: 'Den server', version: 'v1.8.2' })[1].value).toBe('Another server, Den server, WatchMyMedia 1.8.2')
  })
})
