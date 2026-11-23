import { describe, expect, it } from 'vitest'
import type { components } from './schema'
import { continueTarget, withoutContinueItem } from './continueWatching'

type HomeData = components['schemas']['HomeData']
type ContinueItem = components['schemas']['ContinueItem']

const movie: ContinueItem = { kind: 'movie', title: 'Low Water', fileId: 11, movieId: 1, positionMs: 1, durationMs: 2, updatedAt: '2026-11-20T00:00:00Z' }
const episode: ContinueItem = { kind: 'episode', title: 'Harbor Lights', fileId: 22, showId: 5, episodeId: 7, seasonNumber: 2, episodeNumber: 4, positionMs: 1, durationMs: 2, updatedAt: '2026-11-21T00:00:00Z' }
const home = { continueWatching: [movie, episode], rows: [{ id: 'continue', style: 'continue', title: 'Continue watching', continue: [movie, episode] }, { id: 'added', style: 'poster', title: 'Recently added', items: [] }] } as unknown as HomeData

describe('Continue watching’s hidden cards', () => {
  it('names a movie by its id and an episode by its own', () => {
    expect(continueTarget(movie)).toEqual({ type: 'movie', id: 1 })
    expect(continueTarget(episode)).toEqual({ type: 'episode', id: 7 })
    expect(continueTarget({ kind: 'episode', showId: 5 } as ContinueItem)).toBeUndefined()
  })

  it('takes a card out of Home’s list and its arranged row, and leaves the other rows', () => {
    const next = withoutContinueItem(home, { type: 'episode', id: 7 })
    expect(next.continueWatching).toEqual([movie])
    expect(next.rows?.[0].continue).toEqual([movie])
    expect(next.rows?.[1]).toBe(home.rows?.[1])
  })
})
