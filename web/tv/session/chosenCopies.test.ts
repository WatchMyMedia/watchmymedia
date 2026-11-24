import { describe, expect, it } from 'vitest'
import { ChosenCopies, MAX_CHOICES, titleKey } from './chosenCopies'
import { memoryStorage } from './testServers'

describe('the copy Play remembers for a title', () => {
  it('keys a movie by its TMDB id and an episode by its show’s, season and number', () => {
    expect(titleKey({ kind: 'movie', tmdbId: 50_102 })).toBe('movie:50102')
    expect(titleKey({ kind: 'episode', showTmdbId: 60_201, season: 2, episode: 4 })).toBe('episode:60201:2:4')
  })

  it('keeps a choice on this TV, across runs, and replaces it when chosen again', () => {
    const storage = memoryStorage()
    new ChosenCopies(storage).set('movie:1', { server: 'cabin', file: 11 })
    const again = new ChosenCopies(storage)
    expect(again.get('movie:1')).toEqual({ server: 'cabin', file: 11 })
    again.set('movie:1', { server: 'home', file: 3 })
    expect(new ChosenCopies(storage).get('movie:1')).toEqual({ server: 'home', file: 3 })
    expect(again.get('movie:2')).toBeUndefined()
  })

  it('forgets the oldest past its limit, and reads a broken store as empty', () => {
    const storage = memoryStorage()
    const store = new ChosenCopies(storage)
    for (let i = 0; i <= MAX_CHOICES; i++) store.set(`movie:${i}`, { server: 's', file: i })
    expect(store.get('movie:0')).toBeUndefined()
    expect(store.get(`movie:${MAX_CHOICES}`)).toEqual({ server: 's', file: MAX_CHOICES })
    storage.setItem('watchmymedia.tizen.copies.v1', 'not json')
    expect(new ChosenCopies(storage).get('movie:1')).toBeUndefined()
  })
})
