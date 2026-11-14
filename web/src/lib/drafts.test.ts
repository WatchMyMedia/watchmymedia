import { describe, expect, it } from 'vitest'
import { changedFields, sameValue, shouldAskToLeave } from './drafts'

describe('drafts', () => {
  it('compares plain data deeply and ignores undefined fields', () => {
    expect(sameValue({ a: [1, { b: 'x' }] }, { a: [1, { b: 'x' }] })).toBe(true)
    expect(sameValue({ a: 1, b: undefined }, { a: 1 })).toBe(true)
    expect(sameValue([1, 2], [2, 1])).toBe(false)
    expect(sameValue({ a: 1 }, [1])).toBe(false)
    expect(sameValue(null, {})).toBe(false)
  })
  it('sends only what changed', () => {
    expect(changedFields({ a: 1, b: ['x'], c: 'k' }, { a: 1, b: ['x', 'y'], c: 'k' })).toEqual({ b: ['x', 'y'] })
  })
  it('asks before leaving the address with something unsaved, never otherwise', () => {
    expect(shouldAskToLeave(true, '/settings/plex/link/people', '/settings/plex')).toBe(true)
    expect(shouldAskToLeave(true, '/settings/plex/link/people', '/settings/plex/link/people/')).toBe(false)
    expect(shouldAskToLeave(false, '/a', '/b')).toBe(false)
  })
})
