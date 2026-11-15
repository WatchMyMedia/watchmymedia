import { describe, expect, it } from 'vitest'
import { ApiError } from '../api/client'
import { ConfirmationCancelled } from '../api/withConfirm'
import {
  addBody,
  addFlowStep,
  chooseType,
  emptyAddDraft,
  entryWord,
  folderReady,
  folderResult,
  libraryRefusal,
  libraryState,
  mostUnmatched,
  nextOpen,
  nextScanIn,
  parentFolder,
  queueEntries,
  queuePlace,
  queueSummary,
  removalWords,
  removeRefusal,
  scanIntervalOptions,
  startRefusal,
  unmatchedBreakdown,
  type AddDraft,
  type Recorded,
} from './librariesSettings'
import type { components } from '../api/schema'

type Library = components['schemas']['Library']
type Group = components['schemas']['UnmatchedGroup']
type PathCheck = components['schemas']['PathCheck']

const apiError = (status: number, detail?: string) => new ApiError(new Response(null, { status }), detail === undefined ? undefined : { detail })

const check = (over: Partial<PathCheck> = {}): PathCheck => ({ path: '/media/movies', exists: true, readable: true, sample: ['The Long Field (2019)'], videoFiles: 412, ...over })

const lib = (over: Partial<Library> = {}): Library => ({
  id: 1, name: 'Movies', path: '/media/movies', type: 'movie', fileCount: 412, missingCount: 0, pendingCount: 0, unmatchedCount: 0,
  createdAt: '2026-11-01T00:00:00Z', updatedAt: '2026-11-01T00:00:00Z', lastScannedAt: '2026-11-14T00:00:00Z', ...over,
})

const file = (id: number) => ({ id, relativePath: `f${id}.mkv`, size: 1, matchStatus: 'unmatched' as const })
const group = (key: string, kind: Group['kind'], ids: number[], over: Partial<Group> = {}): Group => ({ key, kind, files: ids.map(file), candidates: [], reason: '', seasons: [], title: key, ...over })

describe('Add a library: which step an address opens', () => {
  const typed: AddDraft = { ...emptyAddDraft(), type: 'movie' }
  const checked: AddDraft = { ...typed, path: '/media/movies', check: check() }
  it('starts on Type, and waits for a type before Folder', () => {
    expect(addFlowStep(emptyAddDraft(), undefined).step.key).toBe('type')
    expect(addFlowStep(emptyAddDraft(), 'folder').step.key).toBe('type')
    expect(addFlowStep(typed, 'folder').step.key).toBe('folder')
  })
  it('waits for a folder the server can read before Name and add', () => {
    expect(addFlowStep(typed, 'name').step.key).toBe('folder')
    expect(addFlowStep({ ...typed, path: '/media/movies', check: check({ readable: false }) }, 'name').step.key).toBe('folder')
    expect(addFlowStep(checked, 'name').step.key).toBe('name')
  })
  it('never opens Added before the library is added', () => {
    expect(addFlowStep(checked, 'added').step.key).toBe('name')
  })
  it('keeps an added library on Added, and starts afresh from the flow’s own address', () => {
    const added = { ...checked, added: { id: 3, name: 'Movies', path: '/media/movies' } }
    expect(addFlowStep(added, 'added')).toEqual({ step: expect.objectContaining({ key: 'added' }), reset: false })
    expect(addFlowStep(added, 'name').step.key).toBe('added')
    expect(addFlowStep(added, undefined).reset).toBe(true)
    expect(addFlowStep(added, 'type').reset).toBe(true)
  })
  it('lets Back open an earlier step', () => {
    expect(addFlowStep(checked, 'type').step.key).toBe('type')
  })
})

