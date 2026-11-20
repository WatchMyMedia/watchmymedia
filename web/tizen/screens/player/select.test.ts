import { describe, expect, it } from 'vitest'
import { selector, shallow2, type Store } from './select'

function store<S>(state: S): Store<S> & { state: S } {
  const s: Store<S> & { state: S } = { state, subscribe: () => () => {}, snapshot: () => s.state }
  return s
}

describe('reading part of the player’s state', () => {
  it('compares two levels deep, as the engine does', () => {
    const options = [1, 2]
    expect(shallow2({ a: 1, b: { options, selected: 2 } }, { a: 1, b: { options, selected: 2 } })).toBe(true)
    expect(shallow2({ b: { options } }, { b: { options: [1, 2] } })).toBe(false)
    expect(shallow2({ a: 1 }, { a: 1, b: undefined })).toBe(true)
    expect(shallow2({ a: 1 }, { a: 2 })).toBe(false)
  })

  it('hands back the same value while what it reads stays the same, and a new one when it changes', () => {
    const s = store({ positionMs: 0, phase: 'playing' })
    const read = selector(s, (x: { phase: string }) => ({ phase: x.phase }))
    const first = read()
    s.state = { positionMs: 250, phase: 'playing' }
    expect(read()).toBe(first)
    s.state = { positionMs: 500, phase: 'paused' }
    expect(read()).toEqual({ phase: 'paused' })
    expect(read()).not.toBe(first)
  })
})
