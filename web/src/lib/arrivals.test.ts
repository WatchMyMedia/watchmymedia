import { describe, expect, it } from 'vitest'
import { arrivedRequests, arrivedWords, parseSeen, seenAfter } from './arrivals'
import type { components } from '../api/schema'

type MediaRequest = components['schemas']['MediaRequest']

const now = Date.parse('2026-11-23T20:00:00Z')
const req = (id: number, over: Partial<MediaRequest> = {}): MediaRequest => ({
  id,
  mediaType: 'movie',
  tmdbId: id,
  title: `Title ${id}`,
  status: 'available',
  mine: true,
  createdAt: '2026-11-20T10:00:00Z',
  requestedBy: 'robin',
  ...over,
}) as MediaRequest

describe('arrived requests', () => {
  it('keeps the person’s own available requests from the last month not yet seen, newest first', () => {
    const items = [
      req(1),
      req(2, { createdAt: '2026-11-22T10:00:00Z' }),
      req(3, { status: 'pending' }),
      req(4, { mine: false }),
      req(5, { createdAt: '2026-09-01T10:00:00Z' }),
      req(6),
    ]
    expect(arrivedRequests(items, new Set([6]), now).map((r) => r.id)).toEqual([2, 1])
  })

  it('words the count', () => {
    expect(arrivedWords(0)).toBeUndefined()
    expect(arrivedWords(2)).toBe('2 arrived')
  })

  it('remembers seen ids, newest last, and reads back only numbers', () => {
    expect(seenAfter([1, 2, 3], [2, 4])).toEqual([1, 3, 2, 4])
    expect(seenAfter([1, 2, 3], [4], 2)).toEqual([3, 4])
    expect(parseSeen('[1,"x",3]')).toEqual([1, 3])
    expect(parseSeen('nope')).toEqual([])
    expect(parseSeen(null)).toEqual([])
  })
})
