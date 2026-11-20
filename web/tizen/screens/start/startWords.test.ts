import { describe, expect, it } from 'vitest'
import { startProblemWords } from './startWords'

const server = { id: 's', name: 'Office Mac mini', baseUrl: 'http://192.168.1.31:27587', version: 'v1.7.0' }

describe('the start problems’ words', () => {
  it('words each as ServerProblems does', () => {
    expect(startProblemWords('unreachable', server).body).toBe('This TV couldn’t connect to 192.168.1.31:27587. Check that the server is switched on and that this TV is on the same network, then try again.')
    expect(startProblemWords('notSetUp', server).address).toEqual({ label: 'Open in a browser', value: 'http://192.168.1.31:27587' })
    const old = startProblemWords('tooOld', server)
    expect(old.title).toBe('This server is too old for this TV')
    expect(old.body).toBe('Office Mac mini runs WatchMyMedia 1.7.0, and this TV needs 1.8 or newer. Update WatchMyMedia on the server, then try again.')
    expect(old.note).toContain('stay as they are')
    expect(startProblemWords('tooOld', { ...server, version: undefined }).body).toMatch(/^Office Mac mini runs an older WatchMyMedia/)
    expect(startProblemWords('signedOut', server)).toMatchObject({ retry: 'Sign in again', title: 'This TV was signed out' })
  })
})
