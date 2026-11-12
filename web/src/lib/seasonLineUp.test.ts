import { describe, expect, it } from 'vitest'
import { allSuggested, episodeRange, filesLabel, fitLabel, matchedText, previewSummary, previewTarget, rememberLabel, seasonList, startingRule, targetLabel, type SeasonLineUp, type SeasonSuggestion } from './seasonLineUp'

const show = { tmdbId: 501, title: 'Harbor Lights: Low Tide', year: 2026, seasons: 1 }
const suggestion = (over: Partial<SeasonSuggestion> = {}): SeasonSuggestion => ({
  rule: { fromSeason: 4, fromEpisode: 1, tmdbId: 501, toSeason: 1, toEpisode: 1 },
  show,
  lastSeason: 1,
  lastEpisode: 8,
  found: 8,
  source: 'ownShow',
  detail: 'TMDB lists this season as a show of its own.',
  ...over,
})
const lineUp = (over: Partial<SeasonLineUp> = {}): SeasonLineUp => ({ season: 4, fromEpisode: 1, lastEpisode: 8, fileIds: [1, 2, 3, 4, 5, 6, 7, 8], onTmdb: 0, ...over })

describe('season line-up wording', () => {
  it('names a range of episodes', () => {
    expect(episodeRange(1, 8)).toBe('E01–E08')
    expect(episodeRange(3, 3)).toBe('E03')
  })

  it('counts a season’s files', () => {
    expect(filesLabel(lineUp())).toBe('8 files · E01–E08')
    expect(filesLabel(lineUp({ fileIds: [9], fromEpisode: 8, lastEpisode: 8 }))).toBe('1 file · E08')
  })

  it('says where a season goes', () => {
    expect(targetLabel(suggestion())).toBe('Harbor Lights: Low Tide (2026) · Season 1, E01–E08')
    expect(targetLabel(suggestion({ show: { ...show, year: undefined }, lastEpisode: 1 }))).toBe('Harbor Lights: Low Tide · Season 1, E01')
  })

  it('gives both ends when the files run on into the next season', () => {
    const s = suggestion({ rule: { fromSeason: 6, fromEpisode: 1, tmdbId: 600, toSeason: 3, toEpisode: 9 }, lastSeason: 4, lastEpisode: 2 })
    expect(targetLabel(s)).toBe('Harbor Lights: Low Tide (2026) · Season 3, E09 to Season 4, E02')
  })

  it('counts what lines up', () => {
    expect(fitLabel(8, 8)).toBe('8 of 8 line up')
    expect(fitLabel(1, 1)).toBe('1 of 1 lines up')
    expect(previewSummary(5, 5)).toBe('All 5 files line up')
    expect(previewSummary(4, 5)).toBe('4 of 5 files line up')
    expect(previewSummary(0, 5)).toBe('None of the 5 files line up')
    expect(previewSummary(1, 1)).toBe('The file lines up')
    expect(previewSummary(0, 1)).toBe('The file does not line up')
  })

  it('lists seasons in order', () => {
    expect(seasonList([4])).toBe('Season 4')
    expect(seasonList([7, 6])).toBe('Seasons 6 and 7')
    expect(seasonList([22, 23, 24])).toBe('Seasons 22, 23 and 24')
    expect(rememberLabel([4], 'Harbor Lights')).toBe('Do the same for new Season 4 files in Harbor Lights')
    expect(rememberLabel([6, 7], 'The Night Ferry')).toBe('Do the same for new Season 6 and 7 files in The Night Ferry')
  })
})

describe('season line-up rules', () => {
  it('matches a card in one step only when every season has a suggestion', () => {
    expect(allSuggested([])).toBe(false)
    expect(allSuggested([lineUp({ suggestion: suggestion() })])).toBe(true)
    expect(allSuggested([lineUp({ suggestion: suggestion() }), lineUp({ season: 5 })])).toBe(false)
  })

  it('opens the dialog on the suggestion, or on the folder’s show', () => {
    expect(startingRule(lineUp({ suggestion: suggestion() }), 500)).toEqual(suggestion().rule)
    expect(startingRule(lineUp({ season: 22, fromEpisode: 3 }), 700)).toEqual({ fromSeason: 22, fromEpisode: 3, tmdbId: 700, toSeason: 22, toEpisode: 1 })
  })

  it('says what a file becomes', () => {
    const date = (d: string) => `on ${d}`
    expect(previewTarget({ fileId: 1, to: [{ season: 19, number: 1, title: 'First Day', airDate: '2023-08-08' }] }, date)).toEqual({ text: 'S19 E01 · First Day · on 2023-08-08', ok: true })
    expect(previewTarget({ fileId: 1, to: [{ season: 2, number: 3, title: 'A' }, { season: 2, number: 4, title: 'B' }] }, date)).toEqual({ text: 'S02 E03–E04 · A / B', ok: true })
    expect(previewTarget({ fileId: 1, to: [], reason: 'Past the end of Camp Kettle on TMDB.' }, date)).toEqual({ text: 'Past the end of Camp Kettle on TMDB.', ok: false })
  })

  it('words the banner', () => {
    expect(matchedText(8, 0, ['Harbor Lights: Low Tide (2026)'], [4], 'Harbor Lights')).toBe(
      'Matched 8 files to Harbor Lights: Low Tide (2026). New Season 4 files in Harbor Lights will go there too.',
    )
    expect(matchedText(1, 1, ['A', 'A'], [2])).toBe('Matched 1 file to A. 1 has no episode there and stays unmatched.')
  })
})
