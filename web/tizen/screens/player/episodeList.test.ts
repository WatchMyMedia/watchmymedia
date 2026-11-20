import { describe, expect, it } from 'vitest'
import type { components } from '../../../src/api/schema'
import { episodeContext, episodeProgress, episodeRowLine, playableEpisodes, playingEpisode, previousEpisode, seasonTabList } from './episodeList'

type Episode = components['schemas']['Episode']
const file = (id: number, durationMs = 52 * 60_000) => ({ id, durationMs }) as Episode['files'][number]
const episode = (number: number, files: number[], o: Partial<Episode> = {}): Episode =>
  ({ id: 100 + number, number, title: `E${number}`, runtimeMin: 52, files: files.map((f) => file(f)), ...o }) as Episode

describe('the Episodes panel', () => {
  it('belongs to the file’s show and season; a film has none', () => {
    expect(episodeContext({ showId: 3, seasonNumber: 2, episodeNumber: 7, episodeId: 9 })).toEqual({ showId: 3, season: 2, episode: 7 })
    expect(episodeContext({ showId: 3, seasonNumber: 0, episodeNumber: 1, episodeId: 9 })).toEqual({ showId: 3, season: 0, episode: 1 })
    expect(episodeContext({ showId: undefined })).toBeUndefined()
    expect(episodeContext(undefined)).toBeUndefined()
  })

  it('tabs the seasons on disk by number, Specials last', () => {
    const tabs = seasonTabList([{ number: 0, availableCount: 2 }, { number: 2, availableCount: 9 }, { number: 1, availableCount: 0 }, { number: 3, availableCount: 4 }], 3)
    expect(tabs).toEqual([{ number: 2, name: 'Season 2' }, { number: 3, name: 'Season 3' }, { number: 0, name: 'Specials' }])
  })

  it('lists what can play, finds the one playing and the one before it', () => {
    const list = playableEpisodes([episode(1, [11]), episode(2, []), episode(3, [13, 31]), episode(4, [14])])
    expect(list.map((e) => e.number)).toEqual([1, 3, 4])
    expect(playingEpisode(list, 31)?.number).toBe(3)
    expect(previousEpisode(list, 14)?.number).toBe(3)
    expect(previousEpisode(list, 11)).toBeUndefined()
    expect(previousEpisode(list, 99)).toBeUndefined()
    expect(playingEpisode(list, undefined)).toBeUndefined()
  })

  it('says how long each is, how far in the playing one is, and which are watched', () => {
    expect(episodeRowLine(episode(7, [1]), true, 14)).toBe('52 min · 14 min in · playing now')
    expect(episodeRowLine(episode(6, [1], { watch: { completed: true, positionMs: 0, durationMs: 0, updatedAt: '' } }), false, 0)).toBe('52 min · watched')
    expect(episodeRowLine(episode(8, [1]), false, 0)).toBe('52 min')
  })

  it('fills a still’s bar for the one playing, and for one watched partway', () => {
    expect(episodeProgress(episode(1, [1]), { positionMs: 13 * 60_000, durationMs: 52 * 60_000 })).toBeCloseTo(.25)
    expect(episodeProgress(episode(1, [1], { watch: { completed: false, positionMs: 26 * 60_000, durationMs: 52 * 60_000, updatedAt: '' } }), undefined)).toBeCloseTo(.5)
    expect(episodeProgress(episode(1, [1]), undefined)).toBeUndefined()
  })
})