describe('the folder check', () => {
  it('belongs to the folder typed now', () => {
    expect(folderReady({ path: '/media/movies/', check: check() })).toBe(true)
    expect(folderReady({ path: '/media/film', check: check() })).toBe(false)
    expect(folderReady({ path: '/media/movies', check: null })).toBe(false)
  })
  it('shows one result at a time, the most useful first', () => {
    expect(folderResult('movie', check({ exists: false, readable: false })).kind).toBe('missing')
    expect(folderResult('movie', check({ readable: false })).kind).toBe('unreadable')
    expect(folderResult('movie', check({ videoFiles: 0 })).kind).toBe('empty')
    expect(folderResult('movie', check({ sample: ['Season 01', 'Season 02'] })).kind).toBe('looksTV')
    expect(folderResult('tv', check({ path: '/media/tv/Harbor Lights', sample: ['Season 01'] }))).toEqual({ kind: 'oneShow', parent: '/media/tv' })
    expect(folderResult('tv', check({ truncated: true }))).toEqual({ kind: 'ok', count: 412, truncated: true })
  })
  it('finds the folder above, without climbing past the top', () => {
    expect(parentFolder('/media/tv/Harbor Lights')).toBe('/media/tv')
    expect(parentFolder('/media')).toBe('/')
    expect(parentFolder('/')).toBeNull()
    expect(parentFolder('D:\\TV\\Harbor Lights')).toBe('D:\\TV')
    expect(parentFolder('D:\\TV')).toBe('D:\\')
    expect(parentFolder('\\\\nas\\media')).toBeNull()
  })
  it('names the library after its type until a name is typed', () => {
    const movies = chooseType(emptyAddDraft(), 'movie')
    expect(movies.name).toBe('Movies')
    expect(chooseType(movies, 'tv').name).toBe('TV Shows')
    expect(chooseType({ ...movies, name: 'Films' }, 'tv').name).toBe('Films')
  })
  it('names a library after its type when the name is cleared', () => {
    expect(addBody({ ...emptyAddDraft(), type: 'tv', path: ' /media/tv ', name: '  ' })).toEqual({ type: 'tv', name: 'TV Shows', path: '/media/tv' })
  })
})

describe('refusals, worded as the boards draw them', () => {
  it('says nothing when the password dialog is cancelled', () => {
    expect(libraryRefusal(new ConfirmationCancelled(), '/media/movies', 'add')).toBeNull()
    expect(removeRefusal(new ConfirmationCancelled(), 'TV Shows')).toBeNull()
    expect(startRefusal(new ConfirmationCancelled(), 'scan', 'TV Shows')).toBeNull()
  })
  it('names the library a folder is inside', () => {
    const r = libraryRefusal(apiError(409, 'that folder is inside the "TV Shows" library (/media)'), '/media/movies', 'add')
    expect(r).toEqual({ title: '/media/movies is inside another library', body: 'TV Shows already covers /media. Choose a folder that no library holds, or change TV Shows’ folder first.', fix: 'folder' })
    expect(libraryRefusal(apiError(409, 'that folder is inside the "Movies" library (/mnt/pool)'), '/mnt/pool/tv', 'save')?.body).toBe('Movies already covers /mnt/pool. Choose a folder no library holds.')
  })
  it('names the library a folder holds, and a folder already used', () => {
    expect(libraryRefusal(apiError(409, 'that folder holds the "Movies" library (/media/movies)'), '/media', 'add')?.title).toBe('/media holds another library')
    expect(libraryRefusal(apiError(409, 'a library already uses that folder'), '/media/movies', 'add')?.title).toBe('/media/movies is already a library')
  })
  it('sends a folder that went away back to Folder', () => {
    expect(libraryRefusal(apiError(422, 'folder not found: /media/movies'), '/media/movies', 'add')).toMatchObject({ title: '/media/movies is no longer there', fix: 'folder' })
    expect(libraryRefusal(apiError(422, 'path must be absolute'), 'movies', 'add')?.title).toBe('movies isn’t a full path')
    expect(libraryRefusal(apiError(422, 'not a folder: /media/movies'), '/media/movies', 'save')?.title).toBe('/media/movies wasn’t found on the server')
  })
  it('says nothing was saved when the server can’t be reached or refuses otherwise', () => {
    expect(libraryRefusal(new TypeError('Failed to fetch'), '/media/movies', 'add')?.body).toContain('Nothing was added.')
    expect(libraryRefusal(apiError(500), '/media/movies', 'save')).toEqual({ title: 'Couldn’t save', body: 'Nothing was changed. Try again.' })
    expect(libraryRefusal(apiError(429), '/media/movies', 'add')?.title).toBe('Too many tries')
  })
  it('explains why Scan and Match didn’t start', () => {
    expect(startRefusal(apiError(503, 'TMDB API key not configured'), 'match', 'TV Shows')?.title).toBe('Match is off')
    expect(startRefusal(apiError(503, 'scanner not running'), 'scan', 'TV Shows')?.title).toBe('Couldn’t scan TV Shows')
    expect(startRefusal(new TypeError('x'), 'match', 'TV Shows')?.body).toBe('Nothing was changed. Try again in a few minutes.')
  })
  it('explains a failed remove, and treats an already-removed library as done', () => {
    expect(removeRefusal(new TypeError('x'), 'TV Shows')).toEqual({ title: 'Couldn’t remove TV Shows', body: 'The server didn’t answer and nothing was removed. Try again.' })
    expect(removeRefusal(apiError(404), 'TV Shows')?.gone).toBe(true)
  })
})

