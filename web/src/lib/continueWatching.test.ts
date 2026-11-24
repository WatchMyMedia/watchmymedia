import { describe, expect, it } from 'vitest'
import { continueTarget, hiddenWords, withoutContinue } from './continueWatching'

const movie = { kind: 'movie' as const, movieId: 3, fileId: 9, title: 'Copper Valley', positionMs: 1, durationMs: 2, updatedAt: '' }
const episode = { kind: 'episode' as const, episodeId: 2, showId: 1, fileId: 10, title: 'Night Shift Diner', positionMs: 1, durationMs: 2, updatedAt: '' }

describe('continueTarget', () => {
  it('is the movie or the episode', () => {
    expect(continueTarget(movie)).toEqual({ type: 'movie', id: 3 })
    expect(continueTarget(episode)).toEqual({ type: 'episode', id: 2 })
  })
  it('is nothing without an id', () => {
    expect(continueTarget({ kind: 'movie' })).toBeUndefined()
    expect(continueTarget({ kind: 'episode', movieId: 3 })).toBeUndefined()
  })
})

describe('withoutContinue', () => {
  it('drops the item from every Continue watching row and leaves other rows alone', () => {
    const other: { id: string; style: string; continue?: (typeof movie | typeof episode)[] } = { id: 'recent', style: 'poster' }
    const home = { rows: [{ id: 'continue', style: 'continue', continue: [episode, movie] }, other], totals: {} }
    const out = withoutContinue(home, { type: 'movie', id: 3 })
    expect(out.rows[0].continue).toEqual([episode])
    expect(out.rows[1]).toBe(other)
    expect(out.totals).toBe(home.totals)
  })
  it('drops it from the fixed list older replies carry', () => {
    const home = { continueWatching: [episode, movie] }
    expect(withoutContinue(home, { type: 'episode', id: 2 }).continueWatching).toEqual([movie])
  })
  it('returns the same reply when the item is not in it', () => {
    const home = { rows: [{ id: 'continue', continue: [episode] }] }
    expect(withoutContinue(home, { type: 'movie', id: 3 })).toBe(home)
    expect(withoutContinue(undefined, { type: 'movie', id: 3 })).toBeUndefined()
  })
  it('tells a movie from an episode with the same id', () => {
    const home = { rows: [{ id: 'continue', continue: [{ ...episode, episodeId: 3 }, movie] }] }
    expect(withoutContinue(home, { type: 'episode', id: 3 }).rows[0].continue).toEqual([movie])
  })
})

it('hiddenWords says the place is kept', () => {
  expect(hiddenWords('Copper Valley')).toBe('Copper Valley is off Continue watching. Your place is kept.')
})
