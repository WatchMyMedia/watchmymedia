import { describe, expect, it } from 'vitest'
import type { components } from '../../../src/api/schema'
import { relatedPosterRows, RELEASE_ORDER, titleCard } from './relatedCards'

type BrowseItem = components['schemas']['BrowseItem']

const item = (over: Partial<BrowseItem> = {}): BrowseItem => ({
  kind: 'movie', id: 120, libraryId: 1, title: 'Dead Reckoning', overview: '', genres: ['Thriller', 'Crime'], hdr: false, addedAt: '', isNew: false, totalSize: 0, watched: false, year: 2017, ...over,
})

describe('related rows', () => {
  it('draws the poster rows with cards, the collection with "In release order"', () => {
    const rows = relatedPosterRows({ rows: [
      { id: 'collection', style: 'poster', title: 'Dead Reckoning Collection', items: [item()], seeAll: { kind: 'library', libraryId: 1 } },
      { id: 'similar', style: 'poster', title: 'More like this', items: [item({ id: 121 })] },
      { id: 'empty', style: 'poster', title: 'Nothing', items: [] },
      { id: 'future', style: 'carousel', title: 'From a newer server', items: [item()] },
    ] })
    expect(rows.map((r) => [r.id, r.subtitle])).toEqual([['collection', RELEASE_ORDER], ['similar', undefined]])
    expect(relatedPosterRows(undefined)).toEqual([])
  })

  it('makes a card that opens the title, with New, the watched tick and progress', () => {
    expect(titleCard(item({ isNew: true }))).toMatchObject({ key: 'movie120', subtitle: '2017 · Thriller', badges: [{ label: 'New', tone: 'gold' }], route: { name: 'movie', id: 120 } })
    expect(titleCard(item({ isNew: true, watched: true }))).toMatchObject({ badges: [{ label: '✓', tone: 'gold' }], label: 'Dead Reckoning, 2017 · Thriller, watched' })
    expect(titleCard(item({ progress: 0.4 }))).toMatchObject({ progress: 0.4, label: 'Dead Reckoning, 2017 · Thriller, partly watched' })
    expect(titleCard(item({ kind: 'show', id: 205 })).route).toEqual({ name: 'show', id: 205 })
  })
})
