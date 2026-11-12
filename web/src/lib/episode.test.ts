import { describe, expect, it } from 'vitest'
import type { components } from '../api/schema'
import { addedEpisode, episodeActions, episodeFacts, episodeLine, isPartlyWatched, pageEpisodes, rowCode, showYears, trackSummary } from './episode'
import { formatDate } from './format'

type Episode = components['schemas']['Episode']
type MediaFile = components['schemas']['MediaFile']
type WatchState = components['schemas']['WatchState']

const file = (id: number, durationMs = 52 * 60_000) => ({ id, durationMs, relativePath: `ep-${id}.mkv`, size: 1 }) as MediaFile
const watch = (w: Partial<WatchState>): WatchState => ({ completed: false, positionMs: 0, durationMs: 52 * 60_000, updatedAt: '2026-10-28T20:00:00Z', ...w })

function ep(over: Partial<Episode> = {}): Episode {
  return {
    id: 77,
    seasonId: 3,
    number: 7,
    title: 'The Long Dark',
    overview: 'A storm closes the harbour.',
    airDate: '2026-10-28',
    aired: true,
    isNew: true,
    hasFile: true,
    files: [file(501)],
    runtimeMin: 52,
    rating: 8.4,
    ...over,
  }
}

describe('episodeActions', () => {
  it('plays an episode not started, with its length, letting the player pick up any saved position', () => {
    const a = episodeActions(ep())
    expect(a.primary).toEqual({ kind: 'play', label: 'Play · 52 min', fileId: 501 })
    expect(a.fromStart).toBeUndefined()
    expect(a.mark).toEqual({ label: 'Mark watched', watched: true })
  })

  it('takes the length from the file without a runtime, and says just Play without either', () => {
    expect(episodeActions(ep({ runtimeMin: undefined, files: [file(501, 47 * 60_000 + 20_000)] })).primary.label).toBe('Play · 47 min')
    expect(episodeActions(ep({ runtimeMin: undefined, files: [file(501, 0)] })).primary.label).toBe('Play')
  })

  it('resumes a partly watched episode from its position, and offers the start', () => {
    const a = episodeActions(ep({ watch: watch({ positionMs: 23 * 60_000 + 10_000 }) }))
    expect(a.primary).toEqual({ kind: 'play', label: 'Resume from 23:10', fileId: 501, start: 1390 })
    expect(a.fromStart).toEqual({ label: 'From the start', fileId: 501, start: 0 })
    expect(a.mark).toEqual({ label: 'Mark watched', watched: true })
  })

  it('counts hours in a long resume', () => {
    expect(episodeActions(ep({ watch: watch({ positionMs: 3_723_000, durationMs: 2 * 3_600_000 }) })).primary.label).toBe('Resume from 1:02:03')
  })

  it('resumes the version the position was saved on', () => {
    const a = episodeActions(ep({ files: [file(501), file(502)], watch: watch({ positionMs: 60_000, fileId: 502 }) }))
    expect(a.primary).toMatchObject({ kind: 'play', fileId: 502, start: 60 })
    expect(a.fromStart).toMatchObject({ fileId: 502, start: 0 })
  })

  it('falls back to the best file when the saved version is gone', () => {
    expect(episodeActions(ep({ watch: watch({ positionMs: 10 * 60_000, fileId: 999 }) })).primary).toMatchObject({ fileId: 501, start: 600 })
  })

  it('plays a watched episode again, and offers to unmark it', () => {
    const a = episodeActions(ep({ watch: watch({ completed: true, positionMs: 50 * 60_000 }) }))
    expect(a.primary).toEqual({ kind: 'play', label: 'Play · 52 min', fileId: 501 })
    expect(a.fromStart).toBeUndefined()
    expect(a.mark).toEqual({ label: 'Mark unwatched', watched: false })
  })

  it('shows when an episode that has not aired yet airs, with nothing to press', () => {
    const a = episodeActions(ep({ aired: false, airDate: '2026-11-04', hasFile: false, files: [] }))
    expect(a).toEqual({ primary: { kind: 'unavailable', label: `Airs ${formatDate('2026-11-04')}` } })
    expect(episodeActions(ep({ aired: false, airDate: undefined, hasFile: false, files: [] })).primary.label).toBe('Not aired yet')
  })

  it('says an aired episode without a file is not in the library', () => {
    expect(episodeActions(ep({ hasFile: false, files: [] }))).toEqual({ primary: { kind: 'unavailable', label: 'Not in library' } })
  })

  it('plays a file even before the air date', () => {
    expect(episodeActions(ep({ aired: false, airDate: '2026-11-04' })).primary.kind).toBe('play')
  })
})

describe('isPartlyWatched', () => {
  it('needs a position and no completion', () => {
    expect(isPartlyWatched(ep())).toBe(false)
    expect(isPartlyWatched(ep({ watch: watch({ positionMs: 0 }) }))).toBe(false)
    expect(isPartlyWatched(ep({ watch: watch({ positionMs: 1 }) }))).toBe(true)
    expect(isPartlyWatched(ep({ watch: watch({ positionMs: 1, completed: true }) }))).toBe(false)
  })
})

