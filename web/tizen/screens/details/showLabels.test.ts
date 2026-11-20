import { describe, expect, it } from 'vitest'
import type { components } from '../../../src/api/schema'
import { formatDate } from '../../../src/lib/format'
import {
  abilities, arrivalOf, cardLine, dayWords, deleteWhere, replaceBody, replacedMessage, unansweredLines, episodeFacts, episodeKicker, episodeMore, episodePlay, episodeProgress, episodeTech, missingOf, onTopFor,
  playLabel, seasonActions, seasonLine, seasonMark, showFacts, showMark, showStatus, tabCount, topActions, zoneOffset, type Selection,
} from './showLabels'

type Episode = components['schemas']['Episode']
type Season = components['schemas']['Season']
type ShowDetail = components['schemas']['ShowDetail']
type MediaFile = components['schemas']['MediaFile']
type RequestOptions = components['schemas']['RequestOptions']

const today = new Date(2026, 10, 19)

function mediaFile(id: number, over: Partial<MediaFile> = {}): MediaFile {
  return {
    id, libraryId: 2, path: `/tv/${id}.mkv`, relativePath: `Harbor Lights/Season 03/${id}.mkv`, size: 4.2 * 1024 ** 3, modifiedAt: '', status: 'present',
    parsed: {} as MediaFile['parsed'], firstSeenAt: '', addedAt: '', lastSeenAt: '', matchStatus: 'matched', matchLocked: false, matchIgnored: false,
    width: 3840, height: 2160, videoCodec: 'hevc', audioCodec: 'eac3', hdr: 'hdr10', durationMs: 52 * 60_000, ...over,
  }
}

function ep(number: number, over: Partial<Episode> = {}): Episode {
  return { id: 300 + number, seasonId: 3, number, title: `Episode ${number}`, overview: '', files: [mediaFile(3000 + number)], hasFile: true, aired: true, isNew: false, airDate: '2026-10-01', runtimeMin: 52, ...over }
}

function season(number: number, over: Partial<Season> = {}): Season {
  return { id: number, showId: 201, number, name: `Season ${number}`, overview: '', episodeCount: 10, availableCount: 8, watchedCount: 4, airDate: '2026-09-01', ...over }
}

function show(over: Partial<ShowDetail> = {}): ShowDetail {
  return {
    id: 201, libraryId: 2, tmdbId: 5, title: 'Harbor Lights', overview: '', genres: ['Drama', 'Mystery'], episodeCount: 28, credits: [], hdr: false, totalSize: 0, fileCount: 28,
    totalEpisodes: 40, seasons: [season(1, { availableCount: 10, watchedCount: 10 }), season(2, { availableCount: 10, watchedCount: 10 }), season(3), season(0, { availableCount: 2, watchedCount: 0, episodeCount: 2 })],
    year: 2022, status: 'Returning Series', rating: 8.14, nextAirDate: '2026-11-21', managedBy: 'Sonarr', ...over,
  }
}

const watch = (positionMs: number, completed = false) => ({ positionMs, durationMs: 52 * 60_000, completed, updatedAt: '' })
const admin: RequestOptions = { movies: true, shows: true, needsApproval: false, problems: true, manage: true, backend: 'arr', inbox: 0, delete: true }
const member: RequestOptions = { ...admin, manage: false, delete: false, needsApproval: true }

