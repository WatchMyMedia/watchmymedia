import { describe, expect, it } from 'vitest'
import type { components } from '../../../src/api/schema'
import { approvalNote, chosen, initialPicks, metaLine, requestLabel, seasonChoices, sentWords, standing, toggle } from './requestForm'
import { askedLine, decidable, inboxCounts, reportedLine, seerrUrl, statusLook } from './requestRows'

type Season = components['schemas']['RequestableSeason']
const season = (number: number, state: Season['state'], episodeCount = 10): Season => ({ number, name: number ? `Season ${number}` : '', episodeCount, airDate: '2022-01-01', state })

describe('the request dialog', () => {
  const choices = seasonChoices([season(0, 'missing', 4), season(1, 'requested'), season(2, 'missing'), season(3, 'partial'), season(4, 'unaired')])

  it('locks what is in the library or asked for, and ticks what is missing', () => {
    expect(choices.map((c) => [c.name, c.locked, c.note ?? ''])).toEqual([
      ['Specials', false, ''], ['Season 1', true, 'Requested'], ['Season 2', false, ''], ['Season 3', false, 'Partly in library'], ['Season 4', false, ''],
    ])
    expect(choices[4].detail).toBe('Not aired yet')
    expect(choices[2].detail).toBe('10 episodes · 2022')
    expect(initialPicks(choices)).toEqual([2, 3])
    expect(initialPicks(choices, [1, 4])).toEqual([4])
  })

  it('asks only for ticked seasons that can be asked for', () => {
    expect(chosen(toggle([3, 2], 1), choices)).toEqual([2, 3])
    expect(toggle([2, 3], 3)).toEqual([2])
    expect(requestLabel('tv', 2)).toBe('Request 2 seasons')
    expect(requestLabel('tv', 1)).toBe('Request 1 season')
    expect(requestLabel('tv', 0)).toBe('Request')
    expect(requestLabel('movie', 1)).toBe('Request movie')
  })

  it('says who approves, and what happened once asked', () => {
    expect(metaLine({ mediaType: 'tv', year: 2021, seasons: [season(1, 'missing'), season(2, 'missing')] })).toBe('2021 · 2 seasons · TV show')
    expect(approvalNote({ needsApproval: false, backend: 'arr' })).toBeUndefined()
    expect(approvalNote({ needsApproval: true, backend: 'arr' })).toBe('An admin approves requests before anything downloads.')
    expect(sentWords({ created: true, request: { mine: true, requestedBy: 'Sam' } }, 'seerr').title).toBe('Sent to Seerr')
    expect(sentWords({ created: false, request: { mine: false, requestedBy: 'Alex' } }, 'arr').title).toBe('Alex asked for this already')
    expect(standing({ mediaType: 'movie', state: 'available' })).toBe('inLibrary')
    expect(standing({ mediaType: 'movie', state: 'downloading' })).toBe('asked')
    expect(standing({ mediaType: 'tv', state: 'available' })).toBe('ask')
  })
})

describe('Your requests and the Inbox', () => {
  const at = new Date(Date.now() - 2 * 3_600_000).toISOString()

  it('draws each state in its colour, with the download’s bar', () => {
    expect(statusLook({ status: 'pending' })).toEqual({ tone: 'plain', icon: 'clock' })
    expect(statusLook({ status: 'failed' }).tone).toBe('warn')
    expect(statusLook({ status: 'approved', download: { state: 'downloading', progress: .45 } })).toMatchObject({ tone: 'accent', progress: .45 })
  })

  it('says who asked for what and who reported what', () => {
    expect(askedLine({ requestedBy: 'Alex', mediaType: 'tv', seasons: [2], title: 'Harborwick', createdAt: at })).toEqual({ who: 'Alex', what: 'Season 2', title: 'Harborwick', ago: '2 h ago' })
    expect(askedLine({ requestedBy: 'Alex', mediaType: 'movie', seasons: [], title: 'X', createdAt: at }).what).toBeUndefined()
    expect(reportedLine({ reportedBy: 'Robin', kind: 'video', title: 'Harbor Lights', createdAt: at }).what).toBe('a video problem')
    expect(decidable({ status: 'failed' })).toBe(true)
    expect(decidable({ status: 'approved' })).toBe(false)
    expect(inboxCounts({ requests: [{} as never], problems: [] })).toBe('1 request · 0 problems')
  })

  it('opens only web addresses for Seerr', () => {
    expect(seerrUrl('https://seerr.example.net/requests')).toBe('https://seerr.example.net/requests')
    expect(seerrUrl('javascript:alert(1)')).toBeUndefined()
    expect(seerrUrl(undefined)).toBeUndefined()
  })
})