describe('a library’s state in words', () => {
  it('says what it is doing first, then what needs someone', () => {
    expect(libraryState(lib(), { scan: { scanned: 1, total: 2 } })).toEqual({ tone: 'now', label: 'Scanning' })
    expect(libraryState(lib({ match: { libraryId: 1, done: 0, total: 1 } }))).toEqual({ tone: 'now', label: 'Matching' })
    expect(libraryState(lib({ lastScanError: 'folder not found', lastScanErrorAt: '2026-11-14T00:00:00Z' }))).toEqual({ tone: 'err', label: 'Scan failed' })
    expect(libraryState(lib({ unmatchedCount: 14 }))).toEqual({ tone: 'warn', label: '14 unmatched' })
    expect(libraryState(lib({ pendingCount: 3 }))).toEqual({ tone: 'off', label: '3 waiting to match' })
    expect(libraryState(lib({ lastScannedAt: undefined }))).toEqual({ tone: 'off', label: 'Not scanned yet' })
    expect(libraryState(lib())).toEqual({ tone: 'ok', label: 'Up to date' })
  })
  it('lets a newer clean scan clear a saved failure', () => {
    const failed = lib({ lastScanError: 'folder not found', lastScanErrorAt: '2026-11-14T00:00:00Z' })
    expect(libraryState(failed, { lastScan: { finished: { failed: 0, partial: false }, at: Date.parse('2026-11-14T01:00:00Z') } }).label).toBe('Up to date')
  })
  it('puts the library with the most unmatched files in Needs you', () => {
    expect(mostUnmatched([lib({ id: 1 }), lib({ id: 2, unmatchedCount: 3 }), lib({ id: 3, unmatchedCount: 14 })])?.id).toBe(3)
    expect(mostUnmatched([lib()])).toBeNull()
  })
  it('breaks the unmatched count down by what each needs', () => {
    expect(unmatchedBreakdown('tv', [group('a', 'show', [1, 2, 3, 4, 5, 6, 7, 8, 9]), group('b', 'episodes', [10, 11, 12, 13, 14])])).toBe('9 need a show chosen, 5 have seasons TMDB numbers differently.')
    expect(unmatchedBreakdown('movie', [group('a', 'show', [1]), group('b', 'lookup', [2])])).toBe('1 needs a movie chosen, 1 hasn’t been looked up yet.')
  })
  it('keeps a schedule from config.yaml among the choices', () => {
    expect(scanIntervalOptions(15).map(([m]) => m)).toEqual([5, 15, 30, 60, 360, 1440])
    expect(scanIntervalOptions(45).at(-1)).toEqual([45, 'Every 45 minutes'])
    expect(nextScanIn(15)).toBe('15 minutes')
    expect(nextScanIn(60)).toBe('an hour')
    expect(nextScanIn(360)).toBe('6 hours')
    expect(nextScanIn(1440)).toBe('a day')
  })
})

describe('Remove: what stays and what goes', () => {
  const r = { movies: 0, shows: 96, episodes: 2140, files: 2140, watched: 812, inProgress: 37, people: 4, playsKept: 1530 }
  it('keeps the files and the plays, and deletes titles and watch marks', () => {
    const w = removalWords({ type: 'tv', path: '/media/tv' }, r)
    expect(w.stays).toEqual(['Every file in /media/tv. Nothing on disk is touched.', '1,530 plays in Activity history, with their titles and pictures.'])
    expect(w.deleted[0]).toBe('Matches and details for its 96 shows and 2,140 episodes.')
    expect(w.deleted[1]).toBe('812 watched marks and 37 resume points, across 4 people.')
    expect(w.deleted.join(' ')).not.toMatch(/plays|history/i)
  })
  it('words one title and nobody’s marks', () => {
    const w = removalWords({ type: 'movie', path: '/m' }, { ...r, movies: 1, watched: 0, inProgress: 0, people: 0, playsKept: 0 })
    expect(w.deleted[0]).toBe('Matches and details for its 1 movie.')
    expect(w.deleted[1]).toBe('Nobody has watched marks or resume points on them.')
    expect(w.stays[1]).toBe('Activity history. It has no plays from this library.')
  })
  it('says so when nothing in the library is matched yet', () => {
    const w = removalWords({ type: 'movie', path: '/m' }, { ...r, movies: 0 })
    expect(w.deleted[0]).toBe('Its list of files. None of them is matched to a movie yet.')
  })
  it('says it without numbers while they load', () => {
    expect(removalWords({ type: 'tv', path: '/media/tv' }, undefined).stays[1]).toMatch(/^Plays in Activity history/)
  })
})

