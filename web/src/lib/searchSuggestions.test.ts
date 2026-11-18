import { describe, expect, it } from 'vitest'
import type { components } from '../api/schema'
import { matchRank, moveActive, suggestions } from './searchSuggestions'

type Movie = components['schemas']['Movie']
type Show = components['schemas']['Show']
type SearchEpisode = components['schemas']['SearchEpisode']

const movie = (id: number, title: string, year?: number): Movie => ({
  id, title, year, credits: [], fileCount: 1, genres: [], libraryId: 1, overview: '', tmdbId: id,
})
const show = (id: number, title: string, year?: number): Show => ({
  id, title, year, credits: [], episodeCount: 10, genres: [], libraryId: 2, overview: '', tmdbId: id,
})
const episode = (id: number, title: string, over: Partial<SearchEpisode> = {}): SearchEpisode => ({
  id, title, showId: 9, showTitle: 'A Show', seasonNumber: 1, number: id, fileId: id, ...over,
})

describe('matchRank', () => {
  it('ranks the whole title, then its start, then a word, then inside a word', () => {
    expect(matchRank('Up', 'up')).toBe(0)
    expect(matchRank('Upgrade', 'up')).toBe(1)
    expect(matchRank('Grown Ups', 'up')).toBe(2)
    expect(matchRank('Cupcake Wars', 'up')).toBe(3)
  })

  it('counts a word after punctuation as a word start', () => {
    expect(matchRank('Spider-Man: Homecoming', 'man')).toBe(2)
    expect(matchRank('Spider-Man: Homecoming', 'home')).toBe(2)
  })

  it('finds a later word start after an earlier match inside a word', () => {
    expect(matchRank('Batman: Man of Steel', 'man')).toBe(2)
  })

  it('ignores case, accents and surrounding spaces', () => {
    expect(matchRank('Amélie', '  AME ')).toBe(1)
    expect(matchRank('Pokémon', 'pokemon')).toBe(0)
  })

  it('puts titles the server matched by their original title last', () => {
    expect(matchRank('Spirited Away', 'chihiro')).toBe(4)
  })
})

describe('suggestions', () => {
  it('merges movies and shows by how well they match, then by title', () => {
    const rows = suggestions(
      {
        movies: [movie(1, 'Cupcake'), movie(2, 'Grown Ups'), movie(3, 'Up')],
        shows: [show(4, 'Upload'), show(5, 'Start Up')],
        episodes: [],
      },
      'up',
    )
    expect(rows.map((s) => s.title)).toEqual(['Up', 'Upload', 'Grown Ups', 'Start Up', 'Cupcake'])
    expect(rows.map((s) => s.kind)).toEqual(['movie', 'show', 'movie', 'show', 'movie'])
  })

  it('keeps five titles and three episodes, titles first', () => {
    const rows = suggestions(
      {
        movies: Array.from({ length: 6 }, (_, i) => movie(i + 1, `Star ${i + 1}`)),
        shows: [show(10, 'Stargate')],
        episodes: [episode(1, 'Starlight'), episode(2, 'The Stars'), episode(3, 'Star'), episode(4, 'Mustard')],
      },
      'star',
    )
    expect(rows.filter((s) => s.kind !== 'episode')).toHaveLength(5)
    expect(rows.slice(5).map((s) => s.title)).toEqual(['Star', 'Starlight', 'The Stars'])
  })

  it("uses an episode's still, or its show's poster when it has none", () => {
    const [a, b] = suggestions(
      { movies: [], shows: [], episodes: [episode(1, 'Pilot', { stillPath: '/still.jpg', showPosterPath: '/show.jpg' }), episode(2, 'Pilot II', { showPosterPath: '/show.jpg' })] },
      'pilot',
    )
    expect(a).toMatchObject({ kind: 'episode', picturePath: '/still.jpg' })
    expect(b).toMatchObject({ kind: 'episode', picturePath: '/show.jpg' })
  })

  it('has no rows when nothing matched', () => {
    expect(suggestions({ movies: [], shows: [], episodes: [] }, 'zz')).toEqual([])
  })
})

describe('moveActive', () => {
  it('moves down from the text to the first row and back up', () => {
    expect(moveActive(-1, 3, 1)).toBe(0)
    expect(moveActive(0, 3, -1)).toBe(-1)
  })

  it('goes from the last row back to the text, and up from the text to the last row', () => {
    expect(moveActive(2, 3, 1)).toBe(-1)
    expect(moveActive(-1, 3, -1)).toBe(2)
  })

  it('stays on the text with no rows', () => {
    expect(moveActive(-1, 0, 1)).toBe(-1)
    expect(moveActive(-1, 0, -1)).toBe(-1)
  })
})
