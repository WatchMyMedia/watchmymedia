import { describe, expect, it } from 'vitest'
import { canCancel, canRequestSeason, missingStatus, requestButtonLabel, requestSource, requestStateLabel, seasonSummary, seasonTabCount, timeLeft, whatLabel } from './requests'

const now = new Date('2026-10-31T12:00:00Z').getTime()
const req = (over = {}) => ({ id: 1, status: 'pending' as const, requestedBy: 'Sam', mine: false, seasons: [2], createdAt: '2026-10-30T12:00:00Z', ...over })

describe('missingStatus', () => {
  it('puts the download first', () => {
    const s = missingStatus(
      { aired: true, download: { state: 'downloading', progress: 0.45, finishesAt: new Date(now + 12 * 60_000).toISOString() }, request: req(), monitored: true },
      now,
    )
    expect(s).toEqual({ label: 'Downloading 45% · 12 min left', tone: 'accent', progress: 0.45, thumb: 'Missing' })
    expect(missingStatus({ aired: true, download: { state: 'importing', progress: 1 } }).label).toBe('Waiting to import')
    expect(missingStatus({ aired: true, download: { state: 'failed', progress: 0.2 } })).toMatchObject({ label: 'Download failed', tone: 'warn' })
  })
  it('then the request', () => {
    expect(missingStatus({ aired: true, request: req() }).label).toBe('Requested by Sam · Waiting for approval')
    expect(missingStatus({ aired: true, request: req({ mine: true }) }).label).toBe('Requested by you · Waiting for approval')
    expect(missingStatus({ aired: true, request: req({ status: 'approved' }) }).label).toBe('Approved · Sonarr is searching')
    // A declined request says nothing about the episode itself.
    expect(missingStatus({ aired: true, request: req({ status: 'declined' }), monitored: false }).label).toBe('Not monitored')
  })
  it('then when it airs and what Sonarr does', () => {
    expect(missingStatus({ aired: false, airDate: '2026-11-04' })).toMatchObject({ label: 'Airs Wed 4 Nov', thumb: 'Not aired yet' })
    expect(missingStatus({ aired: true, monitored: true }).label).toBe('Missing · Sonarr is looking for it')
    expect(missingStatus({ aired: true, monitored: false }).label).toBe('Not monitored')
    expect(missingStatus({ aired: true }).label).toBe('Not in library')
  })
})

describe('seasons', () => {
  it('sums up a season', () => {
    expect(seasonSummary({ episodeCount: 10, availableCount: 8 })).toBe('8 of 10 episodes · 2 missing')
    expect(seasonSummary({ episodeCount: 10, availableCount: 10 })).toBe('10 episodes')
    expect(seasonTabCount({ episodeCount: 10, availableCount: 8 })).toBe('8 of 10')
    expect(seasonTabCount({ episodeCount: 10, availableCount: 0 })).toBe('')
  })
  it('offers a season only when it aired, is missing something and is not asked for', () => {
    const s = { episodeCount: 10, availableCount: 8, airDate: '2024-03-01' }
    expect(canRequestSeason(s, '2026-10-31')).toBe(true)
    expect(canRequestSeason({ ...s, availableCount: 10 }, '2026-10-31')).toBe(false)
    expect(canRequestSeason({ ...s, airDate: '2027-01-01' }, '2026-10-31')).toBe(false)
    expect(canRequestSeason({ ...s, request: req() }, '2026-10-31')).toBe(false)
    expect(canRequestSeason({ ...s, request: req({ status: 'declined' }) }, '2026-10-31')).toBe(true)
  })
})

describe('labels', () => {
  it('says where a request is', () => {
    expect(requestStateLabel({ status: 'declined', note: 'Already on the other server' }).label).toBe('Declined · “Already on the other server”')
    expect(requestStateLabel({ status: 'failed', note: 'Radarr has no root folder for this library' }).label).toBe('Failed · Radarr has no root folder for this library')
    expect(requestStateLabel({ status: 'approved', download: { state: 'downloading', progress: 0.45 } }).label).toBe('Downloading 45%')
    expect(requestStateLabel({ status: 'available' }).label).toBe('In your library')
  })
  it('names what was asked for', () => {
    expect(whatLabel({ mediaType: 'tv', seasons: [2] })).toBe('Season 2')
    expect(whatLabel({ mediaType: 'tv', seasons: [3, 1] })).toBe('Seasons 1 and 3')
    expect(whatLabel({ mediaType: 'tv', seasons: [1, 2, 3, 4] })).toBe('Seasons 1–4')
    expect(whatLabel({ mediaType: 'movie', seasons: [] })).toBe('Movie')
    expect(requestButtonLabel('tv', [2, 3])).toBe('Request 2 seasons')
    expect(requestButtonLabel('tv', [3])).toBe('Request season 3')
  })
  it('counts time left', () => {
    expect(timeLeft(new Date(now + 80 * 60_000).toISOString(), now)).toBe('1 h 20 min left')
    expect(timeLeft(undefined, now)).toBe('')
  })
})

describe('with Seerr', () => {
  it('withdraws only a pending request of your own there', () => {
    expect(canCancel({ mine: true, status: 'pending' }, 'seerr')).toBe(true)
    expect(canCancel({ mine: true, status: 'failed' }, 'seerr')).toBe(false)
    expect(canCancel({ mine: true, status: 'failed' }, 'arr')).toBe(true)
    expect(canCancel({ mine: true, status: 'approved' }, 'arr')).toBe(false)
    expect(canCancel({ mine: false, status: 'pending' }, 'arr')).toBe(false)
  })
  it('says where results come from, as arr for a backend it does not know', () => {
    expect(requestSource('seerr')).toBe('Seerr')
    expect(requestSource('arr')).toBe('TMDB')
    expect(requestSource('something-new')).toBe('TMDB')
  })
})
