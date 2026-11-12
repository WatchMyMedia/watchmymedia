import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { components } from '../api/schema'
import { episodeLinks, posterLinks, type CardTarget } from './cardLinks'

type BrowseItem = components['schemas']['BrowseItem']
type EpisodeCard = components['schemas']['EpisodeCard']

const item = (over: Partial<BrowseItem> = {}): BrowseItem => ({
  id: 12, kind: 'show', title: 'Harbor Lights', libraryId: 3, addedAt: '2026-11-08T09:00:00Z', genres: [], hdr: false, isNew: true, overview: '', totalSize: 0, watched: false, ...over,
})
const show: CardTarget = { to: '/shows/$showId', params: { showId: '12' } }
const season3: CardTarget = { to: '/shows/$showId/seasons/$number', params: { showId: '12', number: '3' } }
const at77: CardTarget = { to: '/shows/$showId/seasons/$number/episodes/$episodeId', params: { showId: '12', number: '3', episodeId: '77' } }

describe('a Recently added show card', () => {
  it('links the season and the episode of a one-episode card, and opens the episode from the picture', () => {
    const links = posterLinks(item({ subtitle: 'S03E07', episodeId: 77, seasonNumber: 3 }), 'added today')
    expect(links.title).toEqual({ text: 'Harbor Lights', to: show, label: 'Harbor Lights' })
    expect(links.picture).toEqual({ to: at77, label: 'Harbor Lights, season 3, episode 7' })
    expect(links.line).toEqual([
      { text: 'S03', to: season3, label: 'Harbor Lights, season 3' },
      { text: ' · ' },
      { text: 'E07', to: at77, label: 'Harbor Lights, season 3, episode 7' },
      { text: ' · ' },
      { text: 'added today' },
    ])
    expect(links.third).toBeUndefined()
  })

  it('names Specials rather than season 0', () => {
    const links = posterLinks(item({ subtitle: 'S00E02', episodeId: 5, seasonNumber: 0 }), 'added yesterday')
    expect(links.picture.label).toBe('Harbor Lights, Specials, episode 2')
    expect(links.line[0]).toEqual({ text: 'S00', to: { to: '/shows/$showId/seasons/$number', params: { showId: '12', number: '0' } }, label: 'Harbor Lights, Specials' })
  })

  it('links a whole season from its name and its picture', () => {
    const links = posterLinks(item({ subtitle: 'Season 1' }), 'added yesterday')
    const season1: CardTarget = { to: '/shows/$showId/seasons/$number', params: { showId: '12', number: '1' } }
    expect(links.picture).toEqual({ to: season1, label: 'Harbor Lights, season 1' })
    expect(links.line).toEqual([{ text: 'Season 1', to: season1, label: 'Harbor Lights, season 1' }, { text: ' · ' }, { text: 'added yesterday' }])
  })

  it('links only the show for the complete series or a count of episodes', () => {
    for (const subtitle of ['Complete series', '6 episodes']) {
      const links = posterLinks(item({ subtitle }), 'added 3 days ago')
      expect(links.picture).toEqual({ to: show, label: 'Harbor Lights' })
      expect(links.line).toEqual([{ text: subtitle }, { text: ' · ' }, { text: 'added 3 days ago' }])
    }
  })

  it('keeps an episode code without its ids, as an older reply would send, to the show', () => {
    const links = posterLinks(item({ subtitle: 'S03E07' }), 'added today')
    expect(links.picture.to).toEqual(show)
    expect(links.line).toEqual([{ text: 'S03E07' }, { text: ' · ' }, { text: 'added today' }])
  })
})

describe('other poster cards', () => {
  it('gives a movie one destination and a plain line', () => {
    const movie: CardTarget = { to: '/movies/$movieId', params: { movieId: '5' } }
    const links = posterLinks(item({ id: 5, kind: 'movie', title: 'Dead Reckoning Bay' }), 'added today')
    expect(links.picture).toEqual({ to: movie, label: 'Dead Reckoning Bay' })
    expect(links.title).toEqual({ text: 'Dead Reckoning Bay', to: movie, label: 'Dead Reckoning Bay' })
    expect(links.line).toEqual([{ text: 'added today' }])
  })

  it('falls back to the year and count in a grid, where no tail is given', () => {
    expect(posterLinks(item({ year: 2022, episodeCount: 27 })).line).toEqual([{ text: '2022 · 27 ep' }])
    expect(posterLinks(item({ kind: 'movie', year: 2021, fileCount: 2 })).line).toEqual([{ text: '2021 · 2 versions' }])
    expect(posterLinks(item({ kind: 'movie' })).line).toEqual([])
  })

  it('uses a list’s own line for the show as it is', () => {
    const links = posterLinks(item({ year: 2022 }), '2022')
    expect(links.picture.to).toEqual(show)
    expect(links.line).toEqual([{ text: '2022' }])
  })
})

describe('a Recently aired card', () => {
  // An air date is a day, read as UTC midnight, and relativeTime rounds
  // hours to days, so the day before reads "yesterday" only for the first
  // half of today in UTC. Pin the clock in that half.
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-11-08T03:00:00Z'))
  })
  afterEach(() => {
    vi.useRealTimers()
  })
  const ep = (over: Partial<EpisodeCard> = {}): EpisodeCard => ({
    episodeId: 77, episodeNumber: 7, seasonNumber: 3, showId: 12, showTitle: 'Harbor Lights', title: 'The Long Dark', hasFile: true, isNew: true, overview: '',
    airDate: '2026-11-07', ...over,
  })

  it('links the show, the season, and the episode from E07 and the still', () => {
    const links = episodeLinks(ep())
    expect(links.title).toEqual({ text: 'Harbor Lights', to: show, label: 'Harbor Lights' })
    expect(links.picture).toEqual({ to: at77, label: 'Harbor Lights, season 3, episode 7, The Long Dark' })
    expect(links.line).toEqual([
      { text: 'S03', to: season3, label: 'Harbor Lights, season 3' },
      { text: ' · ' },
      { text: 'E07', to: at77, label: 'Harbor Lights, season 3, episode 7, The Long Dark' },
      { text: ' The Long Dark' },
    ])
  })

  it('says when it aired on a third line', () => {
    expect(episodeLinks(ep()).third).toBe('Aired yesterday')
    expect(episodeLinks(ep({ airDate: undefined })).third).toBeUndefined()
  })

  it('leaves out a missing title', () => {
    const links = episodeLinks(ep({ title: '' }))
    expect(links.line).toHaveLength(3)
    expect(links.picture.label).toBe('Harbor Lights, season 3, episode 7')
  })
})
