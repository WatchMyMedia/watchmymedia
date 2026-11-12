import { describe, expect, it } from 'vitest'
import { nearest } from './remote'

const origin = { left: 100, top: 100, width: 100, height: 60 }
describe('remote focus geometry', () => {
  it('prefers the same row rather than a closer diagonal control', () => {
    expect(nearest(origin, [origin, { ...origin, left: 400 }, { ...origin, left: 205, top: 170 }], 'right')).toBe(1)
  })
  it('moves into an overlapping control in the next row and does not wrap at edges', () => {
    const boxes = [origin, { ...origin, top: 200 }, { ...origin, left: 400, top: 200 }]
    expect(nearest(origin, boxes, 'down')).toBe(1)
    expect(nearest(origin, boxes, 'left')).toBe(-1)
    expect(nearest(origin, boxes, 'up')).toBe(-1)
  })
})
