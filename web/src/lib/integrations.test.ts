import { describe, expect, it } from 'vitest'
import { cleanMappings, freshSuggestions, suggestSeerrUser, unmappedRoots } from './integrations'

describe('mappings', () => {
  it('drops empty rows and trims', () => {
    expect(cleanMappings([{ from: ' /tv ', to: '/Volumes/TV ' }, { from: '', to: '' }])).toEqual([{ from: '/tv', to: '/Volumes/TV' }])
  })
  it('leaves out suggestions already in the list', () => {
    const s = [{ from: '/tv', to: '/Volumes/TV' }, { from: '/anime', to: '/Volumes/Anime' }]
    expect(freshSuggestions(s, [{ from: '/tv', to: '/Volumes/TV' }])).toEqual([{ from: '/anime', to: '/Volumes/Anime' }])
  })
  it('lists root folders outside every library', () => {
    expect(unmappedRoots([{ path: '/tv', localPath: '/Volumes/TV', libraryId: 2, items: 9 }, { path: '/downloads', localPath: '/downloads', items: 0 }])).toEqual([
      { path: '/downloads', localPath: '/downloads', items: 0 },
    ])
  })
})

describe('Seerr', () => {
  const users = [
    { id: 1, name: 'Robin', names: ['robin'], canRequest: true },
    { id: 2, name: 'Sam P', names: ['sam', 'sam@example.test'], canRequest: true },
    { id: 3, name: 'alexk', names: ['alex.k@example.test'], canRequest: true },
    { id: 4, name: 'Jo', names: ['jo'], canRequest: true },
    { id: 5, name: 'jo', names: ['jo2'], canRequest: false },
  ]
  it('suggests the one user whose name agrees', () => {
    expect(suggestSeerrUser('Sam', users, [])?.id).toBe(2)
    expect(suggestSeerrUser(' ROBIN ', users, [])?.id).toBe(1)
  })
  it('matches an email before the @', () => {
    expect(suggestSeerrUser('alex.k', users, [])?.id).toBe(3)
  })
  it('compares accents in either form', () => {
    expect(suggestSeerrUser('Ren\u0065\u0301', [{ id: 9, name: 'Ren\u00e9', names: [], canRequest: true }], [])?.id).toBe(9)
  })
  it('suggests nothing when several agree, none do, or the one is taken', () => {
    expect(suggestSeerrUser('Jo', users, [])).toBeUndefined()
    expect(suggestSeerrUser('Alex', users, [])).toBeUndefined()
    expect(suggestSeerrUser('Sam', users, [2])).toBeUndefined()
    expect(suggestSeerrUser('', users, [])).toBeUndefined()
  })
})
