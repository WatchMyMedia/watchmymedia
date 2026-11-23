import { describe, expect, it } from 'vitest'
import { continueWords, movieLine, peekFacts, qualityWords, scoreWords, showStatusWords } from './cardFacts'

describe('card facts', () => {
  it('says a movie’s year and length', () => {
    expect(movieLine({ year: 2025, runtimeMin: 118 })).toBe('2025 · 1 h 58 min')
    expect(movieLine({ year: 2025 })).toBe('2025')
    expect(movieLine({})).toBe('')
  })

  it('words quality and score', () => {
    expect(qualityWords('4K', true)).toBe('4K HDR')
    expect(qualityWords('1080p', false)).toBe('1080p')
    expect(qualityWords(undefined, false)).toBe('')
    expect(scoreWords(7.25)).toBe('★ 7.3')
    expect(scoreWords(0)).toBe('')
  })

  it('says where a show stands', () => {
    const now = Date.parse('2026-11-23T12:00:00Z')
    expect(showStatusWords({ status: 'Returning Series', nextAirDate: '2026-12-01' }, now)).toMatch(/^Next episode Dec 1$/)
    expect(showStatusWords({ status: 'Returning Series', nextAirDate: '2026-10-01' }, now)).toBe('Returning')
    expect(showStatusWords({ status: 'Ended' }, now)).toBe('Ended')
    expect(showStatusWords({}, now)).toBe('')
  })

  it('lists the hover card’s facts', () => {
    expect(peekFacts({ kind: 'movie', year: 2025, runtimeMin: 118, contentRating: 'PG-13', rating: 7.2 })).toEqual(['2025', '1 h 58 min', 'PG-13', '★ 7.2'])
    expect(peekFacts({ kind: 'show', year: 2020, episodeCount: 1 })).toEqual(['2020', '1 episode'])
  })

  it('words a Continue watching episode and movie', () => {
    const ep = continueWords({ kind: 'episode', title: 'Harbor Lights', subtitle: 'S03E07 The Long Dark', seasonNumber: 3, episodeNumber: 7, positionMs: 10 * 60_000, durationMs: 39 * 60_000 })
    expect(ep).toEqual({
      eyebrow: 'Harbor Lights · Season 3 · Episode 7',
      heading: 'The Long Dark',
      title: 'Harbor Lights',
      line: 'S3 E7 · The Long Dark',
      left: '29 min left',
      label: 'Harbor Lights, season 3, episode 7, The Long Dark',
    })
    const untitled = continueWords({ kind: 'episode', title: 'Saltmarsh', subtitle: 'S01E09 ', seasonNumber: 1, episodeNumber: 9, positionMs: 0, durationMs: 0 })
    expect(untitled.heading).toBe('Saltmarsh')
    expect(untitled.line).toBe('S1 E9')
    const movie = continueWords({ kind: 'movie', title: 'Dead Reckoning Bay', subtitle: '2026', positionMs: 0, durationMs: 60_000 })
    expect(movie.eyebrow).toBe('Movie · 2026')
    expect(movie.line).toBe('Movie · 2026')
  })
})