describe('the episode on top', () => {
  const episodes = [ep(1, { watch: watch(0, true) }), ep(2, { watch: watch(10 * 60_000) }), ep(3)]

  it('reads how the page was reached from its route', () => {
    expect(arrivalOf({ name: 'show', id: 1 })).toEqual({ kind: 'show' })
    expect(arrivalOf({ name: 'show', id: 1, season: 2 })).toEqual({ kind: 'season' })
    expect(arrivalOf({ name: 'show', id: 1, season: 2, episodeId: 9 })).toEqual({ kind: 'episode', id: 9 })
  })

  it('arrives at up next with its reason, and keeps a pinned episode after a refetch', () => {
    const s = show({ nextEpisode: { episodeId: 302, seasonNumber: 3, episodeNumber: 2, title: '', fileId: 3002 } })
    const sel: Selection = { season: 3, arrived: true }
    expect(onTopFor(sel, { episodes }, s, { kind: 'show' })).toEqual({ episode: episodes[1], why: 'resume' })
    const pinned: Selection = { season: 3, episodeId: 302, why: 'resume', arrived: false }
    const next = show({ nextEpisode: { episodeId: 303, seasonNumber: 3, episodeNumber: 3, title: '', fileId: 3003 } })
    expect(onTopFor(pinned, { episodes }, next, { kind: 'show' })).toEqual({ episode: episodes[1], why: 'resume' })
  })

  it('opens a picked season on its first unfinished episode', () => {
    expect(onTopFor({ season: 3, arrived: false }, { episodes }, show(), { kind: 'show' }).episode?.number).toBe(2)
  })

  it('says when the episode a link named is gone', () => {
    const top = onTopFor({ season: 3, episodeId: 999, arrived: true }, { episodes }, show(), { kind: 'episode', id: 999 })
    expect(top.gone).toBe(true)
    expect(top.episode?.number).toBe(2)
  })

  it('names the episode as the boards do', () => {
    expect(episodeKicker('Harbor Lights', 3, 7)).toBe('Harbor Lights · S03 E07')
    expect(episodeKicker('Harbor Lights', 0, 2)).toBe('Harbor Lights · SP E02')
  })

  it('words air dates near today as days', () => {
    expect(dayWords('2026-11-18', today)).toBe('yesterday')
    expect(dayWords('2026-11-19', today)).toBe('today')
    expect(dayWords('2026-11-20', today)).toBe('tomorrow')
    expect(dayWords('2026-10-02', today)).toBe(formatDate('2026-10-02'))
  })

  it('lists the facts of one here and of one to come', () => {
    expect(episodeFacts(ep(8, { airDate: '2026-11-18', rating: 8.44 }), today)).toEqual(['Aired yesterday', '52 min', '★ 8.4'])
    expect(episodeFacts(ep(9, { hasFile: false, files: [], aired: false, airDate: '2026-11-20', rating: 9 }), today)).toEqual(['Airs tomorrow', '52 min'])
  })

  it('tags its file and lists its subtitles and size', () => {
    const probe = { audio: [{ index: 1, codec: 'eac3', channels: 6, channelLayout: '5.1(side)', default: true }], subtitles: [
      { index: 2, codec: 'subrip', language: 'en', default: false, forced: false, image: false }, { index: 3, codec: 'subrip', language: 'es', default: false, forced: false, image: false }] }
    const tech = episodeTech(mediaFile(1), probe)
    expect(tech.tags).toEqual(['4K', 'HEVC', 'HDR', 'EAC3 5.1'])
    expect(tech.line).toMatch(/^Subtitles: .+, .+ · 4\.2 GB$/)
    expect(episodeTech(mediaFile(1, { hdr: 'dolbyvision' }), undefined).tags).toEqual(['4K', 'HEVC', 'DV', 'EAC3'])
    expect(episodeTech(undefined, undefined)).toEqual({ tags: [], line: '' })
  })

  it('plays, or resumes where the server says, and starts over from 0', () => {
    expect(playLabel(ep(3))).toBe('Play')
    expect(playLabel(ep(2, { watch: { ...watch(23 * 60_000 + 10_000), fileId: 3002 } }))).toBe('Resume from 23:10')
    expect(playLabel(ep(4, { hasFile: false, files: [] }))).toBeUndefined()
    expect(episodePlay(show(), 3, ep(7, { title: 'The Long Dark' }))).toEqual({ kind: 'file', fileId: 3007, startMs: undefined, title: 'Harbor Lights', subtitle: 'S03 E07 · The Long Dark', backdropPath: undefined })
    expect(episodePlay(show(), 3, ep(7), 0)).toMatchObject({ startMs: 0 })
    expect(episodeProgress(ep(2, { watch: watch(26 * 60_000) }))).toBeCloseTo(0.5)
    expect(episodeProgress(ep(2))).toBeUndefined()
  })
})

