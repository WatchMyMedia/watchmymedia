import { describe, expect, it } from 'vitest'
import { addRecent, readRecent, recentScope, rememberSearch } from './recentSearches'

function memory(): Pick<Storage, 'getItem' | 'setItem'> {
  const kept = new Map<string, string>()
  return { getItem: (k) => kept.get(k) ?? null, setItem: (k, v) => { kept.set(k, v) } }
}

describe('Search again', () => {
  it('keeps the last three searches, newest first, each once', () => {
    expect(addRecent([], '  harbor  lights ')).toEqual(['harbor lights'])
    expect(addRecent(['fathoms', 'bake-off', 'harbor'], 'Harbor')).toEqual(['Harbor', 'fathoms', 'bake-off'])
    expect(addRecent(['c', 'b', 'a'], 'd')).toEqual(['d', 'c', 'b'])
    expect(addRecent(['a'], '   ')).toEqual(['a'])
  })

  it('keeps each person’s searches apart, on this TV', () => {
    const storage = memory()
    const sam = recentScope('lakeside', 1), robin = recentScope('lakeside', 2)
    rememberSearch(storage, sam, 'harbor')
    rememberSearch(storage, sam, 'fathoms')
    rememberSearch(storage, robin, 'bake-off')
    expect(readRecent(storage, sam)).toEqual(['fathoms', 'harbor'])
    expect(readRecent(storage, robin)).toEqual(['bake-off'])
    expect(readRecent(undefined, sam)).toEqual([])
    expect(readRecent({ getItem: () => '{not json' }, sam)).toEqual([])
  })
})
