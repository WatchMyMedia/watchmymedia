import { describe, expect, it } from 'vitest'
import { actionFailed, decisionNote, failedLine, inboxWaiting, requestApp } from './inboxSettings'

const words = (e: unknown) => (e instanceof Error ? `${e.message}.` : 'Something went wrong.')

describe('Inbox', () => {
  it('counts waiting requests and open problems', () => {
    const inbox = {
      requests: [{ status: 'pending' }, { status: 'failed' }, { status: 'approved' }],
      problems: [{ id: 1 }],
    } as Parameters<typeof inboxWaiting>[0]
    expect(inboxWaiting(inbox)).toBe(3)
    expect(inboxWaiting(undefined)).toBe(0)
  })

  it('sends movies to Radarr and shows to Sonarr', () => {
    expect(requestApp({ mediaType: 'movie' })).toBe('Radarr')
    expect(requestApp({ mediaType: 'tv' })).toBe('Sonarr')
  })

  it('quotes the app when it refused', () => {
    expect(failedLine({ mediaType: 'tv', note: 'Root folder /data/tv does not exist.' })).toBe('Sonarr said: “Root folder /data/tv does not exist”. Fix it in Sonarr, or change where requests go, then retry.')
    expect(failedLine({ mediaType: 'movie' })).toContain('Radarr didn’t take it.')
  })

  it('reports Approve and Retry in their row', () => {
    expect(decisionNote({ status: 'approved', mediaType: 'tv', requestedBy: 'Casey' })).toEqual({ tone: 'ok', text: '✓ Approved just now. Sonarr is searching. Casey has been told.' })
    expect(decisionNote({ status: 'failed', mediaType: 'movie', requestedBy: 'Alex', note: 'Movie already exists' }).tone).toBe('err')
  })

  it('says nothing changed when the server refused or didn’t answer', () => {
    expect(actionFailed('resolve', new TypeError('Failed to fetch'), words)).toEqual({ title: 'Couldn’t mark it resolved', body: 'The server didn’t answer, so it’s still open. Try again.' })
    expect(actionFailed('approve', new Error('Request not found'), words)).toEqual({ title: 'Couldn’t approve it', body: 'Request not found, so it’s still waiting. Try again.' })
  })
})