describe('Review unmatched: the queue', () => {
  const snapshot = [group('night-ferry', 'show', [1, 2]), group('old-rips', 'show', [3]), group('harbor', 'episodes', [4, 5]), group('quiet', 'lookup', [6])]
  const none = new Set<string>()
  const noRecord = new Map<string, Recorded>()

  it('keeps the starting order and says what happened to each', () => {
    const live = [group('old-rips-ignored', 'ignored', [3]), group('harbor', 'episodes', [4, 5]), group('quiet', 'lookup', [6])]
    const e = queueEntries(snapshot, live, 0, new Set(['quiet']), noRecord)
    expect(e.map((x) => x.state)).toEqual(['matched', 'ignored', 'open', 'skipped'])
    expect(e.map((x) => entryWord(x, 'tv'))).toEqual(['matched', 'ignored', 'seasons', 'skipped'])
  })
  it('follows files into the group the server now holds them in', () => {
    const live = [group('night-ferry-episodes', 'episodes', [1, 2]), ...snapshot.slice(1)]
    const e = queueEntries(snapshot, live, 0, none, noRecord)
    expect(e[0]).toMatchObject({ state: 'open', kind: 'episodes', live: expect.objectContaining({ key: 'night-ferry-episodes' }) })
  })
  it('trusts a sort made here until the list is newer', () => {
    const recorded = new Map<string, Recorded>([['harbor', { state: 'seasons', at: 100 }]])
    expect(queueEntries(snapshot, snapshot, 50, none, recorded)[2].state).toBe('seasons')
    expect(queueEntries(snapshot, snapshot, 150, none, recorded)[2].state).toBe('open')
    expect(queueEntries(snapshot, snapshot.filter((g) => g.key !== 'harbor'), 150, none, recorded)[2].state).toBe('seasons')
  })
  it('opens a position, else the first open file, else the end', () => {
    const e = queueEntries(snapshot, snapshot, 0, none, noRecord)
    expect(queuePlace(e, '3')).toEqual({ at: 3 })
    expect(queuePlace(e, undefined)).toEqual({ at: 1 })
    expect(queuePlace(e, '9')).toEqual({ at: 1 })
    expect(queuePlace(e, 'done')).toEqual({ at: 1 })
    const allSkipped = queueEntries(snapshot, snapshot, 0, new Set(snapshot.map((g) => g.key)), noRecord)
    expect(queuePlace(allSkipped, 'done')).toEqual({ done: true })
    expect(queuePlace(allSkipped, '2')).toEqual({ at: 2 })
  })
  it('goes on to the next open file, then back round, then the end', () => {
    const e = queueEntries(snapshot, snapshot, 0, none, noRecord)
    expect(nextOpen(e, 1)).toEqual({ at: 2 })
    const firstOnly = e.map((x, i) => (i === 0 ? x : { ...x, state: 'matched' as const }))
    expect(nextOpen(firstOnly, 3)).toEqual({ at: 1 })
    expect(nextOpen(e.map((x) => ({ ...x, state: 'ignored' as const })), 0)).toEqual({ done: true })
  })
  it('ends on what happened to every file', () => {
    const live = [group('quiet', 'lookup', [6]), group('x', 'ignored', [3])]
    const recorded = new Map<string, Recorded>([['harbor', { state: 'seasons', at: 1, folder: 'Harbor Lights' }], ['night-ferry', { state: 'matched', at: 1, folder: 'Night Ferry' }]])
    const s = queueSummary(queueEntries(snapshot, live, 10, new Set(['quiet']), recorded), recorded)
    expect(s).toMatchObject({ total: 4, sorted: 3, skipped: 1, matched: 1, seasons: 1, ignored: 1, matchedFiles: 4, waitingFiles: 1, folders: 2 })
  })
})