describe('the episode row', () => {
  it('says what a card is waiting for, how much is left, or its length', () => {
    const neap = ep(5, { hasFile: false, files: [], monitored: true })
    expect(cardLine(neap, missingOf(neap, admin), today)).toEqual({ text: 'Missing', tone: 'muted', dot: true })
    const slack = ep(6, { hasFile: false, files: [], download: { state: 'downloading', progress: 0.62 } })
    expect(cardLine(slack, missingOf(slack, admin), today)).toEqual({ text: 'Downloading 62%', tone: 'accent', dot: true })
    const spring = ep(9, { hasFile: false, files: [], aired: false, airDate: '2026-11-20' })
    expect(cardLine(spring, undefined, today)).toEqual({ text: 'Airs tomorrow', tone: 'muted', dot: false })
    expect(cardLine(ep(7, { watch: watch(23 * 60_000) }), undefined, today).text).toBe('29 min left')
    expect(cardLine(ep(1, { watch: watch(0, true) }), undefined, today).text).toBe('Watched')
    expect(cardLine(ep(8), undefined, today).text).toBe('52 min')
  })

  it('knows why one is missing only where requests or Sonarr are on', () => {
    const gap = ep(5, { hasFile: false, files: [], monitored: true })
    expect(missingOf(gap, { ...member, shows: false, manage: false })).toBeUndefined()
    expect(missingOf(ep(5), admin)).toBeUndefined()
  })
})

describe('the season tabs', () => {
  it('count what is here, or with Show missing what is missing', () => {
    expect(tabCount(season(3), false)).toBe('8')
    expect(tabCount(season(3), true)).toBe('8 of 10')
    expect(tabCount(season(4, { availableCount: 0 }), true)).toBe('not in library')
  })

  it('sum up the season with its next to air', () => {
    const eps = [ep(9, { aired: false, airDate: '2026-11-20' }), ep(10, { aired: false, airDate: '2026-11-27' })]
    expect(seasonLine(season(3, { availableCount: 6 }), eps, false, today)).toBe('6 episodes · 4 watched · E09 airs tomorrow')
    expect(seasonLine(season(3, { availableCount: 6 }), [], true, today)).toBe('6 of 10 episodes · 4 missing')
    expect(seasonLine(season(1, { availableCount: 10, watchedCount: 10 }), [], false, today)).toBe('10 episodes · all watched')
    expect(seasonLine(season(4, { availableCount: 0, episodeCount: 6 }), [], true, today)).toBe('6 episodes · none in the library')
  })

  it('mark the season, or unmark it once all of it is watched', () => {
    expect(seasonMark(season(3))).toEqual({ label: 'Mark season watched', watched: true })
    expect(seasonMark(season(1, { availableCount: 10, watchedCount: 10 }))).toEqual({ label: 'Season watched', watched: false })
    expect(seasonMark(season(4, { availableCount: 0 }))).toBeUndefined()
  })

  it('offer the season’s request, and its search and monitor to admins', () => {
    const eps = [ep(5, { hasFile: false, files: [] })]
    expect(seasonActions(season(3), eps, show(), abilities(true, admin), '2026-11-19')).toEqual({ request: true, requested: false, search: true, monitor: false })
    expect(seasonActions(season(4, { monitored: false }), eps, show(), abilities(false, member), '2026-11-19')).toEqual({ request: true, requested: false, search: false, monitor: false })
    expect(seasonActions(season(4, { monitored: false }), eps, show(), abilities(true, admin), '2026-11-19').monitor).toBe(true)
  })
})