describe('episodeFacts', () => {
  it('lists the air date, length and rating', () => {
    expect(episodeFacts(ep())).toEqual([`Aired ${formatDate('2026-10-28')}`, '52 min', '★ 8.4'])
    expect(episodeFacts(ep({ watch: watch({ completed: true }) }))).toEqual([`Aired ${formatDate('2026-10-28')}`, '52 min', '★ 8.4'])
  })
  it('says how much is left of a partly watched episode, at least a minute', () => {
    expect(episodeFacts(ep({ watch: watch({ positionMs: 23 * 60_000 }) }))).toContain('29 min left')
    expect(episodeFacts(ep({ watch: watch({ positionMs: 52 * 60_000 - 5_000 }) }))).toContain('1 min left')
    expect(episodeFacts(ep({ files: [file(501, 0)], watch: watch({ positionMs: 5_000, durationMs: 0 }) })).some((f) => f.endsWith('left'))).toBe(false)
  })
  it('says when an upcoming episode airs, and takes the length from the file without a runtime', () => {
    expect(episodeFacts(ep({ aired: false, airDate: '2026-11-04', rating: undefined }))).toEqual([`Airs ${formatDate('2026-11-04')}`, '52 min'])
    expect(episodeFacts(ep({ runtimeMin: undefined, files: [file(501, 47 * 60_000 + 20_000)], rating: 0 }))).toEqual([`Aired ${formatDate('2026-10-28')}`, '47 min'])
  })
})

describe('rowCode', () => {
  it('gives the season row the code alone, with the air date when it has not aired', () => {
    expect(rowCode(ep({ number: 6 }))).toBe('E06')
    expect(rowCode(ep({ number: 8, aired: false, airDate: '2026-11-04', hasFile: false }))).toBe(`E08 · Airs ${formatDate('2026-11-04')}`)
    // A file that came early plays, so it reads like any other.
    expect(rowCode(ep({ number: 8, aired: false, airDate: '2026-11-04' }))).toBe('E08')
  })
})

describe('episodeLine', () => {
  it('names the show and codes the episode, specials as season 0', () => {
    expect(episodeLine('Harbor Lights', 3, 7)).toBe('Harbor Lights · S03 E07')
    expect(episodeLine('Harbor Lights', 0, 2)).toBe('Harbor Lights · S00 E02')
  })
})

describe('trackSummary', () => {
  const audio = (over = {}) => ({ index: 1, codec: 'eac3', channels: 6, default: true, ...over })
  const sub = (language?: string) => ({ index: 2, codec: 'subrip', default: false, forced: false, image: false, language })
  it('gives the default audio’s layout and the subtitles’ languages once each', () => {
    expect(trackSummary({ audio: [audio({ channelLayout: '5.1(side)' })], subtitles: [sub('eng'), sub('spa'), sub('eng')] })).toEqual({
      channels: '5.1',
      subtitles: 'Subtitles: English, Spanish',
    })
  })
  it('words stereo and mono as channels, and leaves out what it cannot name', () => {
    expect(trackSummary({ audio: [audio({ channelLayout: 'stereo' })], subtitles: [] })).toEqual({ channels: '2.0', subtitles: '' })
    expect(trackSummary({ audio: [audio({ channelLayout: 'mono' })], subtitles: [sub('und'), sub()] })).toEqual({ channels: '1.0', subtitles: '' })
    expect(trackSummary({ audio: [audio({ channelLayout: 'hexadecagonal' })], subtitles: [] }).channels).toBe('')
    expect(trackSummary(undefined)).toEqual({ channels: '', subtitles: '' })
  })
})

describe('showYears', () => {
  it('leaves a running show open-ended and closes an ended one', () => {
    expect(showYears({ year: 2022, lastAirDate: '2026-10-28', status: 'Returning Series' })).toBe('2022–')
    expect(showYears({ year: 2019, lastAirDate: '2023-05-01', status: 'Ended' })).toBe('2019–2023')
    expect(showYears({ year: 2022 })).toBe('2022')
    expect(showYears({})).toBe('')
  })
})

describe('addedEpisode', () => {
  const card = { kind: 'show' as const, id: 12 }
  it("opens the one episode a Recently added card stands for, Specials too", () => {
    expect(addedEpisode({ ...card, episodeId: 901, seasonNumber: 31 })).toEqual({ showId: '12', number: '31', episodeId: '901' })
    expect(addedEpisode({ ...card, episodeId: 902, seasonNumber: 0 })).toEqual({ showId: '12', number: '0', episodeId: '902' })
  })
  it('keeps the show page for a season, several episodes, an older server and movies', () => {
    expect(addedEpisode(card)).toBeUndefined()
    expect(addedEpisode({ ...card, episodeId: 901 })).toBeUndefined()
    expect(addedEpisode({ kind: 'movie', id: 5, episodeId: 901, seasonNumber: 1 })).toBeUndefined()
  })
})

describe('pageEpisodes', () => {
  const list = [ep({ id: 1, hasFile: true }), ep({ id: 2, hasFile: false }), ep({ id: 3, hasFile: false, aired: false }), ep({ id: 4, hasFile: false })]
  it('lists those on disk and still to air, and the page’s own', () => {
    expect(pageEpisodes(list, 4, false).map((e) => e.id)).toEqual([1, 3, 4])
  })
  it('lists every episode with Show missing on', () => {
    expect(pageEpisodes(list, 1, true).map((e) => e.id)).toEqual([1, 2, 3, 4])
  })
})
