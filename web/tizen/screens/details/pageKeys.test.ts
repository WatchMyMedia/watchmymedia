import { describe, expect, it } from 'vitest'
import { stepAlong } from './pageKeys'

describe('the buttons of a title’s page', () => {
  const line = ['play', 'start', 'trailer', 'watchlist', 'more']

  it('go one to the next in order', () => {
    expect(stepAlong(line, 'play', 'right')).toBe('start')
    expect(stepAlong(line, 'watchlist', 'left')).toBe('trailer')
  })

  it('stop at both ends', () => {
    expect(stepAlong(line, 'play', 'left')).toBeUndefined()
    expect(stepAlong(line, 'more', 'right')).toBeUndefined()
    expect(stepAlong(line, 'cast/0', 'left')).toBeUndefined()
  })
})