describe('what the person may do', () => {
  it('lets admins with Sonarr search and monitor, and members request', () => {
    const neap = ep(5, { hasFile: false, files: [], monitored: true })
    expect(topActions(neap, season(3), show(), abilities(true, admin), '2026-11-19')).toMatchObject({ request: false, search: true })
    expect(topActions(neap, season(3), show(), abilities(false, admin), '2026-11-19')).toMatchObject({ request: true, search: false })
  })

  it('puts Replace file and Delete episode in More for those allowed', () => {
    expect(episodeMore(ep(7), show(), abilities(true, admin))).toEqual({ report: true, replace: true, delete: true })
    expect(episodeMore(ep(7), show(), abilities(false, member))).toEqual({ report: true, replace: false, delete: false })
    expect(episodeMore(ep(5, { hasFile: false, files: [] }), show(), abilities(true, admin))).toEqual({ report: false, replace: false, delete: false })
  })
})

describe('about the show', () => {
  it('lists its facts and status', () => {
    expect(showFacts(show({ lastAirDate: '2026-11-11' }))).toBe('2022– · 3 seasons · 28 episodes · Drama · ★ 8.1')
    expect(showStatus(show({ nextAirDate: '2026-11-20' }), today)).toEqual({ text: 'Continuing · next episode tomorrow', active: true })
    expect(showStatus(show({ status: 'Ended' }), today)).toEqual({ text: 'Ended', active: false })
    expect(showStatus(show({ status: 'Canceled' }), today)?.text).toBe('Cancelled')
    expect(showStatus(show({ status: undefined }), today)).toBeUndefined()
  })

  it('marks the show watched until every season on disk is', () => {
    expect(showMark(show().seasons)).toEqual({ label: 'Mark show watched', watched: true })
    expect(showMark([season(1, { availableCount: 2, watchedCount: 2 }), season(2, { availableCount: 0, watchedCount: 0 })])).toEqual({ label: 'Show watched', watched: false })
    expect(showMark([season(1, { availableCount: 0 })])).toBeUndefined()
  })
})

describe('scrolling the page', () => {
  const m = { seasonsTop: 560, trackHeight: 1800, viewHeight: 1080, topPad: 72 }

  it('keeps the top in view down to the row, then brings the tabs to the top, then the end', () => {
    expect(zoneOffset('top', m)).toBe(0)
    expect(zoneOffset('episodes', m)).toBe(0)
    expect(zoneOffset('about', m)).toBe(488)
    expect(zoneOffset('related', m)).toBe(720)
    expect(zoneOffset('related', { ...m, trackHeight: 1200 })).toBe(488)
  })
})

describe('deleting and replacing', () => {
  const app = { connectionId: 1, connection: 'Sonarr', kind: 'sonarr' as const, folder: '/tv/Harbor Lights/Season 03', localFolder: '/media/tv/Harbor Lights/Season 03', files: 1, size: 1, extra: 0 }

  it('says where the file goes without naming the bin’s path', () => {
    expect(deleteWhere([{ ...app, recycleBin: '/recycle' }])).toBe('It goes to Sonarr’s recycling bin.')
    expect(deleteWhere([app])).toBe('It’s deleted for good: Sonarr has no recycling bin set.')
    expect(deleteWhere([{ ...app, files: 0 }])).toBe('')
    expect(deleteWhere([{ ...app, recycleBin: '/r' }, { ...app, connection: 'Sonarr 4K' }])).toBe('Sonarr moves it to its recycling bin. Sonarr 4K deletes it for good.')
  })

  it('names apps that didn’t answer in its own words', () => {
    expect(unansweredLines([app, { ...app, connection: 'Sonarr 4K', error: 'dial tcp 10.0.0.9: refused' }])).toEqual(['Sonarr 4K didn’t answer, so it can’t delete its copy.'])
  })

  it('words Replace file and what happened', () => {
    expect(replaceBody('Sonarr')).toBe('Sonarr blocks this release and searches for another. The episode is gone until the new copy arrives.')
    expect(replacedMessage({ connection: 'Sonarr', blocklisted: true })).toBe('Sonarr blocked this release and is searching for another.')
    expect(replacedMessage({ connection: 'Sonarr', blocklisted: false })).toBe('Sonarr is searching for another copy.')
  })
})
