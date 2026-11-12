import { describe, expect, it } from 'vitest'
import { autoRows, episodeLink, itemLink, type PlayAuto, type PlayItem } from './activity'

const episode: PlayItem = { type: 'episode', title: 'Double Shift', showTitle: 'Night Shift Diner', showId: 5, seasonNumber: 2, episodeNumber: 2, episodeId: 77 }

describe('activity links', () => {
  it('sends an episode’s line to its own page and its show’s name to the show', () => {
    expect(episodeLink(episode)).toEqual({ to: '/shows/$showId/seasons/$number/episodes/$episodeId', params: { showId: '5', number: '2', episodeId: '77' } })
    expect(itemLink(episode)).toEqual({ to: '/shows/$showId', params: { showId: '5' } })
  })

  it('keeps specials, season 0', () => {
    expect(episodeLink({ ...episode, seasonNumber: 0 })?.params.number).toBe('0')
  })

  it('has no episode page for a movie, an unmatched file or an episode it can’t place', () => {
    expect(episodeLink({ type: 'movie', title: 'Paper Moons', movieId: 3 })).toBeNull()
    expect(episodeLink({ type: 'file', title: 'clip.mkv' })).toBeNull()
    expect(episodeLink({ ...episode, episodeId: undefined })).toBeNull()
    expect(episodeLink({ ...episode, seasonNumber: undefined })).toBeNull()
  })
})

it('labels a recovered quality only after its generation is applied', () => {
  const sw = { at: '2026-11-08T12:00:00Z', from: '720', to: 'original', reason: 'connection recovered', server: false }
  const auto: PlayAuto = { on: true, step: '720', floor: false, switches: [{ ...sw, generation: 3, outcome: 'proposed' }] }
  expect(autoRows(auto)[0][0]).toBe('Proposed')
  expect(autoRows({ ...auto, switches: [{ ...sw, generation: 3, outcome: 'applied' }] })[0][0]).toBe('Raised')
  expect(autoRows({ ...auto, switches: [{ ...sw, generation: 3, outcome: 'failed' }] })[0][0]).toBe('Failed')
})
