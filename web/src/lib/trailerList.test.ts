import { describe, expect, it } from 'vitest'
import { otherTrailers, trailerKindWords, trailerLine } from './trailerList'

describe('the trailer page’s list', () => {
  const trailers = [
    { source: 'youtube', key: 'abcdefgh1', name: 'Official Trailer', kind: 'trailer' },
    { source: 'file', fileId: 9, name: 'Teaser', durationMs: 62_000, kind: 'trailer' },
    { source: 'youtube', key: 'bad key!', name: 'Broken' },
    { source: 'vimeo', key: 'x', name: 'Elsewhere' },
    { source: 'youtube', key: 'abcdefgh2', name: 'First look', kind: 'teaser' },
  ]

  it('lists what can play, in order, marking the one playing', () => {
    const { items, position } = otherTrailers(trailers, 'abcdefgh2')
    expect(items.map((i) => [i.kind, i.name])).toEqual([
      ['youtube', 'Official Trailer'],
      ['file', 'Teaser'],
      ['youtube', 'First look'],
    ])
    expect(items[2]).toMatchObject({ kind: 'youtube', index: 1, current: true })
    expect(items[0]).toMatchObject({ index: 0, current: false })
    expect(position).toBe('Trailer 3 of 3')
  })

  it('says no position for a single trailer, or when nothing plays', () => {
    expect(otherTrailers([trailers[0]], 'abcdefgh1').position).toBeUndefined()
    expect(otherTrailers(trailers, undefined).position).toBeUndefined()
    expect(otherTrailers(undefined, undefined).items).toEqual([])
  })
})

describe('a listed trailer’s line', () => {
  it('names its kind and where it plays from', () => {
    const { items } = otherTrailers(
      [
        { source: 'youtube', key: 'abcdefgh1', name: 'Official Trailer', kind: 'trailer' },
        { source: 'youtube', key: 'abcdefgh2', name: 'First look', kind: 'teaser' },
        { source: 'file', fileId: 9, name: 'Trailer', kind: 'trailer' },
        { source: 'youtube', key: 'abcdefgh3', name: 'Old one' },
      ],
      'abcdefgh1',
    )
    expect(items.map(trailerLine)).toEqual(['Playing now', 'Teaser · From YouTube', 'Trailer · In your library', 'From YouTube'])
    expect(trailerKindWords('clip')).toBe('')
  })
})
