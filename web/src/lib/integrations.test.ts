import { describe, expect, it } from 'vitest'
import { cleanMappings, connectionStatus, freshSuggestions, itemWord, suggestSeerrUser, tileLetters, unmappedRoots } from './integrations'

const base = {
  id: 1,
  kind: 'sonarr' as const,
  name: 'Sonarr',
  url: 'http://nas:8989',
  apiKeyHint: '3f9a',
  pathMap: [],
  targets: [],
  status: { ok: true, items: 3, webhook: { outside: 0 } },
}

describe('connectionStatus', () => {
  it('puts an error first', () => {
    const s = connectionStatus({ ...base, status: { ...base.status, ok: false, error: 'connection refused' } })
    expect(s.tone).toBe('warn')
    expect(s.text).toBe("Couldn't reach Sonarr: connection refused")
  })
  it('says when the webhook last called', () => {
    const at = new Date(Date.now() - 2 * 60_000).toISOString()
    expect(connectionStatus({ ...base, status: { ...base.status, webhook: { outside: 0, lastEventAt: at } } })).toEqual({
      tone: 'ok',
      text: 'Webhook · last event 2 min ago',
    })
  })
  it('says when nothing has arrived', () => {
    expect(connectionStatus(base)).toEqual({ tone: 'off', text: 'Webhook not connected yet' })
  })
})

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
  it('counts items in the app’s words', () => {
    expect(itemWord('radarr', 1)).toBe('1 movie')
    expect(itemWord('radarr', 0)).toBe('0 movies')
    expect(itemWord('sonarr', 4)).toBe('4 series')
  })
})

describe('Seerr', () => {
  it('says how many users it has rather than a webhook', () => {
    expect(connectionStatus({ ...base, kind: 'seerr', name: 'Seerr', status: { ...base.status, items: 4 } })).toEqual({
      tone: 'ok',
      text: 'Takes requests · 4 users',
    })
    expect(tileLetters('seerr')).toBe('Se')
    expect(tileLetters('sonarr')).toBe('S')
  })

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
